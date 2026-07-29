package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.Grade;
import hsts.common.entity.StudentExam;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.CommentRequest;
import hsts.common.protocol.MarkedExam;
import hsts.common.protocol.PublishRequest;
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
            // Everybody who started, not only those who handed in. A student still
            // sitting has no mark yet, but she is counted in "how many sat it" and
            // the teacher needs to see why the paper is not there to be marked.
            List<Grade> grades = gradeDAO.findByExecutionIncludingUnmarked(executionId);

            long stillSitting = grades.stream().filter(g -> !g.isMarked()).count();
            long ready = grades.size() - stillSitting;

            String note;
            if (grades.isEmpty()) {
                note = "Nobody has started this sitting yet.";
            } else if (stillSitting == 0) {
                note = ready + " paper(s) to mark.";
            } else if (ready == 0) {
                note = stillSitting + " student(s) still sitting. Nothing to mark yet.";
            } else {
                note = ready + " paper(s) to mark, " + stillSitting + " still sitting.";
            }
            return Response.ok(grades, note);
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
            notifyResultsChanged(attempt.getExecutionId(),
                    "A mark was changed by hand on exam " + grade.getExamId() + ".");
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

            notifyResultsChanged(attempt.getExecutionId(),
                    "A mark was approved for exam " + grade.getExamId() + ".");
            return Response.ok(grade, "Approved. " + grade.getStudentName()
                    + " can now see her mark and her marked paper.");
        } catch (SQLException e) {
            return Response.error("Could not approve: " + e.getMessage());
        }
    }

    /**
     * Saves everything the teacher settled on for one paper, then publishes it.
     *
     * <p>This is what the marking screen's single <b>Approve and publish</b> button
     * sends. It replaces four separate presses - save the mark, save the overall
     * comment, save each question's comment, approve - which between them let a
     * teacher publish a mark while a comment she had typed was still unsaved.</p>
     *
     * <p><b>Nothing is written until everything has been checked.</b> Requirement 52
     * and acceptance test 3.4 make an explanation compulsory when a mark is moved by
     * hand; if it is missing, the whole request is refused and the paper is left
     * exactly as it was - not published, and with her typing still on screen to
     * correct. Acceptance test 3.6 rejects a mark outside 0-100 the same way.</p>
     *
     * <p>Pressing it again on a paper that is already published is how acceptance
     * test 3.12 works: the new mark is saved and the student is told again.</p>
     */
    public Response publish(User user, PublishRequest request) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher marks exams.");
        }
        if (request == null) {
            return Response.error("Nothing to publish.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(request.getSubmissionId());
            if (attempt == null) {
                return Response.error("That paper does not exist.");
            }
            String refusal = refuseIfNotHers(user, attempt.getExecutionId());
            if (refusal != null) {
                return Response.error(refusal);
            }
            if (attempt.isInProgress()) {
                // Acceptance test 3.11.
                return Response.error(attempt.getStudentName()
                        + " is still sitting this exam. It cannot be marked yet.");
            }

            // Makes sure a mark exists to compare against, and is safe to repeat.
            gradeDAO.autoGrade(request.getSubmissionId());
            Grade before = gradeDAO.findBySubmission(request.getSubmissionId());

            Integer wanted = request.getFinalGrade();
            boolean markMoved = wanted != null && wanted != before.getFinalGrade();

            // ---- check everything first, write nothing yet ----
            if (markMoved) {
                if (wanted < 0 || wanted > 100) {
                    return Response.error("The mark must be a whole number between 0 and 100.");
                }
                if (request.getReason() == null || request.getReason().trim().isEmpty()) {
                    return Response.error("You changed the mark from " + before.getFinalGrade()
                            + " to " + wanted + ", so a reason is needed. It is kept with the "
                            + "mark so the change can be accounted for. Nothing has been "
                            + "published.");
                }
            }

            // ---- now write ----
            if (markMoved) {
                gradeDAO.changeFinalGrade(request.getSubmissionId(), wanted,
                        request.getReason().trim(), user.getUserId());
            }

            String general = request.getGeneralComment();
            gradeDAO.setGeneralComment(request.getSubmissionId(),
                    (general == null || general.isBlank()) ? null : general.trim());

            for (CommentRequest c : request.getQuestionComments()) {
                gradeDAO.setQuestionComment(request.getSubmissionId(), c.getQuestionId(),
                        c.getQuestionVersion(),
                        (c.getComment() == null || c.getComment().isBlank())
                                ? null : c.getComment().trim());
            }

            boolean wasAlreadyOut = before.isApproved();
            gradeDAO.approve(request.getSubmissionId(), user.getUserId());

            Grade grade = gradeDAO.findBySubmission(request.getSubmissionId());
            notifyStudent(grade, wasAlreadyOut
                    ? "Your mark for exam " + grade.getExamId()
                      + " has been updated to " + grade.getFinalGrade() + "."
                    : "Your mark for exam " + grade.getExamId()
                      + " is ready: " + grade.getFinalGrade() + ".");

            notifyResultsChanged(attempt.getExecutionId(),
                    "A mark was published for exam " + grade.getExamId() + ".");
            return Response.ok(grade, (wasAlreadyOut ? "Updated and published. " : "Published. ")
                    + grade.getStudentName() + " can now see "
                    + (markMoved ? "the mark of " + grade.getFinalGrade() : "her mark")
                    + " and her marked paper.");

        } catch (SQLException e) {
            return Response.error("Could not publish: " + e.getMessage());
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
            if (published > 0) {
                notifyResultsChanged(executionId, published
                        + " mark(s) were approved at once.");
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
            notifyResultsChanged(executionId, "A factor of "
                    + (delta > 0 ? "+" : "") + delta + " was applied.");
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

    /**
     * Tells everybody whose figures just changed - NFR 18, the other direction.
     *
     * <p>{@link #notifyStudent} tells the girl her mark is ready. This tells the
     * people looking at the <em>statistics</em>: the exam's author (requirement 59
     * gives her the results of exams she wrote, even when somebody else ran them),
     * the teacher who released the sitting, and the principal (requirement 62).</p>
     *
     * <p>Without it a teacher with the histogram open would watch a colleague
     * approve marks and see nothing move.</p>
     */
    private void notifyResultsChanged(int executionId, String what) {
        try {
            ExamExecution execution = executionDAO.findById(executionId);
            if (execution == null) {
                return;
            }
            java.util.Set<String> tell = new java.util.LinkedHashSet<>();
            for (Exam version : examDAO.findAllVersions(execution.getExamId())) {
                User author = userDAO.findById(version.getAuthorId());
                if (author != null) {
                    tell.add(author.getUsername());
                }
            }
            User releaser = userDAO.findById(execution.getReleasedBy());
            if (releaser != null) {
                tell.add(releaser.getUsername());
            }
            tell.addAll(userDAO.findUsernamesWithRole(hsts.common.enums.UserRole.PRINCIPAL));

            pushService.toUsernames(tell, new PushEvent(PushType.RESULTS_CHANGED,
                    execution.getExamId(), what));
        } catch (SQLException e) {
            System.err.println("Could not announce the results change: " + e.getMessage());
        }
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
