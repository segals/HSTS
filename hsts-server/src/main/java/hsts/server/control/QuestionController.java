package hsts.server.control;

import hsts.common.entity.Answer;
import hsts.common.entity.Question;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.Response;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.QuestionDAO;

import java.sql.SQLException;
import java.util.List;

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

    /** The courses this user may write questions for. */
    public Response listMyCourses(User user) {
        try {
            if (user instanceof Teacher) {
                return Response.ok(courseDAO.findByTeacher(user.getUserId()), null);
            }
            return Response.error("Only a teacher has courses to manage.");
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
            questionDAO.insert(question);
            return Response.ok(question,
                    "Question " + question.getQuestionId() + " added to the bank.");
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
    private String validate(Question q) {
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
                return "Answer " + a.getAnswerNo() + " is empty.";
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
     * <p>Requirement 14: a teacher may create questions only for the courses she
     * teaches. Requirement 19 additionally lets a subject coordinator edit
     * questions belonging to the subject she coordinates - that part is scheduled
     * for milestone 15 with the other derived requirements, so for now a
     * coordinator is treated exactly like the teacher she also is.</p>
     */
    private String refuseIfCannotEditCourse(User user, String courseCode) {
        if (!(user instanceof Teacher teacher)) {
            return "Only a teacher can change the question bank.";
        }
        if (!teacher.teaches(courseCode)) {
            return "You do not teach that course, so you cannot change its questions.";
        }
        return null;
    }

    /** May this user look at this course's questions? */
    private String refuseIfCannotUseCourse(User user, String courseCode) {
        if (isBlank(courseCode)) {
            return "No course was chosen.";
        }
        if (user instanceof SubjectCoordinator || user instanceof Teacher) {
            Teacher teacher = (Teacher) user;
            if (teacher.teaches(courseCode)) {
                return null;
            }
            return "You do not teach that course.";
        }
        // The principal's read-only access to the whole bank (requirement 62)
        // arrives with milestone 12 and its own screen.
        return "You are not allowed to view this question bank.";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
