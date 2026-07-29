package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.ResultsQuery;
import hsts.common.protocol.ResultsReport;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-11 / מתווה scenario 10: a teacher looking at the results of her exams.
 *
 * <p>מתווה 10 asks for the marks <em>in a table and as a histogram</em>, and
 * requirement 59 says which exams she may see: <b>every exam she wrote, even if
 * another teacher ran it</b>.</p>
 *
 * <h2>A different rule from marking, deliberately</h2>
 *
 * <p>{@link GradingController} lets the teacher who <b>released</b> a sitting mark
 * it, because those are her students. This screen answers a different question, so
 * it uses a different rule: the <b>author</b> may look, because it is her paper and
 * she is entitled to know how it performs.</p>
 *
 * <p>The two do not conflict, because everything here is <b>read-only</b>. Nothing
 * on this path can change or publish a mark. An author looking at a colleague's
 * class can see the numbers and nothing else.</p>
 *
 * <p>The teacher who released a sitting may look too - she has just marked it, and
 * refusing her the summary of her own class would be perverse.</p>
 *
 * <p>There are <b>no acceptance tests</b> for SUC-11 in the submitted Assignment 1;
 * the document covers SUC-3, 7, 9 and 10 only. The behaviour here follows מתווה 10,
 * requirement 59 and requirement 54, and is recorded in the change log.</p>
 */
public class TeacherReportController {

    private final ExamDAO examDAO;
    private final ExecutionDAO executionDAO;
    private final GradeDAO gradeDAO;

    public TeacherReportController(ExamDAO examDAO, ExecutionDAO executionDAO,
                                   GradeDAO gradeDAO) {
        this.examDAO = examDAO;
        this.executionDAO = executionDAO;
        this.gradeDAO = gradeDAO;
    }

    /** Requirement 59: every exam she wrote, whoever ran it. */
    public Response listMyExams(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher has exams of her own to report on.");
        }
        try {
            List<Exam> written = examDAO.findCurrentByAuthor(user.getUserId());
            return Response.ok(written, written.isEmpty()
                    ? "You have not written any exams yet."
                    : written.size() + " exam(s) you wrote.");
        } catch (SQLException e) {
            return Response.error("Could not load your exams: " + e.getMessage());
        }
    }

    /**
     * The sittings of one of her exams.
     *
     * <p>This is where requirement 59 becomes visible: sittings run by other
     * teachers are listed too, each showing who released it.</p>
     */
    public Response listSittings(User user, String examId) {
        String refusal = refuseIfNotHerExam(user, examId);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            List<ExamExecution> sittings = executionDAO.findByExam(examId);
            long byOthers = sittings.stream()
                    .filter(x -> !x.getReleasedBy().equals(user.getUserId()))
                    .count();
            return Response.ok(sittings, sittings.isEmpty()
                    ? "This exam has not been handed out yet."
                    : sittings.size() + " sitting(s)"
                      + (byOthers > 0 ? ", " + byOthers + " run by another teacher." : "."));
        } catch (SQLException e) {
            return Response.error("Could not load the sittings: " + e.getMessage());
        }
    }

    /**
     * The marks and the statistics, for one sitting or for the whole exam.
     *
     * <p>Both shapes מתווה 10 asks for come back together, so the table and the
     * histogram can never disagree about what they are showing.</p>
     */
    public Response getResults(User user, ResultsQuery query) {
        if (query == null) {
            return Response.error("No exam was chosen.");
        }
        String refusal = refuseIfNotHerExam(user, query.getExamId());
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            Exam exam = examDAO.findById(query.getExamId());
            if (exam == null) {
                return Response.error("That exam does not exist.");
            }

            List<Grade> grades;
            ExamStatistics stats;
            String subtitle;

            if (query.isWholeExam()) {
                grades = gradeDAO.findByExam(query.getExamId());
                stats = gradeDAO.computeStatisticsForExam(query.getExamId());
                int sittings = executionDAO.findByExam(query.getExamId()).size();
                subtitle = sittings + " sitting(s) together";
            } else {
                ExamExecution sitting = executionDAO.findById(query.getExecutionId());
                if (sitting == null || !sitting.getExamId().equals(query.getExamId())) {
                    return Response.error("That sitting does not belong to this exam.");
                }
                grades = gradeDAO.findByExecution(query.getExecutionId());
                stats = gradeDAO.computeStatistics(query.getExecutionId());
                subtitle = "Sitting " + sitting.getExecutionCode()
                         + "  ·  released by " + sitting.getReleasedByName()
                         + "  ·  " + sitting.getOpenTime().toLocalDate();
            }

            String title = "Exam " + exam.getExamId() + "  ·  " + exam.getCourseName();
            return Response.ok(new ResultsReport(title, subtitle, grades, stats),
                    describe(stats, grades));

        } catch (SQLException e) {
            return Response.error("Could not work out the results: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------

    private String describe(ExamStatistics stats, List<Grade> grades) {
        if (grades.isEmpty()) {
            return "Nobody has sat this yet.";
        }
        if (stats.getGradeCount() == 0) {
            return grades.size() + " paper(s), none approved yet - "
                 + "the statistics count approved marks only.";
        }
        long waiting = grades.stream().filter(g -> !g.isApproved()).count();
        return stats.getGradeCount() + " approved mark(s)"
             + (waiting > 0 ? ", " + waiting + " still waiting for approval." : ".");
    }

    /**
     * Requirement 59, plus the teacher who ran the sitting.
     *
     * <p>Checked against the exam's author across <em>all</em> its versions: a
     * teacher who wrote version 1 still wrote the exam after somebody edits it.</p>
     */
    private String refuseIfNotHerExam(User user, String examId) {
        if (!(user instanceof Teacher)) {
            return "Only a teacher has exams of her own to report on.";
        }
        if (examId == null || examId.isBlank()) {
            return "No exam was chosen.";
        }
        try {
            List<Exam> versions = examDAO.findAllVersions(examId);
            if (versions.isEmpty()) {
                return "That exam does not exist.";
            }
            for (Exam version : versions) {
                if (user.getUserId().equals(version.getAuthorId())) {
                    return null;                       // she wrote it
                }
            }
            for (ExamExecution sitting : executionDAO.findByExam(examId)) {
                if (user.getUserId().equals(sitting.getReleasedBy())) {
                    return null;                       // she ran it
                }
            }
            return "You did not write that exam and you have not run it, "
                 + "so its results are not yours to see.";
        } catch (SQLException e) {
            return "Could not check that exam: " + e.getMessage();
        }
    }

    /** Exposed for the principal's read-only browse, which has its own rule. */
    List<Grade> allMarksOf(String examId) throws SQLException {
        return new ArrayList<>(gradeDAO.findByExam(examId));
    }
}
