package hsts.server.dao;

import hsts.common.entity.StudentAnswer;
import hsts.common.entity.StudentExam;
import hsts.common.enums.SubmissionStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQL concerning students' attempts and the answers they choose.
 */
public class SubmissionDAO implements IDAO<StudentExam, Integer> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Starting
    // -----------------------------------------------------------------

    /** Creates the attempt row and fills in its generated id. */
    @Override
    public void insert(StudentExam attempt) throws SQLException {
        String sql = """
            INSERT INTO student_exam (execution_id, student_id, attempt_no,
                                      start_time, deadline, status)
            VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, attempt.getExecutionId());
            ps.setString(2, attempt.getStudentId());
            ps.setInt(3, attempt.getAttemptNo());
            ps.setTimestamp(4, Timestamp.valueOf(attempt.getStartTime()));
            ps.setTimestamp(5, Timestamp.valueOf(attempt.getDeadline()));
            ps.setString(6, attempt.getStatus().name());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    attempt.setSubmissionId(keys.getInt(1));
                }
            }
        }
    }

    /** How many attempts this student has already made at this sitting. */
    public int countAttempts(int executionId, String studentId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM student_exam WHERE execution_id = ? AND student_id = ?")) {
            ps.setInt(1, executionId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Her attempt that is still running at this sitting, if any.
     *
     * <p>Used so that reopening the client puts her back where she was rather than
     * starting a second attempt - acceptance test 2.11.</p>
     */
    public StudentExam findInProgressFor(int executionId, String studentId) throws SQLException {
        String sql = baseSelect() + " WHERE s.execution_id = ? AND s.student_id = ?"
                   + " AND s.status = 'IN_PROGRESS' ORDER BY s.attempt_no DESC LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, executionId);
            ps.setString(2, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    // -----------------------------------------------------------------
    //  Answering
    // -----------------------------------------------------------------

    /**
     * Records one choice, replacing any earlier one for the same question.
     *
     * <p>Written as it is chosen rather than all at once on submit. Requirement 45
     * says whatever she had entered is kept when the time runs out, and a client
     * that dies mid-exam must not take her work with it.</p>
     */
    public void saveAnswer(int submissionId, String questionId, int questionVersion,
                           Integer selectedAnswerNo) throws SQLException {
        String sql = """
            INSERT INTO student_answer (submission_id, question_id, question_version,
                                        selected_answer_no)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE selected_answer_no = VALUES(selected_answer_no)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, submissionId);
            ps.setString(2, questionId);
            ps.setInt(3, questionVersion);
            if (selectedAnswerNo == null) {
                ps.setNull(4, java.sql.Types.TINYINT);
            } else {
                ps.setInt(4, selectedAnswerNo);
            }
            ps.executeUpdate();
        }
    }

    public List<StudentAnswer> findAnswers(int submissionId) throws SQLException {
        String sql = """
            SELECT question_id, question_version, selected_answer_no
            FROM student_answer WHERE submission_id = ?""";
        List<StudentAnswer> answers = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int chosen = rs.getInt("selected_answer_no");
                    answers.add(new StudentAnswer(rs.getString("question_id"),
                            rs.getInt("question_version"),
                            rs.wasNull() ? null : chosen));
                }
            }
        }
        return answers;
    }

    // -----------------------------------------------------------------
    //  Finishing
    // -----------------------------------------------------------------

    /**
     * Closes an attempt - by her hand or by the clock.
     *
     * <p>The {@code status = 'IN_PROGRESS'} condition matters. A student pressing
     * Submit at the same instant the clock service times her out would otherwise
     * be written twice, and the second write would overwrite the first. With the
     * condition, whichever arrives second changes no rows and the caller can see
     * that it lost the race.</p>
     *
     * @return true if this call closed it; false if it was already closed
     */
    public boolean finish(int submissionId, SubmissionStatus status,
                          LocalDateTime endTime, int actualMinutes) throws SQLException {
        String sql = """
            UPDATE student_exam
            SET status = ?, end_time = ?, actual_duration = ?
            WHERE submission_id = ? AND status = 'IN_PROGRESS'""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.valueOf(endTime));
            ps.setInt(3, actualMinutes);
            ps.setInt(4, submissionId);
            return ps.executeUpdate() > 0;
        }
    }

    /** Moves the personal deadline - a teacher granting extra time (milestone 8). */
    public int extendDeadlines(int executionId, int extraMinutes) throws SQLException {
        String sql = """
            UPDATE student_exam
            SET deadline = DATE_ADD(deadline, INTERVAL ? MINUTE)
            WHERE execution_id = ? AND status = 'IN_PROGRESS'""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, extraMinutes);
            ps.setInt(2, executionId);
            return ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    @Override
    public StudentExam findById(Integer submissionId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                baseSelect() + " WHERE s.submission_id = ?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    /** Every attempt still running - what the clock service watches. */
    public List<StudentExam> findAllInProgress() throws SQLException {
        List<StudentExam> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                     baseSelect() + " WHERE s.status = 'IN_PROGRESS'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs));
            }
        }
        return list;
    }

    public List<StudentExam> findByExecution(int executionId) throws SQLException {
        List<StudentExam> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                baseSelect() + " WHERE s.execution_id = ? ORDER BY s.start_time")) {
            ps.setInt(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    @Override
    public List<StudentExam> findAll() throws SQLException {
        List<StudentExam> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs));
            }
        }
        return list;
    }

    @Override
    public void update(StudentExam attempt) {
        throw new UnsupportedOperationException("Use saveAnswer, finish or extendDeadlines.");
    }

    @Override
    public void delete(Integer submissionId) {
        // A sat exam is a record. Grades and feedback hang off it.
        throw new UnsupportedOperationException("Submissions are not deleted.");
    }

    private String baseSelect() {
        return """
            SELECT s.submission_id, s.execution_id, s.student_id, u.full_name AS student_name,
                   u.username AS student_username,
                   s.attempt_no, s.start_time, s.deadline, s.end_time, s.actual_duration,
                   s.status, x.exam_id, c.name AS course_name,
                   e.instructions_for_students
            FROM student_exam s
            JOIN users u          ON u.user_id      = s.student_id
            JOIN exam_execution x ON x.execution_id = s.execution_id
            JOIN exam e           ON e.exam_id      = x.exam_id AND e.version = x.exam_version
            JOIN course c         ON c.course_code  = e.course_code""";
    }

    private StudentExam readRow(ResultSet rs) throws SQLException {
        StudentExam s = new StudentExam();
        s.setSubmissionId(rs.getInt("submission_id"));
        s.setExecutionId(rs.getInt("execution_id"));
        s.setStudentId(rs.getString("student_id"));
        s.setStudentName(rs.getString("student_name"));
        s.setStudentUsername(rs.getString("student_username"));
        s.setAttemptNo(rs.getInt("attempt_no"));
        s.setStartTime(rs.getTimestamp("start_time").toLocalDateTime());
        s.setDeadline(rs.getTimestamp("deadline").toLocalDateTime());
        Timestamp end = rs.getTimestamp("end_time");
        s.setEndTime(end == null ? null : end.toLocalDateTime());
        int minutes = rs.getInt("actual_duration");
        s.setActualDuration(rs.wasNull() ? null : minutes);
        s.setStatus(SubmissionStatus.valueOf(rs.getString("status")));
        s.setExamId(rs.getString("exam_id"));
        s.setCourseName(rs.getString("course_name"));
        s.setInstructionsForStudents(rs.getString("instructions_for_students"));
        return s;
    }
}
