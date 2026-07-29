package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.Grade;
import hsts.common.entity.Student;
import hsts.common.entity.StudentExam;
import hsts.common.entity.User;
import hsts.common.protocol.MarkedExam;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.SubmissionDAO;

import java.sql.SQLException;
import java.util.List;

/**
 * SUC-10 / מתווה scenario 9: a student looking at her own results.
 *
 * <p>Requirement 56 - she sees her marks and a copy of the marked paper.
 * Requirement 57 - only her own, never anybody else's. Requirement 55 - never the
 * class statistics.</p>
 *
 * <h2>How "only her own" is actually enforced</h2>
 *
 * <p>Not by hiding buttons. Every method here takes the signed-in user and filters
 * on <em>her</em> id, and the query that lists results is filtered on her id in
 * SQL. Asking for another student's paper by its number is refused, which is what
 * the rewritten acceptance test 4.6 checks - the original assumed a browser and a
 * URL to tamper with, and this is the desktop equivalent.</p>
 */
public class ResultsViewController {

    private final GradeDAO gradeDAO;
    private final SubmissionDAO submissionDAO;
    private final ExecutionDAO executionDAO;
    private final ExamDAO examDAO;

    public ResultsViewController(GradeDAO gradeDAO, SubmissionDAO submissionDAO,
                                 ExecutionDAO executionDAO, ExamDAO examDAO) {
        this.gradeDAO = gradeDAO;
        this.submissionDAO = submissionDAO;
        this.executionDAO = executionDAO;
        this.examDAO = examDAO;
    }

    /**
     * Every exam she has sat, with the mark where it has been approved.
     *
     * <p>Attempts still waiting are listed too, but without a number - acceptance
     * test 4.2 says she should see that it exists and is not ready, rather than
     * find it missing and wonder.</p>
     */
    public Response listMyResults(User user) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student has her own results to view.");
        }
        try {
            List<Grade> all = gradeDAO.findAllForStudent(student.getUserId());

            // Blank out anything not yet approved, so an unapproved mark cannot
            // reach her even by accident (requirement 53, acceptance test 4.2).
            for (Grade grade : all) {
                if (!grade.isApproved()) {
                    grade.setAutoGrade(0);
                    grade.setFinalGrade(0);
                    grade.setFactor(0);
                    grade.setManualChangeExplanation(null);
                    grade.setTeacherGeneralComment(null);
                }
            }

            return Response.ok(all, all.isEmpty()
                    // Acceptance test 4.12.
                    ? "You have not sat any exams yet."
                    : all.size() + " exam(s).");
        } catch (SQLException e) {
            return Response.error("Could not load your results: " + e.getMessage());
        }
    }

    /**
     * One marked paper of hers.
     *
     * <p>Refused unless it is hers <em>and</em> the teacher has approved it.</p>
     */
    public Response getMyMarkedExam(User user, Integer submissionId) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student has her own results to view.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That exam does not exist.");
            }

            // Requirement 57. The wording is deliberately the same as for a
            // non-existent paper, so trying numbers reveals nothing.
            if (!attempt.getStudentId().equals(student.getUserId())) {
                return Response.error("That exam does not exist.");
            }

            Grade grade = gradeDAO.findBySubmission(submissionId);
            if (grade == null || !grade.isApproved()) {
                // Acceptance test 4.2 and branch ב of SUC-10.
                return Response.error("Your teacher has not approved this exam yet. "
                                    + "The mark will appear here once she has.");
            }

            ExamExecution execution = executionDAO.findById(attempt.getExecutionId());
            Exam exam = examDAO.findByIdAndVersion(execution.getExamId(),
                                                   execution.getExamVersion());

            attempt.setExamId(exam.getExamId());
            attempt.setCourseName(exam.getCourseName());
            attempt.setInstructionsForStudents(exam.getInstructionsForStudents());
            // The right answers ARE included here. Requirement 53 says she sees
            // which questions she got wrong, which is meaningless without them -
            // and the exam is over, so there is nothing left to protect.
            attempt.setQuestions(exam.getQuestions());
            attempt.setAnswers(submissionDAO.findAnswers(submissionId));

            grade.setFeedback(gradeDAO.findFeedback(submissionId));

            // Acceptance test 4.10: the teacher's private notes are simply never
            // put on the object. There is no flag to forget to set.
            return Response.ok(new MarkedExam(attempt, grade), null);

        } catch (SQLException e) {
            return Response.error("Could not load that exam: " + e.getMessage());
        }
    }
}
