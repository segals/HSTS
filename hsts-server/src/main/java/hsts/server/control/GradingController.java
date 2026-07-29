package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.Grade;
import hsts.common.entity.StudentExam;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.MarkedExam;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.SubmissionDAO;
import hsts.server.dao.UserDAO;
import hsts.server.push.PushService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-9 / מתווה scenario 8: marking exams and publishing the results.
 *
 * <p>The system marks automatically (requirement 49). The teacher reviews, may
 * change a mark by hand with a compulsory explanation (requirement 52), may add
 * comments (requirement 51), and approves - and only then does the student see
 * anything (requirement 53).</p>
 *
 * <p><b>Who may mark:</b> the teacher who <em>released</em> the sitting. That was
 * settled during planning - they are her students. The exam's author gets
 * read-only statistics through her own reports instead.</p>
 */
public class GradingController {

    private final GradeDAO gradeDAO;
    private final SubmissionDAO submissionDAO;
    private final ExecutionDAO executionDAO;
    private final ExamDAO examDAO;
    private final UserDAO userDAO;
    private final PushService pushService;

    public GradingController(GradeDAO gradeDAO, SubmissionDAO submissionDAO,
                             ExecutionDAO executionDAO, ExamDAO examDAO,
                             UserDAO userDAO, PushService pushService) {
        this.gradeDAO = gradeDAO;
        this.submissionDAO = submissionDAO;
        this.executionDAO = executionDAO;
        this.examDAO = examDAO;
        this.userDAO = userDAO;
        this.pushService = pushService;
    }

    // -----------------------------------------------------------------
    //  Finding work to do
    // -----------------------------------------------------------------

    /** Sittings this teacher released that have papers in them (requirement 58). */
    public Response listSittingsToMark(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        try {
            List<ExamExecution> withWork = new ArrayList<>();
            for (ExamExecution execution : executionDAO.findByTeacher(user.getUserId())) {
                if (execution.getNumStarted() > 0) {
                    withWork.add(execution);
                }
            }
            return Response.ok(withWork, withWork.isEmpty()
                    ? "None of your exams has been sat yet."
                    : withWork.size() + " sitting(s) with papers.");
        } catch (SQLException e) {
            return Response.error("Could not load your sittings: " + e.getMessage());
        }
    }

