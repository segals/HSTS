package hsts.server.control;

import hsts.common.entity.Answer;
import hsts.common.entity.Course;
import hsts.common.entity.Question;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.Response;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.QuestionDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SUC-2: managing the question bank.
 *
 * <p>Covers מתווה scenario 2 in full - add a question, edit it so the previous
 * version stays in the bank, browse the bank, and delete a question.</p>
 *
 * <h2>Why the checks are here and not on the screen</h2>
 *
 * <p>The screen does check things, because telling the user immediately is
 * friendlier than a round trip. But the screen's checks are a convenience, not a
 * guarantee: a client is a program on somebody else's computer and can send
 * anything at all. Every rule that actually matters is enforced here, on the
 * server, where it cannot be bypassed.</p>
 */
public class QuestionController {

    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;   // 2 MB

    private final QuestionDAO questionDAO;
    private final CourseDAO courseDAO;

    public QuestionController(QuestionDAO questionDAO, CourseDAO courseDAO) {
        this.questionDAO = questionDAO;
        this.courseDAO = courseDAO;
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    /** The live bank for one course, for a user allowed to see it. */
    public Response listByCourse(User user, String courseCode) {
        String refusal = refuseIfCannotUseCourse(user, courseCode);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            List<Question> questions = questionDAO.findCurrentByCourse(courseCode);
            String message = questions.isEmpty()
                    ? "There are no questions in this course yet."
                    : questions.size() + " question(s) in the bank.";
            return Response.ok(questions, message);
        } catch (SQLException e) {
            return Response.error("Could not read the question bank: " + e.getMessage());
        }
    }

