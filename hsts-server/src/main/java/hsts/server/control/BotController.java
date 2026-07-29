package hsts.server.control;

import hsts.common.entity.Bot;
import hsts.common.entity.BotConversation;
import hsts.common.entity.Course;
import hsts.common.entity.KnowledgeSource;
import hsts.common.entity.Question;
import hsts.common.entity.Student;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.enums.BotStatus;
import hsts.common.enums.KnowledgeSourceType;
import hsts.common.protocol.BotQuestion;
import hsts.common.protocol.Response;
import hsts.common.protocol.SourceRequest;
import hsts.server.boundary.IStudyBotService;
import hsts.server.dao.BotDAO;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.QuestionDAO;
import hsts.server.dao.SubmissionDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-13, SUC-14 and SUC-15 / מתווה scenarios 13 and 14: the course study bot.
 *
 * <p>Three jobs in one controller because they are one feature: a teacher builds
 * the bot, a student uses it, and both look at what was asked.</p>
 *
 * <h2>The rules, and where each comes from</h2>
 *
 * <ul>
 *   <li><b>65</b> a teacher may create a bot for a course <em>she teaches</em>;</li>
 *   <li><b>66, 68</b> its knowledge is the question bank, uploaded PDF or Word, or
 *       typed text;</li>
 *   <li><b>67</b> a second teacher on the same course adds to the <em>existing</em>
 *       bot - which the UNIQUE key on {@code bot.course_code} guarantees;</li>
 *   <li><b>60</b> she turns it on and off;</li>
 *   <li><b>70</b> a student may use it only if enrolled <em>and</em> it is on;</li>
 *   <li><b>71</b> and not while she is sitting an exam in that course;</li>
 *   <li><b>72</b> if no usable answer comes back, she is told so;</li>
 *   <li><b>73, 74</b> every question and answer is kept, and she can read her own;</li>
 *   <li><b>75</b> the teacher sees usage <em>with no identities</em>.</li>
 * </ul>
 *
 * <p>The external service sits behind {@link IStudyBotService} (requirement 69), so
 * every rule above can be tested without the network or an API key.</p>
 */
public class BotController {

    /**
     * How much material is sent with a question.
     *
     * <p>Every model has a limit on how much it will read, and a teacher who
     * uploads a whole textbook would otherwise silently push the question itself
     * out of the request. Cut here, and the student is told it happened.</p>
     */
    private static final int CONTEXT_LIMIT = 30_000;

    private static final int MAX_QUESTION_LENGTH = 1_000;

    private final BotDAO botDAO;
    private final CourseDAO courseDAO;
    private final QuestionDAO questionDAO;
    private final SubmissionDAO submissionDAO;
    private final IStudyBotService botService;

    public BotController(BotDAO botDAO, CourseDAO courseDAO, QuestionDAO questionDAO,
                         SubmissionDAO submissionDAO, IStudyBotService botService) {
        this.botDAO = botDAO;
        this.courseDAO = courseDAO;
        this.questionDAO = questionDAO;
        this.submissionDAO = submissionDAO;
        this.botService = botService;
    }

    // =================================================================
    //  SUC-13: the teacher's side
    // =================================================================

    /**
     * Every bot on every course she teaches.
     *
     * <p>A teacher of two courses sees both courses' bots in one list, and a course
     * with several bots contributes all of them. The list is ordered by course so
     * they group naturally on screen.</p>
     */
    public Response listMyBots(User user) {
        if (!(user instanceof Teacher teacher)) {
            return Response.error("Only a teacher manages a course bot.");
        }
        try {
            List<Bot> bots = new ArrayList<>();
            List<String> courses = new ArrayList<>(teacher.getTaughtCourseCodes());
            java.util.Collections.sort(courses);
            for (String courseCode : courses) {
                bots.addAll(botDAO.findAllByCourse(courseCode));
            }
            long active = bots.stream().filter(Bot::isActive).count();
            return Response.ok(bots, bots.isEmpty()
                    ? "None of your courses has a bot yet."
                    : bots.size() + " bot(s) across " + courses.size()
                      + " course(s) you teach, " + active + " switched on.");
        } catch (SQLException e) {
            return Response.error("Could not load your bots: " + e.getMessage());
        }
    }