    /** Every mark in one sitting. */
    public Response listGrades(User user, Integer executionId) {
        String refusal = refuseIfNotHers(user, executionId);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            // Mark anything that has been handed in but never marked. Doing it
            // here rather than only on submit means a paper closed by the clock
            // during a server restart still gets marked.
            for (StudentExam attempt : submissionDAO.findByExecution(executionId)) {
                if (!attempt.isInProgress()) {
                    gradeDAO.autoGrade(attempt.getSubmissionId());
                }
            }
            List<Grade> grades = gradeDAO.findByExecution(executionId);
            return Response.ok(grades, grades.isEmpty()
                    ? "Nobody has handed in yet."
                    : grades.size() + " paper(s).");
        } catch (SQLException e) {
            return Response.error("Could not load the marks: " + e.getMessage());
        }
    }

    /** One paper with the questions, her answers, the right answers and the marking. */
    public Response getMarkedExam(User user, Integer submissionId) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }
            if (attempt.isInProgress()) {
                // Acceptance test 3.11: nothing to mark until she has handed in.
                return Response.error(attempt.getStudentName()
                        + " is still sitting this exam. It cannot be marked yet.");
            }

            gradeDAO.autoGrade(submissionId);
            return Response.ok(loadMarkedExam(attempt), null);

        } catch (SQLException e) {
            return Response.error("Could not load that paper: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Changing a mark
    // -----------------------------------------------------------------

    /**
     * Changes a mark by hand.
     *
     * <p>Requirement 52 and acceptance test 3.4: an explanation is compulsory.
     * Acceptance test 3.6: the mark must be a number between 0 and 100.</p>
     */
    public Response changeGrade(User user, Integer submissionId, Integer newGrade,
                                String explanation) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        if (newGrade == null) {
            return Response.error("Enter a mark.");
        }
        if (newGrade < 0 || newGrade > 100) {
            return Response.error("The mark must be a whole number between 0 and 100.");
        }
        if (explanation == null || explanation.trim().isEmpty()) {
            return Response.error("Changing a mark by hand needs an explanation. "
                                + "It is kept with the mark so the change can be accounted for.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }

            gradeDAO.autoGrade(submissionId);
            gradeDAO.changeFinalGrade(submissionId, newGrade, explanation.trim(),
                                      user.getUserId());

            Grade grade = gradeDAO.findBySubmission(submissionId);
            // Acceptance test 3.12: a mark may be changed after approval too. If it
            // was already published, tell her again so she sees the new one.
            if (grade.isApproved()) {
                notifyStudent(grade, "Your mark for exam " + grade.getExamId()
                        + " has been updated to " + grade.getFinalGrade() + ".");
            }
            return Response.ok(grade, "Mark changed to " + newGrade
                    + ". The explanation was saved with it.");
        } catch (SQLException e) {
            return Response.error("Could not change the mark: " + e.getMessage());
        }
    }

    /** A note against one question (requirement 51). */
    public Response addQuestionComment(User user, Integer submissionId, String questionId,
                                       Integer questionVersion, String comment) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }
            gradeDAO.setQuestionComment(submissionId, questionId, questionVersion,
                    (comment == null || comment.isBlank()) ? null : comment.trim());
            return Response.ok(submissionId, "Comment saved.");
        } catch (SQLException e) {
            return Response.error("Could not save the comment: " + e.getMessage());
        }
    }

    /** A note about the paper as a whole. */
    public Response addGeneralComment(User user, Integer submissionId, String comment) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }
            gradeDAO.autoGrade(submissionId);
            gradeDAO.setGeneralComment(submissionId,
                    (comment == null || comment.isBlank()) ? null : comment.trim());
            return Response.ok(submissionId, "Comment saved.");
        } catch (SQLException e) {
            return Response.error("Could not save the comment: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Publishing
    // -----------------------------------------------------------------

    /** Publishes one mark. Requirement 53: only now does the student see it. */
    public Response approve(User user, Integer submissionId) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher approves marks.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }
            if (attempt.isInProgress()) {
                return Response.error(attempt.getStudentName()
                        + " is still sitting this exam.");
            }

            gradeDAO.autoGrade(submissionId);
            gradeDAO.approve(submissionId, user.getUserId());

            Grade grade = gradeDAO.findBySubmission(submissionId);
            notifyStudent(grade, "Your mark for exam " + grade.getExamId()
                    + " is ready: " + grade.getFinalGrade() + ".");

            return Response.ok(grade, "Approved. " + grade.getStudentName()
                    + " can now see her mark and her marked paper.");
        } catch (SQLException e) {
            return Response.error("Could not approve: " + e.getMessage());
        }
    }

    /** Publishes every unapproved mark in a sitting (acceptance test 3.10). */
    public Response approveAll(User user, Integer executionId) {
        String refusal = refuseIfNotHers(user, executionId);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            for (StudentExam attempt : submissionDAO.findByExecution(executionId)) {
                if (!attempt.isInProgress()) {
                    gradeDAO.autoGrade(attempt.getSubmissionId());
                }
            }
            int published = gradeDAO.approveAll(executionId, user.getUserId());

            for (Grade grade : gradeDAO.findByExecution(executionId)) {
                if (grade.isApproved()) {
                    notifyStudent(grade, "Your mark for exam " + grade.getExamId()
                            + " is ready: " + grade.getFinalGrade() + ".");
                }
            }
            return Response.ok(published, published == 0
                    ? "Every mark in this sitting was already approved."
                    : published + " mark(s) approved and published.");
        } catch (SQLException e) {
            return Response.error("Could not approve them: " + e.getMessage());
        }
    }

    /**
     * Adds a factor to every mark in a sitting (requirement 77).
     *
     * <p>Additive and clamped to 0-100, as agreed during planning.</p>
     */
    public Response applyFactor(User user, Integer executionId, Integer delta) {
        String refusal = refuseIfNotHers(user, executionId);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (delta == null || delta == 0) {
            return Response.error("Say how many points to add or take away.");
        }
        if (Math.abs(delta) > 100) {
            return Response.error("A factor of more than 100 points makes no sense.");
        }
        try {
            int changed = gradeDAO.applyFactor(executionId, delta, user.getUserId());
            return Response.ok(changed, (delta > 0 ? "Added " : "Took away ")
                    + Math.abs(delta) + " points for " + changed + " student(s). "
                    + "Marks stay between 0 and 100.");
        } catch (SQLException e) {
            return Response.error("Could not apply the factor: " + e.getMessage());
        }
    }

    /** Average, median and deciles - never sent to a student (requirement 55). */
    public Response getStatistics(User user, Integer executionId) {
        String refusal = refuseIfNotHers(user, executionId);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            return Response.ok(gradeDAO.computeStatistics(executionId), null);
        } catch (SQLException e) {
            return Response.error("Could not work out the statistics: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /** Loads the paper with the right answers shown - this is for a marker. */
    MarkedExam loadMarkedExam(StudentExam attempt) throws SQLException {
        ExamExecution execution = executionDAO.findById(attempt.getExecutionId());
        Exam exam = examDAO.findByIdAndVersion(execution.getExamId(), execution.getExamVersion());

        attempt.setExamId(exam.getExamId());
        attempt.setCourseName(exam.getCourseName());
        attempt.setInstructionsForStudents(exam.getInstructionsForStudents());
        attempt.setQuestions(exam.getQuestions());
        attempt.setAnswers(submissionDAO.findAnswers(attempt.getSubmissionId()));

        Grade grade = gradeDAO.findBySubmission(attempt.getSubmissionId());
        if (grade != null) {
            grade.setFeedback(gradeDAO.findFeedback(attempt.getSubmissionId()));
        }
        return new MarkedExam(attempt, grade);
    }

    private void notifyStudent(Grade grade, String message) {
        try {
            User student = userDAO.findById(grade.getStudentId());
            if (student != null) {
                pushService.toUsername(student.getUsername(),
                        new PushEvent(PushType.GRADE_APPROVED, grade.getSubmissionId(), message));
            }
        } catch (SQLException e) {
            System.err.println("Could not tell the student about her mark: " + e.getMessage());
        }
    }

    /** Decision 5: the teacher who released a sitting is the one who marks it. */
    private String refuseIfNotHers(User user, Integer executionId) {
        if (!(user instanceof Teacher)) {
            return "Only a teacher marks exams.";
        }
        if (executionId == null) {
            return "No sitting was chosen.";
        }
        try {
            ExamExecution execution = executionDAO.findById(executionId);
            if (execution == null) {
                return "That sitting does not exist.";
            }
            if (!execution.getReleasedBy().equals(user.getUserId())) {
                return "You did not release that sitting, so it is not yours to mark.";
            }
            return null;
        } catch (SQLException e) {
            return "Could not check that sitting: " + e.getMessage();
        }
    }
}