    /** One full question including its picture. */
    public Response getQuestion(User user, String questionId, int version) {
        try {
            Question question = (version > 0)
                    ? questionDAO.findByIdAndVersion(questionId, version)
                    : questionDAO.findById(questionId);

            if (question == null) {
                return Response.error("No question with id " + questionId + ".");
            }
            String refusal = refuseIfCannotUseCourse(user, question.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(question, null);
        } catch (SQLException e) {
            return Response.error("Could not load the question: " + e.getMessage());
        }
    }

    /** Every version of a question - the proof that editing preserves history. */
    public Response listVersions(User user, String questionId) {
        try {
            List<Question> versions = questionDAO.findAllVersions(questionId);
            if (versions.isEmpty()) {
                return Response.error("No question with id " + questionId + ".");
            }
            String refusal = refuseIfCannotUseCourse(user, versions.get(0).getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(versions, versions.size() + " version(s) stored.");
        } catch (SQLException e) {
            return Response.error("Could not load the version history: " + e.getMessage());
        }
    }

    /** Topics already used in a course, to populate the combo box. */
    public Response listTopics(User user, String courseCode) {
        String refusal = refuseIfCannotUseCourse(user, courseCode);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            return Response.ok(questionDAO.findTopicsByCourse(courseCode), null);
        } catch (SQLException e) {
            return Response.error("Could not load the topics: " + e.getMessage());
        }
    }

    /**
     * The courses this user may write questions for.
     *
     * <p>For a coordinator that is the courses she teaches <b>plus every course in
     * the subject she coordinates</b> (requirement 19). Without the second half she
     * would be permitted to edit those questions and have no way to reach them:
     * the screen offers this list and nothing else.</p>
     *
     * <p>Merged by course code, because a coordinator usually also teaches one of
     * her own subject's courses and it must not appear twice.</p>
     */
    public Response listMyCourses(User user) {
        try {
            if (!(user instanceof Teacher)) {
                return Response.error("Only a teacher has courses to manage.");
            }
            Map<String, Course> byCode = new LinkedHashMap<>();
            for (Course course : courseDAO.findByTeacher(user.getUserId())) {
                byCode.put(course.getCourseCode(), course);
            }
            if (user instanceof SubjectCoordinator coordinator) {
                for (Course course : courseDAO.findBySubject(
                        coordinator.getCoordinatedSubjectCode())) {
                    byCode.putIfAbsent(course.getCourseCode(), course);
                }
            }
            return Response.ok(new ArrayList<>(byCode.values()), null);
        } catch (SQLException e) {
            return Response.error("Could not load your courses: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Writing
    // -----------------------------------------------------------------

    /** Adds a new question as version 1, with a freshly generated 5-digit id. */
    public Response addQuestion(User user, Question question) {
        if (question == null) {
            return Response.error("No question was sent.");
        }
        String refusal = refuseIfCannotEditCourse(user, question.getCourseCode());
        if (refusal != null) {
            return Response.error(refusal);
        }
        String invalid = validate(question);
        if (invalid != null) {
            return Response.error(invalid);
        }

        try {
            question.setQuestionId(questionDAO.generateNextQuestionId(question.getCourseCode()));
            question.setAuthorId(user.getUserId());
            question.setName(question.getName().trim());
            questionDAO.insert(question);
            return Response.ok(question,
                    "\"" + question.getName() + "\" added to the bank as question "
                  + question.getQuestionId() + ".");
        } catch (SQLException e) {
            return Response.error("Could not save the question: " + e.getMessage());
        }
    }

    /**
     * Saves an edit as a new version.
     *
     * <p>מתווה scenario 2 item 2: "השאלה בגרסה הקודמת נשארת במאגר השאלות" - the
     * previous version stays in the bank. Nothing is overwritten.</p>
     */
    public Response editQuestion(User user, Question edited) {
        if (edited == null || edited.getQuestionId() == null) {
            return Response.error("No question was sent.");
        }

        try {
            Question existing = questionDAO.findById(edited.getQuestionId());
            if (existing == null) {
                return Response.error("No question with id " + edited.getQuestionId() + ".");
            }
            String refusal = refuseIfCannotEditCourse(user, existing.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }

            // The course of an existing question never changes: the course code is
            // baked into its 5-digit id, so moving it would make the id a lie.
            //
            // This has to happen BEFORE validation, not after. Validation requires a
            // course, and on an edit the course is not the client's to supply - it
            // comes from the stored question. Validating first rejected every edit
            // with "The question must belong to a course", even though the server
            // was about to fill that field in itself.
            edited.setCourseCode(existing.getCourseCode());
            edited.setName(edited.getName() == null ? null : edited.getName().trim());
            edited.setAuthorId(user.getUserId());

            String invalid = validate(edited);
            if (invalid != null) {
                return Response.error(invalid);
            }

            int newVersion = questionDAO.createNewVersion(edited);
            return Response.ok(edited,
                    "Saved as version " + newVersion + ". Version " + (newVersion - 1)
                  + " is still in the bank.");
        } catch (SQLException e) {
            return Response.error("Could not save the edit: " + e.getMessage());
        }
    }

    /** Removes a question from the bank, keeping it for exams that already use it. */
    public Response deleteQuestion(User user, String questionId) {
        try {
            Question existing = questionDAO.findById(questionId);
            if (existing == null) {
                return Response.error("No question with id " + questionId + ".");
            }
            String refusal = refuseIfCannotEditCourse(user, existing.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }
            questionDAO.delete(questionId);
            return Response.ok(questionId,
                    "Question " + questionId + " removed from the bank. Exams that already "
                  + "use it are unaffected.");
        } catch (SQLException e) {
            return Response.error("Could not delete the question: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------

    /**
     * Checks one question, returning the first problem in words the user can act
     * on, or null when it is fine.
     *
     * <p>The four-answers-one-correct rule comes straight from system description
     * §3.1 and cannot be expressed as a database constraint, so it lives here.</p>
     */
    /** Long enough to say what a question is about, short enough for a list row. */
    private static final int MAX_NAME_LENGTH = 120;

    private String validate(Question q) {
        // Compulsory, alongside the exam name and for the same reason: a list of
        // forty questions showing their full text is a wall, and one showing "00101"
        // says nothing. A name that MAY be blank is one most questions will not have.
        if (isBlank(q.getName())) {
            return "Give the question a short name - something like "
                 + "\"Triangle angle sum\" - so it can be found in a list.";
        }
        if (q.getName().trim().length() > MAX_NAME_LENGTH) {
            return "That name is too long. Keep it under " + MAX_NAME_LENGTH
                 + " characters so it fits on a list.";
        }
        if (isBlank(q.getText())) {
            return "The question text cannot be empty.";
        }
        if (isBlank(q.getTopic())) {
            return "Choose or type a topic. Automatic exam building selects questions by topic.";
        }
        if (q.getDifficulty() == null) {
            return "Choose a difficulty level.";
        }
        if (isBlank(q.getCourseCode())) {
            return "The question must belong to a course.";
        }

        List<Answer> answers = q.getAnswers();
        if (answers == null || answers.size() != 4) {
            return "A question must have exactly four answers (this one has "
                 + (answers == null ? 0 : answers.size()) + ").";
        }

        int correct = 0;
        for (Answer a : answers) {
            if (isBlank(a.getText())) {
                // Deliberately generic. Naming the offending answer number adds
                // nothing the user cannot already see on the screen.
                return "All four answers must be filled in.";
            }
            if (a.isCorrect()) {
                correct++;
            }
        }
        if (correct == 0) {
            return "Mark one answer as the correct one.";
        }
        if (correct > 1) {
            return "Only one answer may be marked correct (" + correct + " are marked).";
        }

        if (q.hasImage() && q.getImage().length > MAX_IMAGE_BYTES) {
            return "The picture is too large ("
                 + (q.getImage().length / 1024) + " KB). The limit is "
                 + (MAX_IMAGE_BYTES / 1024) + " KB.";
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Permission
    // -----------------------------------------------------------------

    /**
     * May this user write questions for this course?
     *
     * <p>Two requirements, and the coordinator's is the wider one.</p>
     *
     * <p><b>Requirement 14</b>: a teacher may create and change questions only for
     * the courses she teaches.</p>
     *
     * <p><b>Requirement 19</b>: <i>"רכזת המקצוע תוכל לערוך שאלות של אותו המקצוע
     * שמרכזת"</i> - a subject coordinator may edit the questions of the subject she
     * coordinates. That is <em>every course in the subject</em>, not only the ones
     * she happens to teach, which is the whole point of the requirement: Noa Katz
     * coordinates Mathematics and so may correct a question in Plane Geometry even
     * though she teaches Algebra.</p>
     *
     * <p>Checked coordinator-first, because {@code SubjectCoordinator} extends
     * {@code Teacher} - testing the narrower role first would refuse her before the
     * wider rule was ever reached. That mistake has already been made once on this
     * project, on the exam-viewing path, and is recorded in the change log.</p>
     */
    private String refuseIfCannotEditCourse(User user, String courseCode) {
        if (!(user instanceof Teacher teacher)) {
            return "Only a teacher can change the question bank.";
        }
        if (teacher.teaches(courseCode)) {
            return null;
        }
        if (user instanceof SubjectCoordinator coordinator
                && coordinatesSubjectOf(coordinator, courseCode)) {
            return null;                       // requirement 19
        }
        return user instanceof SubjectCoordinator
                ? "That course is not in your subject, so you cannot change its questions."
                : "You do not teach that course, so you cannot change its questions.";
    }

    /** May this user look at this course's questions? */
    private String refuseIfCannotUseCourse(User user, String courseCode) {
        if (isBlank(courseCode)) {
            return "No course was chosen.";
        }
        if (user instanceof Teacher teacher) {
            if (teacher.teaches(courseCode)) {
                return null;
            }
            // Requirement 19 again: she cannot sensibly be allowed to edit a
            // question she is not allowed to read.
            if (user instanceof SubjectCoordinator coordinator
                    && coordinatesSubjectOf(coordinator, courseCode)) {
                return null;
            }
            return user instanceof SubjectCoordinator
                    ? "That course is not in your subject."
                    : "You do not teach that course.";
        }
        // The principal reads the whole bank through her own screen (requirement 62),
        // not through this controller.
        return "You are not allowed to view this question bank.";
    }

    /** True when the course belongs to the subject this coordinator runs. */
    private boolean coordinatesSubjectOf(SubjectCoordinator coordinator, String courseCode) {
        try {
            Course course = courseDAO.findById(courseCode);
            return course != null
                && course.getSubjectCode() != null
                && course.getSubjectCode().equals(coordinator.getCoordinatedSubjectCode());
        } catch (SQLException e) {
            // Refuse rather than guess: a lookup that failed is not permission.
            System.err.println("Could not check the subject of " + courseCode
                             + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