    /**
     * Every course she teaches - she may create a bot for any of them.
     *
     * <p>This used to list only courses with <em>no</em> bot, because a course could
     * have only one. A course may now have several, so every course she teaches is
     * offered; the list says how many each already has so she can see what she is
     * adding to.</p>
     */
    public Response listCoursesWithoutBot(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher creates a course bot.");
        }
        try {
            List<Course> courses = courseDAO.findByTeacher(user.getUserId());
            return Response.ok(courses, courses.isEmpty()
                    ? "You do not teach any courses."
                    : "You teach " + courses.size() + " course(s).");
        } catch (SQLException e) {
            return Response.error("Could not load your courses: " + e.getMessage());
        }
    }

    /** Requirements 65 and 66: create a bot for a course she teaches. */
    public Response createBot(User user, String courseCode, String name) {
        String refusal = refuseIfNotHerCourse(user, courseCode);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (name == null || name.trim().isEmpty()) {
            return Response.error("Give the bot a name.");
        }
        if (name.trim().length() > 100) {
            return Response.error("That name is too long - 100 characters at most.");
        }
        try {
            // A course may have several bots. Requirement 67 is unaffected: any
            // teacher of the course may add material to any of them, and the source
            // list names who added what. What the requirement asks is that a
            // colleague CAN add to an existing bot, not that she cannot make another.
            List<Bot> existing = botDAO.findAllByCourse(courseCode);
            for (Bot other : existing) {
                if (other.getName().equalsIgnoreCase(name.trim())) {
                    return Response.error(other.getCourseName() + " already has a bot called \""
                            + other.getName() + "\". Give this one a different name so they "
                            + "can be told apart.");
                }
            }
            Bot bot = botDAO.insertBot(courseCode, name.trim(), user.getUserId());
            return Response.ok(bot, existing.isEmpty()
                    ? "Bot created. It is not active yet - add some material for it to "
                      + "read, then turn it on."
                    : "Bot created - that course now has " + (existing.size() + 1)
                      + " bots. Only one can be switched on at a time.");
        } catch (SQLException e) {
            return Response.error("Could not create the bot: " + e.getMessage());
        }
    }

    /**
     * Requirement 60, plus the one-active-per-course rule.
     *
     * <p>Switching one on switches the course's others off, and says which. The
     * alternative - refusing until she turns the other off herself - is two steps to
     * express one intention, and she has already said what she wants.</p>
     *
     * <p>A bot with no material may not be switched on at all: requirement 70 would
     * otherwise let a student interrogate a bot that knows nothing.</p>
     */
    public Response setStatus(User user, int botId, BotStatus status) {
        try {
            Bot bot = botDAO.findById(botId);
            if (bot == null) {
                return Response.error("That bot does not exist.");
            }
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            if (status == BotStatus.ACTIVE && !bot.hasKnowledge()) {
                return Response.error("This bot has nothing to read yet. Add the question "
                        + "bank, a document or some text before turning it on.");
            }

            String alsoOff = "";
            if (status == BotStatus.ACTIVE) {
                // Which ones were on, before they are turned off, so she can be told.
                List<String> wereOn = new ArrayList<>();
                for (Bot other : botDAO.findAllByCourse(bot.getCourseCode())) {
                    if (other.getBotId() != botId && other.isActive()) {
                        wereOn.add("\"" + other.getName() + "\"");
                    }
                }
                botDAO.deactivateOthers(bot.getCourseCode(), botId);
                if (!wereOn.isEmpty()) {
                    alsoOff = " " + String.join(" and ", wereOn)
                            + (wereOn.size() == 1 ? " was" : " were")
                            + " switched off - only one bot per course can be on.";
                }
            }

            botDAO.setStatus(botId, status);
            Bot updated = botDAO.findById(botId);
            return Response.ok(updated, status == BotStatus.ACTIVE
                    ? "\"" + bot.getName() + "\" is on. Students on "
                      + bot.getCourseName() + " can use it now." + alsoOff
                    : "\"" + bot.getName() + "\" is off. Students on "
                      + bot.getCourseName() + " have no bot until one is switched on.");
        } catch (SQLException e) {
            return Response.error("Could not change the bot: " + e.getMessage());
        }
    }

    /**
     * Deletes a bot, its material and its history.
     *
     * <p><b>The history goes with it.</b> Requirement 73 says the questions and
     * answers are kept, and this destroys them - so the reply says how many were
     * lost, and the screen asks first. The customer asked for a plain delete knowing
     * that; the conflict is written down in {@code docs/03_document_updates.md}
     * rather than hidden.</p>
     */
    public Response deleteBot(User user, int botId) {
        try {
            Bot bot = botDAO.findById(botId);
            if (bot == null) {
                return Response.error("That bot does not exist.");
            }
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            int lost = botDAO.deleteBotAndHistory(botId);
            return Response.ok(bot.getCourseCode(),
                    "Deleted \"" + bot.getName() + "\"."
                  + (lost == 0 ? " It had never been used."
                               : " Its " + lost + " stored question(s) and answer(s) "
                                 + "were deleted with it."));
        } catch (SQLException e) {
            return Response.error("Could not delete that bot: " + e.getMessage());
        }
    }

    /** How much would be lost by deleting - so the screen can ask properly. */
    public Response describeDeletion(User user, int botId) {
        try {
            Bot bot = botDAO.findById(botId);
            if (bot == null) {
                return Response.error("That bot does not exist.");
            }
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(botDAO.countConversations(botId), null);
        } catch (SQLException e) {
            return Response.error("Could not check that bot: " + e.getMessage());
        }
    }

    /**
     * Requirements 66, 67 and 68: add material.
     *
     * <p>An upload arrives as bytes and is turned into text <b>here</b>, so a file
     * that cannot be read is refused now rather than stored and quietly ignored.
     * Text extraction lives in {@code DocumentText}, which is honest about PDFs.</p>
     */
    public Response addSource(User user, SourceRequest request) {
        if (request == null) {
            return Response.error("Nothing to add.");
        }
        try {
            Bot bot = botDAO.findById(request.getBotId());
            if (bot == null) {
                return Response.error("That bot does not exist.");
            }
            // Requirement 67: any teacher of the course, not only its creator.
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            if (request.getType() == null) {
                return Response.error("Say what kind of material this is.");
            }

            String title = (request.getTitle() == null || request.getTitle().isBlank())
                    ? request.getType().getDisplayName() : request.getTitle().trim();
            String content;

            switch (request.getType()) {
                case QUESTION_BANK -> {
                    content = questionBankText(bot.getCourseCode());
                    if (content.isBlank()) {
                        return Response.error("There are no questions in "
                                + bot.getCourseName() + "'s bank to give it.");
                    }
                }
                case FREE_TEXT -> {
                    if (request.getText() == null || request.getText().trim().length() < 20) {
                        return Response.error("Type at least a couple of sentences "
                                + "for the bot to learn from.");
                    }
                    content = request.getText().trim();
                }
                case WORD -> {
                    try {
                        content = hsts.server.util.DocumentText.fromWord(
                                request.getFileBytes());
                    } catch (hsts.server.util.DocumentText.UnreadableDocumentException e) {
                        return Response.error(e.getMessage());
                    }
                }
                case PDF -> {
                    try {
                        content = hsts.server.util.DocumentText.fromPdf(
                                request.getFileBytes());
                    } catch (hsts.server.util.DocumentText.UnreadableDocumentException e) {
                        return Response.error(e.getMessage());
                    }
                }
                default -> {
                    return Response.error("That kind of material is not supported.");
                }
            }

            botDAO.insertSource(request.getBotId(), request.getType(), title,
                    content, user.getUserId());
            Bot updated = botDAO.findById(request.getBotId());
            return Response.ok(updated, "Added \"" + title + "\" - "
                    + content.length() + " characters of material.");

        } catch (SQLException e) {
            return Response.error("Could not add that material: " + e.getMessage());
        }
    }

    /** מתווה 13 item 2: material can be removed again. */
    public Response removeSource(User user, int sourceId) {
        try {
            KnowledgeSource source = botDAO.findSource(sourceId);
            if (source == null) {
                return Response.error("That material is not there.");
            }
            Bot bot = botDAO.findById(source.getBotId());
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            botDAO.deleteSource(sourceId);

            Bot updated = botDAO.findById(bot.getBotId());
            // A bot left with nothing to read cannot stay on - requirement 70 would
            // otherwise let a student ask a bot that knows nothing at all.
            String extra = "";
            if (!updated.hasKnowledge() && updated.isActive()) {
                botDAO.setStatus(updated.getBotId(), BotStatus.INACTIVE);
                updated = botDAO.findById(bot.getBotId());
                extra = " It has nothing left to read, so it has been turned off.";
            }
            return Response.ok(updated, "Removed \"" + source.getTitle() + "\"." + extra);
        } catch (SQLException e) {
            return Response.error("Could not remove that material: " + e.getMessage());
        }
    }

    /** Requirement 75: usage with no identities. */
    public Response getUsage(User user, int botId) {
        try {
            Bot bot = botDAO.findById(botId);
            if (bot == null) {
                return Response.error("That bot does not exist.");
            }
            String refusal = refuseIfNotHerCourse(user, bot.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(botDAO.usageOf(bot), null);
        } catch (SQLException e) {
            return Response.error("Could not load the usage: " + e.getMessage());
        }
    }

    // =================================================================
    //  SUC-14: the student's side
    // =================================================================

    /**
     * One row per course of hers: the bot she can actually reach.
     *
     * <p>A course may have several bots, but she has no business choosing between a
     * teacher's drafts - she gets the one that is switched on. If none is on, she is
     * shown one of them anyway, so the screen can say "not switched on" rather than
     * pretend the course has no bot at all.</p>
     */
    public Response listAvailableBots(User user) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student uses a course bot.");
        }
        try {
            List<Bot> bots = new ArrayList<>();
            List<String> courses = new ArrayList<>(student.getEnrolledCourseCodes());
            java.util.Collections.sort(courses);
            for (String courseCode : courses) {
                Bot active = botDAO.findActiveByCourse(courseCode);
                if (active != null) {
                    bots.add(active);
                    continue;
                }
                List<Bot> all = botDAO.findAllByCourse(courseCode);
                if (!all.isEmpty()) {
                    bots.add(all.get(0));        // so she is told it is switched off
                }
            }
            long usable = bots.stream().filter(Bot::isActive).count();
            return Response.ok(bots, bots.isEmpty()
                    ? "None of your courses has a study bot."
                    : bots.size() + " course bot(s), " + usable + " you can use now.");
        } catch (SQLException e) {
            return Response.error("Could not load the bots: " + e.getMessage());
        }
    }

    /**
     * Requirements 70 to 73: ask the bot.
     *
     * <p>The order of the checks is the order of the requirements, and each has its
     * own message - "not enrolled", "not switched on" and "you are in an exam" are
     * three different situations and a student should be told which.</p>
     */
    public Response ask(User user, BotQuestion request) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student uses a course bot.");
        }
        if (request == null || request.getQuestion() == null
                || request.getQuestion().trim().isEmpty()) {
            return Response.error("Type a question first.");
        }
        String question = request.getQuestion().trim();
        if (question.length() > MAX_QUESTION_LENGTH) {
            return Response.error("That question is very long. Keep it under "
                    + MAX_QUESTION_LENGTH + " characters.");
        }

        try {
            // Requirement 70, first half: she must be on the course.
            if (!student.getEnrolledCourseCodes().contains(request.getCourseCode())) {
                return Response.error("You are not enrolled in that course.");
            }

            // Requirement 70, second half: she reaches whichever bot is switched on,
            // and only one per course can be.
            Bot bot = botDAO.findActiveByCourse(request.getCourseCode());
            if (bot == null) {
                List<Bot> all = botDAO.findAllByCourse(request.getCourseCode());
                if (all.isEmpty()) {
                    return Response.error("That course does not have a study bot.");
                }
                return Response.error(all.size() == 1
                        ? "\"" + all.get(0).getName() + "\" is not switched on at the "
                          + "moment. Your teacher turns it on and off."
                        : "None of that course's " + all.size() + " bots is switched on "
                          + "at the moment. Your teacher turns them on and off.");
            }

            // Requirement 71 and the מתווה note: not while she is sitting that
            // course's exam. Checked against the live rows, so it lifts by itself
            // the moment she hands in.
            if (submissionDAO.isSittingAnExamIn(user.getUserId(), request.getCourseCode())) {
                return Response.error("The study bot is not available while you are "
                        + "sitting an exam in this course.");
            }

            if (!bot.hasKnowledge()) {
                return Response.error("\"" + bot.getName() + "\" has nothing to read yet.");
            }
            if (!botService.isConfigured()) {
                // Told apart from "no answer": this one is the school's to fix.
                return Response.error("The study bot is not set up on the server yet. "
                        + "Please tell your teacher.");
            }

            String context = buildContext(bot);
            String answer;
            try {
                answer = botService.ask(context, question);
            } catch (IStudyBotService.BotUnavailableException e) {
                // Requirement 72: no suitable answer, so say so in her words.
                return Response.error(e.getMessage());
            }

            // Requirement 73: both sides are kept.
            botDAO.insertConversation(bot.getBotId(), user.getUserId(), question, answer);

            BotConversation conversation = new BotConversation();
            conversation.setBotId(bot.getBotId());
            conversation.setCourseName(bot.getCourseName());
            conversation.setStudentId(user.getUserId());
            conversation.setStudentName(user.getFullName());
            conversation.setQuestion(question);
            conversation.setAnswer(answer);
            conversation.setAskedAt(java.time.LocalDateTime.now().withNano(0));
            return Response.ok(conversation, null);

        } catch (SQLException e) {
            return Response.error("Could not ask the bot: " + e.getMessage());
        }
    }

    /** Requirement 74: her own history and nobody else's. */
    public Response myHistory(User user) {
        if (!(user instanceof Student)) {
            return Response.error("Only a student has a bot history of her own.");
        }
        try {
            List<BotConversation> history = botDAO.findByStudent(user.getUserId());
            return Response.ok(history, history.isEmpty()
                    ? "You have not asked the bot anything yet."
                    : history.size() + " question(s) you have asked.");
        } catch (SQLException e) {
            return Response.error("Could not load your history: " + e.getMessage());
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================

    /** Every current question in the course, as text the bot can read. */
    private String questionBankText(String courseCode) throws SQLException {
        StringBuilder out = new StringBuilder();
        for (Question q : questionDAO.findCurrentByCourse(courseCode)) {
            out.append("Q: ").append(q.getText()).append('\n');
            if (q.getTopic() != null) {
                out.append("   topic: ").append(q.getTopic()).append('\n');
            }
            for (hsts.common.entity.Answer a : q.getAnswers()) {
                out.append("   ").append(a.getAnswerNo()).append(". ")
                   .append(a.getText())
                   .append(a.isCorrect() ? "   [correct]" : "")
                   .append('\n');
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    /**
     * Joins the bot's material into the text sent with a question.
     *
     * <p>Cut to {@link #CONTEXT_LIMIT}. Without the cut, a large upload would push
     * the question itself past whatever the service will read, and the failure
     * would look like the bot being stupid rather than the request being too big.</p>
     */
    String buildContext(Bot bot) {
        StringBuilder out = new StringBuilder();
        for (KnowledgeSource source : bot.getSources()) {
            String piece = "--- " + source.getTitle() + " ("
                         + source.getType().getDisplayName() + ") ---\n"
                         + source.getContent() + "\n\n";
            if (out.length() + piece.length() > CONTEXT_LIMIT) {
                int room = CONTEXT_LIMIT - out.length();
                if (room > 200) {
                    out.append(piece, 0, room).append("\n[material truncated]");
                }
                break;
            }
            out.append(piece);
        }
        return out.toString().trim();
    }

    /**
     * Requirements 65 and 67 in one place.
     *
     * <p>Any teacher of the course, not only the one who created the bot - which is
     * exactly what requirement 67 asks for. Coordinators pass too: a
     * {@code SubjectCoordinator} is a {@code Teacher} and has taught courses of her
     * own.</p>
     */
    private String refuseIfNotHerCourse(User user, String courseCode) {
        if (!(user instanceof Teacher teacher)) {
            return "Only a teacher manages a course bot.";
        }
        if (courseCode == null || courseCode.isBlank()) {
            return "No course was chosen.";
        }
        if (!teacher.getTaughtCourseCodes().contains(courseCode)) {
            return "You do not teach that course, so its bot is not yours to change.";
        }
        return null;
    }
}
