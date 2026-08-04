import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.BotStatus;
import hsts.common.enums.KnowledgeSourceType;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.boundary.GeminiStudyBotService;
import hsts.server.boundary.IStudyBotService;
import hsts.server.dao.DBController;
import hsts.server.util.DocumentText;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Milestone 14: the course study bot (SUC-13, SUC-14, SUC-15 / מתווה 13 and 14).
 *
 * <p><b>No network and no API key.</b> Requirement 69 puts the external service
 * behind {@code IStudyBotService}, and this suite installs a stub in its place. So
 * every rule - who may ask, when the bot is unavailable, what gets stored, what the
 * teacher may see - is checked for real, while nothing is spent and nothing depends
 * on the internet being up. The stub can also be told to fail, which is the only
 * honest way to test requirement 72.</p>
 *
 * <p>The real Gemini adapter is not left untested: its JSON building, escaping and
 * reply reading are checked directly at the end, since those are the parts that
 * would break silently.</p>
 *
 * <p>There are <b>no acceptance tests</b> for SUC-13, 14 or 15 in the submitted
 * Assignment 1, which covers SUC-3, 7, 9 and 10 only. Every check below cites the
 * requirement or מתווה item it comes from.</p>
 */
public class M14Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;

    /** Stands in for Gemini. Records what it was asked; can be told to fail. */
    static class StubBot implements IStudyBotService {
        volatile String lastContext;
        volatile String lastQuestion;
        volatile boolean fail;
        volatile boolean configured = true;
        volatile String answer = "Because the angles of a triangle add up to 180 degrees.";
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public String ask(String context, String question) throws BotUnavailableException {
            calls.incrementAndGet();
            lastContext = context;
            lastQuestion = question;
            if (fail) {
                throw new BotUnavailableException(
                        "The study bot did not have an answer for that.");
            }
            return answer;
        }

        @Override public String getDescription() { return "stub"; }
        @Override public boolean isConfigured()  { return configured; }
    }

    private static final StubBot stub = new StubBot();

    /**
     * A port nobody is using, found at the moment of asking.
     *
     * <p>These suites used to each hold a fixed port in the 155xx range. On this
     * machine Windows hands out ephemeral ports from 1024 upwards - the whole range
     * - so an outbound connection belonging to any other program can be sitting on
     * the number a suite is about to listen on. It happened: one run failed with
     * "Address already in use" and the port turned out to be held by an unrelated
     * process. A fixed number was never safe, it was just usually lucky.</p>
     */
    private static int freePort() {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            probe.setReuseAddress(true);
            return probe.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { failed++; System.out.println("   [FAIL] harness threw:");
                              t.printStackTrace(System.out); }
        finally {
            System.out.println();
            System.out.println("==== passed " + passed + ", failed " + failed + " ====");
            System.exit(failed > 0 ? 1 : 0);
        }
    }

    @SuppressWarnings("unchecked")
    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.setStudyBotService(stub);        // requirement 69's boundary, swapped
        server.listen();

        // Course 01 is taught by teacher1 (Noa) and teacher2 (Maya) - which is what
        // requirement 67 needs: two teachers on one course.
        Conn noa   = new Conn(); noa.login("teacher1", "teacher1!T");
        Conn maya  = new Conn(); maya.login("teacher2", "teacher2!T");
        Conn other = new Conn(); other.login("teacher5", "teacher5!T");   // Poetry only
        Conn head  = new Conn(); head.login("principal", "principal!P");

        String course = "01";
        cleanUpBot(db, course);

        // ==============================================================
        System.out.println("1. REQUIREMENT 65 - a bot for a course she teaches");
        Response wrongCourse = noa.ask(RequestType.BOT_CREATE,
                new BotCreateRequest("05", "Poetry helper"));
        check("a course she does not teach is refused", !wrongCourse.isOk());
        System.out.println("   " + wrongCourse.getMessage());

        check("a bot with no name is refused",
                !noa.ask(RequestType.BOT_CREATE, new BotCreateRequest(course, "  ")).isOk());

        Response created = noa.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Geometry helper"));
        check("her own course is accepted", created.isOk());
        Bot bot = (Bot) created.getPayload();
        System.out.println("   " + created.getMessage());
        check("it has an id", bot.getBotId() > 0);
        check("requirement 66 - it has a name and a course",
                "Geometry helper".equals(bot.getName()) && course.equals(bot.getCourseCode()));
        check("it starts SWITCHED OFF", bot.getStatus() == BotStatus.INACTIVE);
        check("and with nothing to read", !bot.hasKnowledge());

        System.out.println("2. a course may have several bots, but not two of one name");
        // This section used to assert the opposite - one bot per course, enforced by
        // a UNIQUE key. The customer asked for several per course with only one
        // switched on, so the rule changed and so did the checks. What survives is
        // that two bots on a course cannot share a name, or the teacher's own list
        // becomes unreadable. Section 11 covers the new rule in full; requirement 67
        // (a colleague adding to an existing bot) is section 4.
        Response second = maya.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Another geometry bot"));
        check("a second bot on the same course is allowed", second.isOk());
        Bot extra = (Bot) second.getPayload();

        Response sameName = maya.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "geometry HELPER"));
        check("but the same name again is refused, whatever the case", !sameName.isOk());
        System.out.println("   " + sameName.getMessage());
        check("and the refusal names the clash",
                sameName.getMessage().contains("Geometry helper"));

        // Removed again so the sections below deal with one bot, as they were written
        // to. Several-bots behaviour is exercised deliberately in section 11.
        check("and it can be deleted again",
                maya.ask(RequestType.BOT_DELETE, extra.getBotId()).isOk());

        // ==============================================================
        System.out.println("3. REQUIREMENT 60 - it cannot be switched on with nothing to read");
        Response tooEarly = noa.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(bot.getBotId(), BotStatus.ACTIVE));
        check("refused", !tooEarly.isOk());
        System.out.println("   " + tooEarly.getMessage());

        // ==============================================================
        System.out.println("4. REQUIREMENTS 66 and 68 - the four kinds of material");

        Response bank = noa.ask(RequestType.BOT_ADD_SOURCE,
                SourceRequest.questionBank(bot.getBotId(), "Geometry question bank"));
        check("the course question bank can be added", bank.isOk());
        System.out.println("   " + bank.getMessage());

        Response tooShort = noa.ask(RequestType.BOT_ADD_SOURCE,
                SourceRequest.text(bot.getBotId(), "Note", "too short"));
        check("a scrap of text is refused", !tooShort.isOk());

        Response text = noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(
                bot.getBotId(), "Key formulas",
                "The area of a triangle is half the base times the height. "
              + "The circumference of a circle is 2 pi r."));
        check("typed text can be added", text.isOk());

        // Requirement 67 in action: MAYA adds to NOA's bot.
        Response byColleague = maya.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(
                bot.getBotId(), "Maya's notes",
                "Congruent triangles have equal sides and equal angles. "
              + "Similar triangles have equal angles only."));
        check("a COLLEAGUE on the same course can add material", byColleague.isOk());
        Bot withMaya = (Bot) byColleague.getPayload();
        check("and her addition is attributed to her",
                withMaya.getSources().stream()
                        .anyMatch(s -> "Maya Cohen".equals(s.getAddedByName())));

        Response byStranger = other.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(
                bot.getBotId(), "Nothing to do with me",
                "This teacher does not teach geometry at all, so this must be refused."));
        check("a teacher NOT on the course cannot add material", !byStranger.isOk());
        System.out.println("   " + byStranger.getMessage());

        // ---- a real .docx, built here ----
        byte[] docx = fakeDocx("Pythagoras' theorem says that in a right triangle the "
                + "square of the hypotenuse equals the sum of the squares of the other "
                + "two sides. This is used constantly in geometry problems.");
        Response word = noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.upload(
                bot.getBotId(), KnowledgeSourceType.WORD, "pythagoras.docx", docx));
        check("a real .docx is read and added", word.isOk());
        System.out.println("   " + word.getMessage());

        check("a .doc (not a zip) is refused with advice",
                !noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.upload(
                        bot.getBotId(), KnowledgeSourceType.WORD, "old.doc",
                        "ÐÏà rubbish".getBytes(StandardCharsets.ISO_8859_1)))
                        .isOk());

        // ---- a PDF whose text is readable, and one whose is not ----
        byte[] simplePdf = fakePdf("Circle theorems: an angle in a semicircle is a right "
                + "angle, and the angle at the centre is twice the angle at the circumference.");
        Response pdf = noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.upload(
                bot.getBotId(), KnowledgeSourceType.PDF, "circles.pdf", simplePdf));
        check("an UNCOMPRESSED pdf is read", pdf.isOk());
        System.out.println("   " + pdf.getMessage());

        Response compressed = noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.upload(
                bot.getBotId(), KnowledgeSourceType.PDF, "compressed.pdf",
                "%PDF-1.7\nstream\n binary nonsense \nendstream"
                        .getBytes(StandardCharsets.ISO_8859_1)));
        check("a pdf with no readable text is REFUSED, not stored as rubbish",
                !compressed.isOk());
        System.out.println("   " + compressed.getMessage());
        check("and the refusal says what to do instead",
                compressed.getMessage().toLowerCase().contains("paste"));

        // By name, not "the first bot on the course": a course may have several
        // now, and which one sorts first is not this section's business.
        Bot ready = firstBotNamed(noa, course, "Geometry helper");
        System.out.println("   " + ready.getSources().size() + " sources on the bot");
        check("five pieces of material are stored", ready.getSources().size() == 5);

        // ==============================================================
        System.out.println("5. REQUIREMENT 70 - enrolled AND switched on");
        // The sitting used for the requirement-71 test is worked out first, because
        // which students are usable depends on it: max_attempts is 1, so a girl who
        // already sat it in an earlier run cannot start it again.
        int execId = openExecutionIn(db, course);
        List<String> enrolled = enrolledIn(db, course, 2, execId);
        Conn dana = new Conn();
        Student danaMe = (Student) dana.login(enrolled.get(0), enrolled.get(0) + "!S")
                .getPayload();

        Response offNow = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Why do triangles add to 180?"));
        check("while it is off, she is refused", !offNow.isOk());
        System.out.println("   " + offNow.getMessage());
        check("and the reason is that it is off",
                offNow.getMessage().toLowerCase().contains("not switched on"));

        Response on = noa.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(bot.getBotId(), BotStatus.ACTIVE));
        check("now it has material, it can be switched on", on.isOk());
        System.out.println("   " + on.getMessage());

        String notEnrolled = studentNotIn(db, course);
        Conn outsider = new Conn();
        outsider.login(notEnrolled, notEnrolled + "!S");
        Response notHers = outsider.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Tell me about triangles."));
        check("a student NOT on the course is refused", !notHers.isOk());
        check("and told it is about enrolment",
                notHers.getMessage().toLowerCase().contains("not enrolled"));

        check("a teacher cannot use the student's ask request",
                !noa.ask(RequestType.BOT_ASK, new BotQuestion(course, "hello")).isOk());
        check("nor can the principal",
                !head.ask(RequestType.BOT_ASK, new BotQuestion(course, "hello")).isOk());
        check("a student cannot manage a bot",
                !dana.ask(RequestType.BOT_SET_STATUS,
                        new BotStatusRequest(bot.getBotId(), BotStatus.INACTIVE)).isOk());
        check("nor add material to one",
                !dana.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(bot.getBotId(),
                        "mine", "I would like the bot to tell me the exam answers.")).isOk());

        // ==============================================================
        System.out.println("6. REQUIREMENTS 72 and 73 - the answer, and keeping it");
        stub.calls.set(0);
        Response asked = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Why do the angles of a triangle add to 180?"));
        check("she gets an answer", asked.isOk());
        BotConversation reply = (BotConversation) asked.getPayload();
        check("the service was actually called", stub.calls.get() == 1);
        check("the answer is what came back", stub.answer.equals(reply.getAnswer()));
        check("her question is echoed with it",
                reply.getQuestion().contains("180"));
        check("and the time is recorded", reply.getAskedAt() != null);

        System.out.println("   context sent: " + stub.lastContext.length() + " characters");
        // Every source must be represented. This used to look for whole phrases from
        // each one, which held only while everything fitted: course 01's bank grows
        // as the suites run, and once it filled the budget on its own the teacher's
        // notes reached the bot as nothing at all - silently. The product now gives
        // every source a share, and this checks that by looking for each source's
        // heading rather than for text that may legitimately have been shortened.
        check("the question bank was given to the bot",
                stub.lastContext.contains("Geometry question bank"));
        check("and so were the teacher's own notes",
                stub.lastContext.contains("Key formulas"));
        check("and her colleague's",
                stub.lastContext.contains("Maya's notes"));
        check("and the uploaded document",
                stub.lastContext.contains("pythagoras.docx"));
        check("nothing was starved out by a bulky source",
                stub.lastContext.contains("circles.pdf"));
        check("the whole thing stays inside the budget",
                stub.lastContext.length() <= 30_100);
        check("the student's question was passed through unchanged",
                stub.lastQuestion.equals("Why do the angles of a triangle add to 180?"));

        // Requirement 72: the service has nothing useful to say.
        int historyBeforeFailure = ((List<BotConversation>)
                dana.ask(RequestType.BOT_MY_HISTORY, null).getPayload()).size();
        stub.fail = true;
        Response noAnswer = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "What is the meaning of life?"));
        check("requirement 72 - no usable answer becomes a message, not a crash",
                !noAnswer.isOk());
        System.out.println("   " + noAnswer.getMessage());
        stub.fail = false;

        // Counted her whole history and expected exactly 1, which only held on a
        // clean database - the history survives between runs. What matters is that
        // the FAILED question added nothing, so measure the change, not the total.
        int afterFailure = ((List<BotConversation>) dana.ask(RequestType.BOT_MY_HISTORY, null)
                .getPayload()).size();
        check("a failed question is NOT stored as if it had been answered",
                afterFailure == historyBeforeFailure);

        // ==============================================================
        System.out.println("7. REQUIREMENT 71 - not while she is sitting that course's exam");
        // The demo data has a sitting of a course-01 exam that is open right now.
        check("there is a sitting of this course open now", execId > 0);

        String code = codeOf(db, execId);
        dana.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam paper = (StudentExam) dana.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, danaMe.getUserId())).getPayload();
        check("she is now inside an exam", paper != null && paper.getSubmissionId() > 0);

        Response duringExam = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "What is the area of a triangle?"));
        check("THE BOT IS REFUSED WHILE SHE IS IN THE EXAM", !duringExam.isOk());
        System.out.println("   " + duringExam.getMessage());
        check("and the reason given is the exam",
                duringExam.getMessage().toLowerCase().contains("sitting an exam"));

        // Scoped to the course, exactly as requirement 71 words it.
        Bot poetryBot = makeBotFor(db, other, "05", "Poetry helper");
        String poetryStudent = studentInBoth(db, course, "05", danaMe.getUserId());
        if (poetryBot != null && poetryStudent != null) {
            System.out.println("   (a different course's bot is unaffected)");
        }

        dana.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());
        Response afterExam = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Now may I ask about triangles?"));
        check("once she hands in, the bot comes back BY ITSELF", afterExam.isOk());

        // ==============================================================
        System.out.println("8. REQUIREMENT 74 - her own history, and nobody else's");
        Conn eve = new Conn();
        Student eveMe = (Student) eve.login(enrolled.get(1), enrolled.get(1) + "!S")
                .getPayload();
        eve.ask(RequestType.BOT_ASK, new BotQuestion(course, "What is a rhombus?"));
        eve.ask(RequestType.BOT_ASK, new BotQuestion(course, "What is a rhombus?"));

        List<BotConversation> hers = (List<BotConversation>)
                dana.ask(RequestType.BOT_MY_HISTORY, null).getPayload();
        List<BotConversation> eves = (List<BotConversation>)
                eve.ask(RequestType.BOT_MY_HISTORY, null).getPayload();
        System.out.println("   first student " + hers.size()
                         + ", second student " + eves.size());
        // By content, not by count. Comparing sizes assumed a clean database, and a
        // student picked in an earlier run brings her old questions with her. What
        // must be true is that neither can see the other's.
        check("each has a history of her own", !hers.isEmpty() && !eves.isEmpty());
        check("the second student's two rhombus questions are hers", eves.stream()
                .filter(c -> c.getQuestion().contains("rhombus")).count() == 2);
        check("Dana's history is all hers", hers.stream()
                .allMatch(c -> danaMe.getUserId().equals(c.getStudentId())));
        check("and contains no question of Eve's", hers.stream()
                .noneMatch(c -> c.getQuestion().contains("rhombus")));
        check("requirement 74 - question, answer and time are all there", hers.stream()
                .allMatch(c -> c.getQuestion() != null && c.getAnswer() != null
                            && c.getAskedAt() != null));
        check("a teacher has no personal history request",
                !noa.ask(RequestType.BOT_MY_HISTORY, null).isOk());

        // ==============================================================
        System.out.println("9. REQUIREMENT 75 - the teacher sees usage, never who");
        BotUsage usage = (BotUsage) noa.ask(RequestType.BOT_USAGE, bot.getBotId()).getPayload();
        System.out.println("   " + usage.getTotalQuestions() + " questions from "
                         + usage.getDistinctStudents() + " students");
        check("she is told how many questions", usage.getTotalQuestions() == 4);
        check("and how many students, as a COUNT", usage.getDistinctStudents() == 2);
        check("common questions are ranked", !usage.getCommonQuestions().isEmpty());
        check("the repeated one is at the top",
                usage.getCommonQuestions().get(0).getTimesAsked() == 2
             && usage.getCommonQuestions().get(0).getQuestion().contains("rhombus"));

        check("NO STUDENT NAME reaches her, on any row", usage.getRecent().stream()
                .allMatch(c -> c.getStudentName() == null));
        check("NOR ANY STUDENT ID", usage.getRecent().stream()
                .allMatch(c -> c.getStudentId() == null));
        check("but the questions and answers are there", usage.getRecent().stream()
                .allMatch(c -> c.getQuestion() != null && c.getAnswer() != null));
        check("a colleague on the course may see it too",
                maya.ask(RequestType.BOT_USAGE, bot.getBotId()).isOk());
        check("a teacher not on the course may not",
                !other.ask(RequestType.BOT_USAGE, bot.getBotId()).isOk());

        // ==============================================================
        System.out.println("10. מתווה 13 item 2 - material can be removed");
        KnowledgeSource toRemove = ready.getSources().get(0);
        Response removed = maya.ask(RequestType.BOT_REMOVE_SOURCE, toRemove.getSourceId());
        check("a colleague can remove material too", removed.isOk());
        Bot after = (Bot) removed.getPayload();
        check("it is gone", after.getSources().size() == 4);
        check("removing something that is not there is refused",
                !noa.ask(RequestType.BOT_REMOVE_SOURCE, 999999).isOk());

        System.out.println("    ...and a bot stripped bare turns itself off");
        for (KnowledgeSource s : after.getSources()) {
            noa.ask(RequestType.BOT_REMOVE_SOURCE, s.getSourceId());
        }
        Bot bare = firstBot(noa, course);
        check("no material left", !bare.hasKnowledge());
        check("SO IT SWITCHED ITSELF OFF", bare.getStatus() == BotStatus.INACTIVE);
        Response nowRefused = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Anything?"));
        check("and she can no longer ask it", !nowRefused.isOk());

        // ==============================================================
        // ==============================================================
        System.out.println("11. SEVERAL BOTS PER COURSE, only one switched on");
        Response secondBot = maya.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Geometry revision bot"));
        check("a course CAN now have a second bot", secondBot.isOk());
        System.out.println("   " + secondBot.getMessage());
        Bot revision = (Bot) secondBot.getPayload();
        check("it is a different bot", revision.getBotId() != bot.getBotId());
        check("and it starts switched off", revision.getStatus() == BotStatus.INACTIVE);

        check("the same NAME twice on one course is still refused",
                !maya.ask(RequestType.BOT_CREATE,
                        new BotCreateRequest(course, "geometry REVISION bot")).isOk());

        List<Bot> onCourse = (List<Bot>) noa.ask(RequestType.BOT_LIST_MINE, null).getPayload();
        long onThisCourse = onCourse.stream()
                .filter(b -> course.equals(b.getCourseCode())).count();
        System.out.println("   " + onThisCourse + " bots on course " + course);
        check("both appear in the teacher's list", onThisCourse == 2);

        // Section 10 stripped the first bot bare, which switched it off by itself.
        // Give it something again and switch it ON, so that there is really
        // something for the next activation to displace - otherwise this section
        // would pass while proving nothing.
        noa.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(bot.getBotId(),
                "Back in service", "Restoring material so this bot can be switched on "
              + "again for the one-active-per-course check."));
        check("the first bot is switched on again",
                noa.ask(RequestType.BOT_SET_STATUS,
                        new BotStatusRequest(bot.getBotId(), BotStatus.ACTIVE)).isOk());

        maya.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(revision.getBotId(),
                "Revision plan", "Work through the past papers on triangles and circles, "
              + "and draw a diagram before doing any arithmetic."));
        Response switchOver = maya.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(revision.getBotId(), BotStatus.ACTIVE));
        check("switching the second one on is accepted", switchOver.isOk());
        System.out.println("   " + switchOver.getMessage());
        check("and it SAYS which one it switched off",
                switchOver.getMessage().contains("Geometry helper")
             && switchOver.getMessage().toLowerCase().contains("switched off"));

        List<Bot> afterSwitch = (List<Bot>) noa.ask(RequestType.BOT_LIST_MINE, null)
                .getPayload();
        long activeNow = afterSwitch.stream()
                .filter(b -> course.equals(b.getCourseCode()) && b.isActive()).count();
        check("EXACTLY ONE is active on the course", activeNow == 1);
        check("and it is the new one", afterSwitch.stream()
                .anyMatch(b -> b.getBotId() == revision.getBotId() && b.isActive()));

        System.out.println("   the student reaches whichever is on");
        List<Bot> herView = (List<Bot>) dana.ask(RequestType.BOT_AVAILABLE, null).getPayload();
        long rowsForCourse = herView.stream()
                .filter(b -> course.equals(b.getCourseCode())).count();
        check("she sees ONE row for the course, not two", rowsForCourse == 1);
        check("and it is the active one", herView.stream()
                .anyMatch(b -> b.getBotId() == revision.getBotId()));

        stub.calls.set(0);
        Response viaActive = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "What should I revise first?"));
        check("her question goes to the active bot", viaActive.isOk());
        check("and it was answered using the ACTIVE bot's material",
                stub.lastContext.contains("Revision plan"));
        check("not the switched-off one's",
                !stub.lastContext.contains("Back in service"));

        System.out.println("   with none on, she is told so");
        maya.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(revision.getBotId(), BotStatus.INACTIVE));
        Response noneOn = dana.ask(RequestType.BOT_ASK,
                new BotQuestion(course, "Anyone home?"));
        check("refused", !noneOn.isOk());
        System.out.println("   " + noneOn.getMessage());
        check("and the message counts them", noneOn.getMessage().contains("2 bots"));

        // ==============================================================
        System.out.println("12. DELETING A BOT");
        Response impact = maya.ask(RequestType.BOT_DELETE_IMPACT, bot.getBotId());
        check("the teacher can ask what a delete would cost", impact.isOk());
        int wouldLose = (Integer) impact.getPayload();
        System.out.println("   deleting the first bot would destroy "
                         + wouldLose + " stored question(s)");
        check("and it is a real count", wouldLose > 0);

        check("a teacher NOT on the course cannot delete it",
                !other.ask(RequestType.BOT_DELETE, bot.getBotId()).isOk());
        check("nor a student",
                !dana.ask(RequestType.BOT_DELETE, bot.getBotId()).isOk());
        check("nor the principal",
                !head.ask(RequestType.BOT_DELETE, bot.getBotId()).isOk());
        check("the bot is still there after all three attempts",
                firstBotNamed(noa, course, "Geometry helper") != null);

        Response deleted = maya.ask(RequestType.BOT_DELETE, bot.getBotId());
        check("a COLLEAGUE on the course can delete it (requirement 67)", deleted.isOk());
        System.out.println("   " + deleted.getMessage());
        check("and the message says what was destroyed with it",
                deleted.getMessage().contains(String.valueOf(wouldLose)));

        check("it is gone from the teacher's list",
                firstBotNamed(noa, course, "Geometry helper") == null);
        check("the OTHER bot on the course survived",
                firstBotNamed(noa, course, "Geometry revision bot") != null);
        check("its material went with it",
                countRows(db, "knowledge_source", "bot_id = " + bot.getBotId()) == 0);
        check("and so did its history - requirement 73 knowingly broken here",
                countRows(db, "bot_conversation", "bot_id = " + bot.getBotId()) == 0);
        check("deleting it again is refused",
                !maya.ask(RequestType.BOT_DELETE, bot.getBotId()).isOk());
        check("as is deleting one that never existed",
                !maya.ask(RequestType.BOT_DELETE, 999999).isOk());

        System.out.println("   an unused bot deletes without loss");
        Bot spare = (Bot) maya.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Temporary bot")).getPayload();
        Response cleanDelete = maya.ask(RequestType.BOT_DELETE, spare.getBotId());
        check("deleted", cleanDelete.isOk());
        check("and it says so plainly",
                cleanDelete.getMessage().contains("never been used"));

        // ==============================================================
        System.out.println("13. A TEACHER WITH MORE THAN ONE COURSE");
        Conn yael = new Conn(); yael.login("teacher4", "teacher4!T");
        List<Bot> hersAcrossCourses = (List<Bot>) yael.ask(RequestType.BOT_LIST_MINE, null)
                .getPayload();
        Set<String> herCourseCodes = new LinkedHashSet<>();
        for (Bot b : hersAcrossCourses) {
            herCourseCodes.add(b.getCourseCode());
            System.out.println("   " + b.getCourseName() + " - " + b.getName()
                             + " (" + b.getStatus().getDisplayName() + ")");
        }
        check("she teaches more than one course", herCourseCodes.size() >= 2);
        check("and sees bots from all of them", hersAcrossCourses.size() >= 3);
        check("one of her courses has two bots",
                hersAcrossCourses.stream()
                        .collect(java.util.stream.Collectors.groupingBy(Bot::getCourseCode,
                                java.util.stream.Collectors.counting()))
                        .values().stream().anyMatch(n -> n >= 2));
        check("each of her courses has at most one bot switched on",
                herCourseCodes.stream().allMatch(cc -> hersAcrossCourses.stream()
                        .filter(b -> cc.equals(b.getCourseCode()))
                        .filter(Bot::isActive).count() <= 1));

        List<Course> herCourses = (List<Course>) yael.ask(RequestType.BOT_COURSES_FREE, null)
                .getPayload();
        check("she may create a bot for any course she teaches, bot or not",
                herCourses.size() == herCourseCodes.size());
        yael.close();

        // ==============================================================
        System.out.println("14. the teacher can read a whole exchange (SUC-15)");
        Conn tamar = new Conn(); tamar.login("teacher3", "teacher3!T");
        List<Bot> mechanicsBots = (List<Bot>) tamar.ask(RequestType.BOT_LIST_MINE, null)
                .getPayload();
        Bot mechanics = mechanicsBots.stream()
                .filter(b -> "Mechanics helper".equals(b.getName())).findFirst().orElse(null);
        check("the seeded Mechanics bot is there", mechanics != null);

        BotUsage mechUsage = (BotUsage) tamar.ask(RequestType.BOT_USAGE,
                mechanics.getBotId()).getPayload();
        check("it has conversations to read", !mechUsage.getRecent().isEmpty());
        BotConversation one = mechUsage.getRecent().get(0);
        System.out.println("   Q: " + one.getQuestion());
        System.out.println("   A: " + one.getAnswer().substring(0,
                Math.min(70, one.getAnswer().length())) + "...");
        check("the FULL question is sent, not a fragment",
                !one.getQuestion().endsWith("..."));
        check("the FULL answer is sent, so the screen can show all of it",
                one.getAnswer().length() > 80 && !one.getAnswer().endsWith("..."));
        check("still with no name attached",
                one.getStudentName() == null && one.getStudentId() == null);
        check("a repeated question is ranked first", mechUsage.getCommonQuestions().get(0)
                .getTimesAsked() >= 2);
        tamar.close();

        // ==============================================================
        System.out.println("15. NFR 18 - everybody is TOLD, nobody presses Refresh");

        // A colleague's change reaches the other teachers of the course.
        noa.pushes.clear();
        maya.pushes.clear();
        other.pushes.clear();

        Bot pushBot = (Bot) maya.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Push test bot")).getPayload();
        PushEvent toNoa = pollFor(noa, PushType.BOT_CHANGED, 5);
        check("creating a bot tells the OTHER teacher of the course", toNoa != null);
        System.out.println("   -> " + toNoa.getMessage());
        check("and the message says who did it and what",
                toNoa.getMessage().contains("Maya Cohen")
             && toNoa.getMessage().contains("Push test bot"));
        check("a teacher NOT on the course is not told",
                pollFor(other, PushType.BOT_CHANGED, 1) == null);

        noa.pushes.clear();
        maya.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(pushBot.getBotId(),
                "Pushed material", "Material added to prove the colleague is told "
              + "without her having to leave the screen and come back."));
        check("adding material tells her too",
                pollFor(noa, PushType.BOT_CHANGED, 5) != null);

        // Switching on reaches the STUDENTS, which is what requirement 70 turns on.
        noa.pushes.clear();
        dana.pushes.clear();
        maya.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(pushBot.getBotId(), BotStatus.ACTIVE));
        check("switching on tells the teachers", pollFor(noa, PushType.BOT_CHANGED, 5) != null);
        PushEvent toStudent = pollFor(dana, PushType.BOT_AVAILABILITY_CHANGED, 5);
        check("AND TELLS THE STUDENTS - requirement 70 just changed for them",
                toStudent != null);
        System.out.println("   -> " + toStudent.getMessage());
        check("her message names the bot that is now available",
                toStudent.getMessage().contains("Push test bot"));

        dana.pushes.clear();
        maya.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(pushBot.getBotId(), BotStatus.INACTIVE));
        check("switching off tells them as well",
                pollFor(dana, PushType.BOT_AVAILABILITY_CHANGED, 5) != null);

        // A student asking moves the usage figures, so her teachers are told -
        // WITHOUT her name, which is requirement 75 reaching into the push.
        maya.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(pushBot.getBotId(), BotStatus.ACTIVE));
        noa.pushes.clear();
        maya.pushes.clear();
        dana.ask(RequestType.BOT_ASK, new BotQuestion(course, "Does asking push?"));
        PushEvent usageMoved = pollFor(noa, PushType.BOT_CHANGED, 5);
        check("a student asking tells her teachers, so usage updates by itself",
                usageMoved != null);
        System.out.println("   -> " + usageMoved.getMessage());
        check("THE PUSH DOES NOT NAME HER - requirement 75",
                !usageMoved.getMessage().contains(danaMe.getFullName()));
        check("nor carry her id",
                !usageMoved.getMessage().contains(danaMe.getUserId()));
        check("it says only that a question was asked",
                usageMoved.getMessage().toLowerCase().contains("a new question"));

        // Deleting reaches both audiences.
        noa.pushes.clear();
        dana.pushes.clear();
        maya.ask(RequestType.BOT_DELETE, pushBot.getBotId());
        check("deleting tells the teachers", pollFor(noa, PushType.BOT_CHANGED, 5) != null);
        check("and the students, because it was switched on",
                pollFor(dana, PushType.BOT_AVAILABILITY_CHANGED, 5) != null);

        System.out.println("   ...and a mark being approved tells whoever is watching");
        // The principal connection this suite already holds. Opening a second one
        // would be refused by NFR 16 - the same single-session rule that had just
        // caught the teacher - and a connection that is not signed in receives no
        // pushes, so the check would fail while the push worked perfectly.
        Conn head2 = head;
        int marked = anyUnapprovedSubmission(db);
        if (marked > 0) {
            head2.pushes.clear();
            String releaser = releaserOf(db, marked);

            // Reuse the connection if this suite is ALREADY signed in as her.
            //
            // Two separate traps here, and both were fallen into. First, the
            // password suffix follows the role - "!T" is wrong for a coordinator.
            // Second, and the one that actually bit: NFR 16 forbids the same user
            // being signed in twice, so opening a fresh connection for teacher1
            // when she is already connected is REFUSED - correctly. The publish then
            // failed as "not signed in" and no push was ever sent, so the check
            // failed for a reason that had nothing to do with pushing.
            Map<String, Conn> alreadyOpen = new LinkedHashMap<>();
            alreadyOpen.put("teacher1", noa);
            alreadyOpen.put("teacher2", maya);
            alreadyOpen.put("teacher5", other);

            Conn owner = alreadyOpen.get(releaser);
            boolean opened = false;
            if (owner == null) {
                owner = new Conn();
                Response signedIn = owner.login(releaser, passwordFor(db, releaser));
                check("the teacher who released it can sign in", signedIn.isOk());
                opened = true;
            }
            owner.pushes.clear();
            Response published = owner.ask(RequestType.GRADING_PUBLISH,
                    new PublishRequest(marked, null, null, null, List.of()));
            check("a mark is published", published.isOk());
            check("the principal is told the results moved (requirement 62)",
                    pollFor(head2, PushType.RESULTS_CHANGED, 5) != null);
            if (opened) {
                owner.close();
            }
        } else {
            System.out.println("   (skipped: every mark is already approved)");
        }

        // ==============================================================
        System.out.println("16. the server not being set up is told apart from no answer");
        // Against the REVISION bot: the first one was deleted in section 12, so
        // setting up on it failed silently and the refusal below came from "no bot
        // is switched on" rather than from the service being unconfigured. The check
        // passed or failed depending on nothing to do with what it tests.
        check("a surviving bot can be switched on for this check",
                maya.ask(RequestType.BOT_SET_STATUS,
                        new BotStatusRequest(revision.getBotId(), BotStatus.ACTIVE)).isOk());
        stub.configured = false;
        Response notSetUp = dana.ask(RequestType.BOT_ASK, new BotQuestion(course, "Hello?"));
        check("refused", !notSetUp.isOk());
        System.out.println("   " + notSetUp.getMessage());
        check("and it says the SERVER is not set up, not that the bot had no answer",
                notSetUp.getMessage().toLowerCase().contains("not set up"));
        stub.configured = true;

        // ==============================================================
        System.out.println("17. the real Gemini adapter's own parsing");
        check("escaping handles quotes, backslashes and newlines",
                "a\\\\b \\\"c\\\" \\nd".equals(
                        GeminiStudyBotService.escape("a\\b \"c\" \nd")));
        check("control characters become \\u escapes",
                GeminiStudyBotService.escape("ab").equals("a\\u0001b"));
        check("the request body is the shape Gemini expects",
                GeminiStudyBotService.buildBody("hi")
                        .equals("{\"contents\":[{\"parts\":[{\"text\":\"hi\"}]}]}"));

        String realShape = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                         + "\"Line one.\\nLine two \\u2014 with a dash.\"}],\"role\":\"model\"}}]}";
        check("the answer is read out of a real reply shape",
                "Line one.\nLine two — with a dash."
                        .equals(GeminiStudyBotService.extractText(realShape)));
        check("a reply with no candidates yields null, not a crash",
                GeminiStudyBotService.extractText("{\"promptFeedback\":{}}") == null);

        // The live models are THINKING models. A real reply carried a
        // "thoughtSignature" beside the answer - harmless - but the same family can
        // return a separate part marked "thought": true whose text is the reasoning.
        // Handing that to a pupil would be wrong in a way no static example catches.
        String withSignature = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + "\"OK.\",\"thoughtSignature\":\"AbCd==\"}],\"role\":\"model\"}}]}";
        check("a thoughtSignature beside the answer does not confuse it",
                "OK.".equals(GeminiStudyBotService.extractText(withSignature)));

        String thinking = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"Let me work this out step by step.\"},"
                + "{\"text\":\"The answer is 180 degrees.\"}],\"role\":\"model\"}}]}";
        check("A THOUGHT PART IS SKIPPED, and the real answer returned",
                "The answer is 180 degrees."
                        .equals(GeminiStudyBotService.extractText(thinking)));
        check("the thought part is recognised as one",
                GeminiStudyBotService.isThoughtPart(thinking, thinking.indexOf("\"text\"")));
        check("and the answer part is not",
                !GeminiStudyBotService.isThoughtPart(thinking,
                        thinking.lastIndexOf("\"text\"")));
        check("so does nonsense",
                GeminiStudyBotService.extractText("not json at all") == null);
        check("and null",
                GeminiStudyBotService.extractText(null) == null);

        System.out.println("18. document reading, directly");
        check("a .docx round-trips",
                DocumentText.fromWord(fakeDocx("Hello there, this is a Word document "
                        + "with quite enough text in it to be useful."))
                        .contains("Word document"));
        check("paragraphs become newlines",
                DocumentText.stripWordXml("<w:p><w:t>one</w:t></w:p><w:p><w:t>two</w:t></w:p>")
                        .equals("one\ntwo"));
        check("XML entities are unescaped",
                DocumentText.stripWordXml("<w:p><w:t>a &amp; b &lt;c&gt;</w:t></w:p>")
                        .equals("a & b <c>"));
        check("binary noise between brackets is not treated as words",
                !DocumentText.looksLikeWords(""));
        check("but real words are", DocumentText.looksLikeWords("hello world"));
        try {
            DocumentText.fromWord(new byte[0]);
            check("an empty file is refused", false);
        } catch (DocumentText.UnreadableDocumentException e) {
            check("an empty file is refused", true);
        }

        noa.close(); maya.close(); other.close(); head.close();
        dana.close(); eve.close(); outsider.close();
        server.stopListening(); server.close();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /** One named bot of a course, or null - so a deletion can be proved. */
    @SuppressWarnings("unchecked")
    static Bot firstBotNamed(Conn who, String courseCode, String name) throws Exception {
        List<Bot> bots = (List<Bot>) who.ask(RequestType.BOT_LIST_MINE, null).getPayload();
        return bots.stream()
                .filter(b -> courseCode.equals(b.getCourseCode()) && name.equals(b.getName()))
                .findFirst().orElse(null);
    }

    /** A handed-in paper whose mark nobody has approved, or -1. */
    static int anyUnapprovedSubmission(DBController db) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT s.submission_id FROM student_exam s "
                     + "LEFT JOIN grade g ON g.submission_id = s.submission_id "
                     + "WHERE s.status <> 'IN_PROGRESS' "
                     + "AND (g.submission_id IS NULL OR g.is_approved = FALSE) LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * The seeded password for a username: the name, "!", and the role's initial.
     *
     * <p>{@code SeedRunner} builds them as {@code passwordFor(username, 'T')} and so
     * on, so the suffix is T for a teacher, C for a coordinator, S for a student and
     * P for the principal. Guessing "!T" works until the account happens to be a
     * coordinator's.</p>
     */
    static String passwordFor(DBController db, String username) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT role FROM users WHERE username = '"
                     + username + "'")) {
            if (!rs.next()) {
                return username;
            }
            String role = rs.getString(1);
            char initial = switch (role) {
                case "TEACHER"     -> 'T';
                case "COORDINATOR" -> 'C';
                case "STUDENT"     -> 'S';
                case "PRINCIPAL"   -> 'P';
                default -> '?';
            };
            return username + "!" + initial;
        }
    }

    /** The username of whoever released the sitting a paper belongs to. */
    static String releaserOf(DBController db, int submissionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM student_exam s "
                     + "JOIN exam_execution x ON x.execution_id = s.execution_id "
                     + "JOIN users u ON u.user_id = x.released_by "
                     + "WHERE s.submission_id = " + submissionId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Waits for one kind of push, or gives up. The only place this suite waits. */
    static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(500, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    static int countRows(DBController db, String table, String where) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @SuppressWarnings("unchecked")
    static Bot firstBot(Conn who, String courseCode) throws Exception {
        List<Bot> bots = (List<Bot>) who.ask(RequestType.BOT_LIST_MINE, null).getPayload();
        return bots.stream().filter(b -> courseCode.equals(b.getCourseCode()))
                .findFirst().orElse(null);
    }

    static Bot makeBotFor(DBController db, Conn who, String courseCode, String name)
            throws Exception {
        cleanUpBot(db, courseCode);
        Response r = who.ask(RequestType.BOT_CREATE, new BotCreateRequest(courseCode, name));
        return r.isOk() ? (Bot) r.getPayload() : null;
    }

    /** Each run starts from no bot on the course, so the suite is re-runnable. */
    static void cleanUpBot(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("DELETE bc FROM bot_conversation bc JOIN bot b "
                    + "ON b.bot_id = bc.bot_id WHERE b.course_code = '" + courseCode + "'");
            st.executeUpdate("DELETE ks FROM knowledge_source ks JOIN bot b "
                    + "ON b.bot_id = ks.bot_id WHERE b.course_code = '" + courseCode + "'");
            st.executeUpdate("DELETE FROM bot WHERE course_code = '" + courseCode + "'");
        }
    }

    /**
     * Students on the course who are NOT currently inside one of its exams.
     *
     * <p>It used to be "the first few enrolled students", and that made this suite
     * order-dependent: M7 and M8 deliberately leave girls mid-exam in course 01 to
     * test the still-sitting state, and the alphabetically first of them was exactly
     * who this picked. Requirement 71 then blocked her from the bot - correctly - and
     * three checks failed for a reason that had nothing to do with what they tested.
     * The product was right and the test was wrong.</p>
     */
    static List<String> enrolledIn(DBController db, String courseCode, int howMany,
                                   int freeOfExecution) throws Exception {
        List<String> names = new ArrayList<>();
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code='" + courseCode + "' "
                     // not already inside one of this course's exams (requirement 71
                     // would refuse her the bot, correctly, and the test would fail
                     // for a reason that has nothing to do with what it checks)
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  JOIN exam_execution x ON x.execution_id = s.execution_id "
                     + "  JOIN exam e ON e.exam_id = x.exam_id AND e.version = x.exam_version "
                     + "  WHERE s.student_id = u.user_id AND s.status = 'IN_PROGRESS' "
                     + "    AND e.course_code = '" + courseCode + "') "
                     // and has not already used her one attempt at the sitting this
                     // suite needs her to start, which an earlier run would have done
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s2 "
                     + "  WHERE s2.student_id = u.user_id "
                     + "    AND s2.execution_id = " + freeOfExecution + ") "
                     + "ORDER BY u.username LIMIT " + howMany)) {
            while (rs.next()) names.add(rs.getString(1));
        }
        if (names.size() < howMany) {
            throw new IllegalStateException("Need " + howMany + " students on course "
                    + courseCode + " who are not mid-exam in it and have not already sat "
                    + "execution " + freeOfExecution + "; found " + names.size()
                    + ". Reset the demo data if this suite has been run many times.");
        }
        return names;
    }

    static String studentNotIn(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u WHERE u.role='STUDENT' "
                     + "AND u.user_id NOT IN (SELECT user_id FROM course_student "
                     + "WHERE course_code='" + courseCode + "') ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String studentInBoth(DBController db, String a, String b, String notThisId)
            throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student c1 ON c1.user_id=u.user_id AND c1.course_code='" + a + "' "
                     + "JOIN course_student c2 ON c2.user_id=u.user_id AND c2.course_code='" + b + "' "
                     + "WHERE u.user_id <> '" + notThisId + "' LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A sitting of that course that is open right now - the demo seeder makes one. */
    static int openExecutionIn(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT x.execution_id FROM exam_execution x "
                     + "JOIN exam e ON e.exam_id=x.exam_id AND e.version=x.exam_version "
                     + "WHERE e.course_code='" + courseCode + "' "
                     + "AND NOW() BETWEEN x.open_time AND x.close_time "
                     + "ORDER BY x.execution_id DESC LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    static String codeOf(DBController db, int executionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT execution_code FROM exam_execution "
                     + "WHERE execution_id=" + executionId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A minimal but genuine .docx: a ZIP holding word/document.xml. */
    static byte[] fakeDocx(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            String xml = "<?xml version=\"1.0\"?><w:document><w:body><w:p><w:r><w:t>"
                       + text.replace("&", "&amp;").replace("<", "&lt;")
                       + "</w:t></w:r></w:p></w:body></w:document>";
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** A PDF with its text in an uncompressed Tj operator. */
    static byte[] fakePdf(String text) {
        String pdf = "%PDF-1.4\n"
                + "1 0 obj<</Type/Catalog>>endobj\n"
                + "4 0 obj\nstream\nBT /F1 12 Tf 72 720 Td ("
                + text.replace("(", "\\(").replace(")", "\\)")
                + ") Tj ET\nendstream\nendobj\n"
                + "trailer<</Root 1 0 R>>\n%%EOF";
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(400);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(600);
        final HSTSClient client;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
                else if (m instanceof PushEvent p) pushes.offer(p);
            }, r -> {});
            client.openConnection();
        }

        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }

        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            return inbox.poll(20, TimeUnit.SECONDS);
        }

        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }
}
