package hsts.server.dao;

import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.QuestionFeedback;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All SQL concerning marks.
 */
public class GradeDAO implements IDAO<Grade, Integer> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Automatic marking
    // -----------------------------------------------------------------

    /**
     * Marks one attempt and stores the result.
     *
     * <p>Deliberately computed in Java rather than as one large join. The rule -
     * add up the points of the questions she got right - is something the course
     * staff will ask about at the demo, and it is far easier to read here than
     * buried in SQL. The volumes are one exam at a time, so nothing is lost.</p>
     *
     * <p>Safe to call twice: an attempt already marked is left alone, so a
     * re-submission cannot wipe a teacher's manual change.</p>
     *
     * @return the mark that was stored, or the existing one if it was already marked
     */
    public int autoGrade(int submissionId) throws SQLException {
        Grade existing = findBySubmission(submissionId);
        if (existing != null) {
            return existing.getFinalGrade();
        }

        // The paper: question -> (points, which option is correct)
        record Marking(int points, Integer correctAnswerNo, int questionVersion) { }
        Map<String, Marking> paper = new LinkedHashMap<>();

        String paperSql = """
            SELECT eq.question_id, eq.question_version, eq.points,
                   (SELECT a.answer_no FROM answer a
                     WHERE a.question_id = eq.question_id
                       AND a.question_version = eq.question_version
                       AND a.is_correct = TRUE LIMIT 1) AS correct_no
            FROM student_exam s
            JOIN exam_execution x ON x.execution_id = s.execution_id
            JOIN exam_question eq ON eq.exam_id = x.exam_id AND eq.exam_version = x.exam_version
            WHERE s.submission_id = ?
            ORDER BY eq.q_order""";

        try (PreparedStatement ps = conn().prepareStatement(paperSql)) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int correct = rs.getInt("correct_no");
                    paper.put(rs.getString("question_id"), new Marking(
                            rs.getInt("points"),
                            rs.wasNull() ? null : correct,
                            rs.getInt("question_version")));
                }
            }
        }

        // What she chose.
        Map<String, Integer> chosen = new LinkedHashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT question_id, selected_answer_no FROM student_answer WHERE submission_id = ?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int value = rs.getInt("selected_answer_no");
                    chosen.put(rs.getString("question_id"), rs.wasNull() ? null : value);
                }
            }
        }

        int total = 0;
        List<QuestionFeedback> feedback = new ArrayList<>();

        for (var entry : paper.entrySet()) {
            String questionId = entry.getKey();
            Marking marking = entry.getValue();
            Integer answer = chosen.get(questionId);

            // A blank counts as wrong - acceptance test 2.12.
            boolean right = answer != null && marking.correctAnswerNo() != null
                         && answer.equals(marking.correctAnswerNo());
            if (right) {
                total += marking.points();
            }
            feedback.add(new QuestionFeedback(questionId, marking.questionVersion(), !right, null));
        }

        insertGrade(submissionId, total);
        replaceFeedback(submissionId, feedback);
        return total;
    }

    private void insertGrade(int submissionId, int mark) throws SQLException {
        String sql = """
            INSERT INTO grade (submission_id, auto_grade, final_grade, factor, is_approved)
            VALUES (?, ?, ?, 0, FALSE)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, submissionId);
            ps.setInt(2, mark);
            ps.setInt(3, mark);
            ps.executeUpdate();
        }
    }

    private void replaceFeedback(int submissionId, List<QuestionFeedback> feedback)
            throws SQLException {
        String sql = """
            INSERT INTO question_feedback (submission_id, question_id, question_version,
                                           comment, is_wrong)
            VALUES (?, ?, ?, NULL, ?)
            ON DUPLICATE KEY UPDATE is_wrong = VALUES(is_wrong)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (QuestionFeedback f : feedback) {
                ps.setInt(1, submissionId);
                ps.setString(2, f.getQuestionId());
                ps.setInt(3, f.getQuestionVersion());
                ps.setBoolean(4, f.isWrong());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // -----------------------------------------------------------------
    //  The teacher's changes
    // -----------------------------------------------------------------

    /** Changes the mark by hand. The explanation is compulsory (requirement 52). */
    public void changeFinalGrade(int submissionId, int newGrade, String explanation,
                                 String byUserId) throws SQLException {
        String sql = """
            UPDATE grade SET final_grade = ?, manual_change_explanation = ?, graded_by = ?
            WHERE submission_id = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, newGrade);
            ps.setString(2, explanation);
            ps.setString(3, byUserId);
            ps.setInt(4, submissionId);
            ps.executeUpdate();
        }
    }

    /** A note against one question (requirement 51). */
    public void setQuestionComment(int submissionId, String questionId, int questionVersion,
                                   String comment) throws SQLException {
        String sql = """
            INSERT INTO question_feedback (submission_id, question_id, question_version,
                                           comment, is_wrong)
            VALUES (?, ?, ?, ?, FALSE)
            ON DUPLICATE KEY UPDATE comment = VALUES(comment)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, submissionId);
            ps.setString(2, questionId);
            ps.setInt(3, questionVersion);
            ps.setString(4, comment);
            ps.executeUpdate();
        }
    }

    public void setGeneralComment(int submissionId, String comment) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE grade SET teacher_general_comment = ? WHERE submission_id = ?")) {
            ps.setString(1, comment);
            ps.setInt(2, submissionId);
            ps.executeUpdate();
        }
    }

    /** Publishes one mark to the student (requirement 53). */
    public boolean approve(int submissionId, String byUserId) throws SQLException {
        String sql = """
            UPDATE grade SET is_approved = TRUE, approved_at = ?, graded_by = COALESCE(graded_by, ?)
            WHERE submission_id = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(2, byUserId);
            ps.setInt(3, submissionId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Publishes every unapproved mark in one sitting (acceptance test 3.10). */
    public int approveAll(int executionId, String byUserId) throws SQLException {
        String sql = """
            UPDATE grade g
            JOIN student_exam s ON s.submission_id = g.submission_id
            SET g.is_approved = TRUE, g.approved_at = ?,
                g.graded_by = COALESCE(g.graded_by, ?)
            WHERE s.execution_id = ? AND g.is_approved = FALSE""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(2, byUserId);
            ps.setInt(3, executionId);
            return ps.executeUpdate();
        }
    }

    /**
     * Adds a factor to every mark in a sitting (requirement 77).
     *
     * <p>Additive and clamped to 0-100, as agreed. The clamp is done in SQL so a
     * factor cannot push anybody past 100 and break the {@code CHECK} constraint -
     * acceptance test 3.6 requires marks to stay in range.</p>
     */
    public int applyFactor(int executionId, int delta, String byUserId) throws SQLException {
        String sql = """
            UPDATE grade g
            JOIN student_exam s ON s.submission_id = g.submission_id
            SET g.final_grade = LEAST(100, GREATEST(0, g.final_grade + ?)),
                g.factor = g.factor + ?,
                g.graded_by = ?
            WHERE s.execution_id = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setInt(2, delta);
            ps.setString(3, byUserId);
            ps.setInt(4, executionId);
            return ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    public Grade findBySubmission(int submissionId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                baseSelect() + " WHERE g.submission_id = ?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    @Override
    public Grade findById(Integer submissionId) throws SQLException {
        return findBySubmission(submissionId);
    }

    /** Every mark in one sitting. Only students who have handed in appear here. */
    public List<Grade> findByExecution(int executionId) throws SQLException {
        return query(baseSelect() + " WHERE s.execution_id = ? ORDER BY u.full_name", executionId);
    }

    /**
     * Everybody who started one sitting, marked or not - the teacher's marking list.
     *
     * <p>Driven from {@code student_exam} with a <b>left</b> join onto {@code grade},
     * because a student who is still sitting the exam has no row in {@code grade}
     * yet. {@link #findByExecution} joins the other way and so silently drops her.</p>
     *
     * <p>That mattered: the sittings list counts everyone who <em>started</em>, so a
     * sitting saying "1 sat it" opened onto an empty student list, and a teacher had
     * no way to see that somebody was still working. She is now listed and flagged,
     * which is also the message acceptance test 3.11 asks for.</p>
     */
    /**
     * How many handed-in papers on this teacher's sittings still need her approval.
     *
     * <p>The number behind the badge on "Mark and approve grades". Driven from
     * {@code student_exam} with a <b>left</b> join for the same reason as the list
     * below: a paper that has been handed in but not yet marked has no {@code grade}
     * row at all - it is marked when she opens the sitting - and it is still a paper
     * waiting for her. Joining the other way would show a badge of nought above a
     * screen full of work.</p>
     *
     * <p>Papers still being sat are excluded: nothing is waiting for the teacher
     * while the student is still writing.</p>
     */
    public int countAwaitingApprovalBy(String teacherId) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM student_exam s
            JOIN exam_execution x ON x.execution_id  = s.execution_id
            LEFT JOIN grade g     ON g.submission_id = s.submission_id
            WHERE x.released_by = ?
              AND s.status <> 'IN_PROGRESS'
              AND (g.submission_id IS NULL OR g.is_approved = FALSE)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, teacherId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Grade> findByExecutionIncludingUnmarked(int executionId) throws SQLException {
        String sql = """
            SELECT s.submission_id, g.auto_grade, g.final_grade, g.factor, g.is_approved,
                   g.approved_at, g.manual_change_explanation, g.teacher_general_comment,
                   g.graded_by, s.student_id, u.full_name AS student_name,
                   s.actual_duration, s.execution_id, s.status AS submission_status,
                   x.exam_id, e.name AS exam_name, c.name AS course_name,
                   (g.submission_id IS NOT NULL) AS is_marked
            FROM student_exam s
            LEFT JOIN grade g      ON g.submission_id = s.submission_id
            JOIN users u           ON u.user_id       = s.student_id
            JOIN exam_execution x  ON x.execution_id  = s.execution_id
            JOIN exam e            ON e.exam_id = x.exam_id AND e.version = x.exam_version
            JOIN course c          ON c.course_code   = e.course_code
            WHERE s.execution_id = ?
            ORDER BY u.full_name""";

        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Grade g = readRow(rs);
                    g.setMarked(rs.getBoolean("is_marked"));
                    list.add(g);
                }
            }
        }
        return list;
    }

    /**
     * A student's own approved marks, and nothing else.
     *
     * <p>Requirements 55 and 57 in one query: filtered to her id, and filtered to
     * approved. Doing it here means no screen can accidentally show more.</p>
     */
    public List<Grade> findApprovedForStudent(String studentId) throws SQLException {
        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect()
                + " WHERE s.student_id = ? AND g.is_approved = TRUE ORDER BY s.start_time DESC")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    /** Every attempt of hers, approved or not, so she can be told what is pending. */
    public List<Grade> findAllForStudent(String studentId) throws SQLException {
        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect()
                + " WHERE s.student_id = ? ORDER BY s.start_time DESC")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    public List<QuestionFeedback> findFeedback(int submissionId) throws SQLException {
        List<QuestionFeedback> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT question_id, question_version, comment, is_wrong "
              + "FROM question_feedback WHERE submission_id = ?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new QuestionFeedback(rs.getString(1), rs.getInt(2),
                            rs.getBoolean(4), rs.getString(3)));
                }
            }
        }
        return list;
    }

    @Override
    public List<Grade> findAll() throws SQLException {
        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs));
            }
        }
        return list;
    }

    private List<Grade> query(String sql, int parameter) throws SQLException {
        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    // -----------------------------------------------------------------
    //  Statistics
    // -----------------------------------------------------------------

    /**
     * Average, median and deciles over the <b>approved</b> marks of one sitting.
     *
     * <p>Approved only, because SUC-9 computes statistics after the teacher has
     * approved (step 7 follows step 6) and acceptance test 3.7 counts approved
     * exams. Including drafts would make the average move about while she works.</p>
     *
     * <p>Recomputed on demand rather than cached, so acceptance test 3.15 - the
     * average reflecting a manual change immediately - is true by construction.</p>
     */
    public ExamStatistics computeStatistics(int executionId) throws SQLException {
        String sql = """
            SELECT g.final_grade FROM grade g
            JOIN student_exam s ON s.submission_id = g.submission_id
            WHERE s.execution_id = ? AND g.is_approved = TRUE
            ORDER BY g.final_grade""";
        List<Integer> marks = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marks.add(rs.getInt(1));
                }
            }
        }
        ExamStatistics stats = statisticsOver(marks);
        stats.setExecutionId(executionId);
        return stats;
    }

    /**
     * The same figures over <b>every sitting</b> of one exam.
     *
     * <p>Requirement 59 asks for an analysis of an exam a teacher wrote, and an
     * exam is handed out many times - a different class, a re-sit. Per-sitting
     * numbers answer "how did this class do"; these answer "how does this paper
     * behave", which is the question an author is actually asking.</p>
     */
    public ExamStatistics computeStatisticsForExam(String examId) throws SQLException {
        String sql = """
            SELECT g.final_grade FROM grade g
            JOIN student_exam s   ON s.submission_id = g.submission_id
            JOIN exam_execution x ON x.execution_id  = s.execution_id
            WHERE x.exam_id = ? AND g.is_approved = TRUE
            ORDER BY g.final_grade""";
        List<Integer> marks = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    marks.add(rs.getInt(1));
                }
            }
        }
        return statisticsOver(marks);
    }

    /** Every mark of every sitting of one exam - the author's results table. */
    public List<Grade> findByExam(String examId) throws SQLException {
        List<Grade> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect()
                + " WHERE x.exam_id = ? ORDER BY g.final_grade DESC, u.full_name")) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    /**
     * The arithmetic itself lives on {@link ExamStatistics}.
     *
     * <p>It moved there when the reports of מתווה 12 became a fourth caller. One
     * copy of the maths means the class average, the exam average and the report
     * average can never disagree.</p>
     */
    private ExamStatistics statisticsOver(List<Integer> marks) {
        return ExamStatistics.over(marks);
    }

    // -----------------------------------------------------------------

    @Override
    public void insert(Grade grade) {
        throw new UnsupportedOperationException("Marks are created by autoGrade(submissionId).");
    }

    @Override
    public void update(Grade grade) {
        throw new UnsupportedOperationException(
                "Use changeFinalGrade, setQuestionComment, setGeneralComment or approve.");
    }

    @Override
    public void delete(Integer submissionId) {
        throw new UnsupportedOperationException("Marks are not deleted.");
    }

    private String baseSelect() {
        return """
            SELECT g.submission_id, g.auto_grade, g.final_grade, g.factor, g.is_approved,
                   g.approved_at, g.manual_change_explanation, g.teacher_general_comment,
                   g.graded_by, s.student_id, u.full_name AS student_name,
                   s.actual_duration, s.execution_id, s.status AS submission_status,
                   x.exam_id, e.name AS exam_name, c.name AS course_name
            FROM grade g
            JOIN student_exam s    ON s.submission_id = g.submission_id
            JOIN users u           ON u.user_id       = s.student_id
            JOIN exam_execution x  ON x.execution_id  = s.execution_id
            JOIN exam e            ON e.exam_id = x.exam_id AND e.version = x.exam_version
            JOIN course c          ON c.course_code   = e.course_code""";
    }

    private Grade readRow(ResultSet rs) throws SQLException {
        Grade g = new Grade();
        g.setSubmissionId(rs.getInt("submission_id"));
        g.setAutoGrade(rs.getInt("auto_grade"));
        g.setFinalGrade(rs.getInt("final_grade"));
        g.setFactor(rs.getInt("factor"));
        g.setApproved(rs.getBoolean("is_approved"));
        Timestamp approved = rs.getTimestamp("approved_at");
        g.setApprovedAt(approved == null ? null : approved.toLocalDateTime());
        g.setManualChangeExplanation(rs.getString("manual_change_explanation"));
        g.setTeacherGeneralComment(rs.getString("teacher_general_comment"));
        g.setGradedBy(rs.getString("graded_by"));
        g.setStudentId(rs.getString("student_id"));
        g.setStudentName(rs.getString("student_name"));
        int minutes = rs.getInt("actual_duration");
        g.setActualDuration(rs.wasNull() ? null : minutes);
        g.setExecutionId(rs.getInt("execution_id"));
        g.setSubmissionStatus(rs.getString("submission_status"));
        g.setExamId(rs.getString("exam_id"));
        g.setExamName(rs.getString("exam_name"));
        g.setCourseName(rs.getString("course_name"));
        return g;
    }
}
