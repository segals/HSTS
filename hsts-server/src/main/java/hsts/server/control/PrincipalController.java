package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Principal;
import hsts.common.entity.Question;
import hsts.common.entity.User;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.ResultsQuery;
import hsts.common.protocol.ResultsReport;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.QuestionDAO;

import java.sql.SQLException;
import java.util.List;

/**
 * SUC-12 / מתווה scenario 11: the principal looking at everything, changing nothing.
 *
 * <p>Requirement 62: <i>"מנהלת בית הספר תוכל לגשת לכלל הנתונים שהוכנסו למערכת
 * (שאלות, בחינות, תוצאות) בקריאה בלבד"</i> - the question bank, the exams and the
 * results, <b>read-only</b>.</p>
 *
 * <h2>How "read-only" is enforced</h2>
 *
 * <p>Not by hiding buttons. This controller has no method that writes anything -
 * there is no principal path to a write at all, so a client that asked for one
 * would be asking for a request type the server does not have.</p>
 *
 * <p>Everything here also refuses anybody who is not a {@link Principal}, so these
 * requests cannot be used as a way around the rules that apply to teachers and
 * students. In particular a student cannot reach class results through them
 * (requirement 55).</p>
 *
 * <h2>What she is allowed to see that others are not</h2>
 *
 * <p>Requirement 62 says <i>all</i> the data, so exams come to her complete -
 * including {@code notesForTeacher}, the private note its author wrote for herself.
 * Acceptance test 4.10 forbids showing those to a <b>student</b>; nothing forbids
 * showing them to the principal, and "כלל הנתונים" says the opposite.</p>
 *
 * <p>Requirement 63 - comparison reports across teachers, courses and students -
 * is a different job and belongs to the report factory in the next milestone. This
 * is the browsing half of SUC-12.</p>
 *
 * <p>There are <b>no acceptance tests</b> for SUC-12 in the submitted Assignment 1;
 * it covers SUC-3, 7, 9 and 10 only. The behaviour follows מתווה 11 and
 * requirement 62.</p>
 */
public class PrincipalController {

    private final QuestionDAO questionDAO;
    private final ExamDAO examDAO;
    private final ExecutionDAO executionDAO;

    /** Read-only: the record of what the staff have done. */
    private final hsts.server.dao.ActivityDAO activityDAO = new hsts.server.dao.ActivityDAO();
    private final GradeDAO gradeDAO;

    public PrincipalController(QuestionDAO questionDAO, ExamDAO examDAO,
                               ExecutionDAO executionDAO, GradeDAO gradeDAO) {
        this.questionDAO = questionDAO;
        this.examDAO = examDAO;
        this.executionDAO = executionDAO;
        this.gradeDAO = gradeDAO;
    }

    /** The whole question bank, every course (requirement 62). */
    public Response listQuestions(User user) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            List<Question> questions = questionDAO.findAll();
            return Response.ok(questions, questions.isEmpty()
                    ? "The question bank is empty."
                    : questions.size() + " question(s) across all courses.");
        } catch (SQLException e) {
            return Response.error("Could not load the question bank: " + e.getMessage());
        }
    }

    /**
     * One question with its four answers and which of them is correct.
     *
     * <p>Separate from {@link #listQuestions} because the list does <b>not</b>
     * carry answers - {@code QuestionDAO.findAll} deliberately leaves them off, and
     * shipping four rows for every question in the school to draw one detail pane
     * would be waste that grows with the bank. The exams tab works the same way.</p>
     */
    public Response getQuestion(User user, QuestionRef ref) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (ref == null) {
            return Response.error("No question was chosen.");
        }
        try {
            Question question = (ref.getVersion() == QuestionRef.CURRENT)
                    ? questionDAO.findById(ref.getQuestionId())
                    : questionDAO.findByIdAndVersion(ref.getQuestionId(), ref.getVersion());
            if (question == null) {
                return Response.error("That question does not exist.");
            }
            return Response.ok(question, null);
        } catch (SQLException e) {
            return Response.error("Could not load that question: " + e.getMessage());
        }
    }

    /** Every exam, whatever its state and whoever wrote it. */
    public Response listExams(User user) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            List<Exam> exams = examDAO.findAll();
            return Response.ok(exams, exams.isEmpty()
                    ? "No exams have been written yet."
                    : exams.size() + " exam(s).");
        } catch (SQLException e) {
            return Response.error("Could not load the exams: " + e.getMessage());
        }
    }

    /** One exam in full, including its questions and the author's private notes. */
    public Response getExam(User user, ExamRef ref) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (ref == null) {
            return Response.error("No exam was chosen.");
        }
        try {
            Exam exam = (ref.getVersion() == ExamRef.CURRENT)
                    ? examDAO.findById(ref.getExamId())
                    : examDAO.findByIdAndVersion(ref.getExamId(), ref.getVersion());
            if (exam == null) {
                return Response.error("That exam does not exist.");
            }
            return Response.ok(exam, null);
        } catch (SQLException e) {
            return Response.error("Could not load that exam: " + e.getMessage());
        }
    }

    /** The sittings of one exam, so results can be read per class. */
    /**
     * Every sitting in the school, newest first - the calendar.
     *
     * <p>The whole school rather than one exam at a time, because the question a
     * head teacher asks is "what is happening this week", and answering it by
     * opening forty exams one by one is not answering it.</p>
     *
     * <p>Nothing is filtered out: past sittings are the record of the year, and a
     * calendar that quietly dropped them would be a diary rather than a register.
     * The screen filters.</p>
     */
    public Response schoolCalendar(User user) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            List<hsts.common.entity.ExamExecution> sittings = executionDAO.findAll();
            return Response.ok(sittings, sittings.isEmpty()
                    ? "No exam has been given to a class yet."
                    : sittings.size() + " sitting(s) in the school's calendar.");
        } catch (SQLException e) {
            return Response.error("Could not load the calendar: " + e.getMessage());
        }
    }

    /**
     * What the staff have done lately, newest first.
     *
     * <p>Teachers and coordinators only. A student sitting an exam is recorded
     * against her paper and is not staff activity; the principal changes nothing
     * herself, so she would only ever be reading her own reflection.</p>
     */
    public Response recentActivity(User user, Integer howMany) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        int limit = (howMany == null || howMany <= 0) ? 200 : Math.min(howMany, 500);
        try {
            List<hsts.common.entity.ActivityEntry> entries = activityDAO.recent(limit);
            return Response.ok(entries, entries.isEmpty()
                    ? "Nothing has been done yet."
                    : entries.size() + " recent action(s).");
        } catch (SQLException e) {
            return Response.error("Could not load the activity: " + e.getMessage());
        }
    }

    public Response listSittings(User user, String examId) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (examId == null || examId.isBlank()) {
            return Response.error("No exam was chosen.");
        }
        try {
            List<ExamExecution> sittings = executionDAO.findByExam(examId);
            return Response.ok(sittings, sittings.isEmpty()
                    ? "This exam has not been handed out yet."
                    : sittings.size() + " sitting(s).");
        } catch (SQLException e) {
            return Response.error("Could not load the sittings: " + e.getMessage());
        }
    }

    /** Results of one sitting, or of every sitting of an exam together. */
    public Response getResults(User user, ResultsQuery query) {
        String refusal = refuseIfNotPrincipal(user);
        if (refusal != null) {
            return Response.error(refusal);
        }
        if (query == null) {
            return Response.error("No exam was chosen.");
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
                subtitle = executionDAO.findByExam(query.getExamId()).size()
                         + " sitting(s) together";
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

            String title = "Exam " + exam.getExamId() + "  ·  " + exam.getCourseName()
                         + "  ·  written by " + exam.getAuthorName();
            return Response.ok(new ResultsReport(title, subtitle, grades, stats),
                    grades.isEmpty() ? "Nobody has sat this yet."
                                     : stats.getGradeCount() + " approved mark(s).");

        } catch (SQLException e) {
            return Response.error("Could not work out the results: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------

    private String refuseIfNotPrincipal(User user) {
        return (user instanceof Principal) ? null
                : "Only the principal has read-only access to all the school's data.";
    }
}
