package hsts.server.dao;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;

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
 * All SQL concerning exam executions - the releases from the drawer.
 */
public class ExecutionDAO implements IDAO<ExamExecution, Integer> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Writing
    // -----------------------------------------------------------------

    /** Saves a release and fills in the generated id. */
    @Override
    public void insert(ExamExecution execution) throws SQLException {
        String sql = """
            INSERT INTO exam_execution (exam_id, exam_version, execution_code,
                                        open_time, close_time, allocated_duration,
                                        original_duration, max_attempts, released_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, execution.getExamId());
            ps.setInt(2, execution.getExamVersion());
            ps.setString(3, execution.getExecutionCode());
            ps.setTimestamp(4, Timestamp.valueOf(execution.getOpenTime()));
            ps.setTimestamp(5, Timestamp.valueOf(execution.getCloseTime()));
            ps.setInt(6, execution.getAllocatedDuration());
            ps.setInt(7, execution.getOriginalDuration());
            ps.setInt(8, execution.getMaxAttempts());
            ps.setString(9, execution.getReleasedBy());
            ps.setTimestamp(10, Timestamp.valueOf(execution.getCreatedAt()));
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    execution.setExecutionId(keys.getInt(1));
                }
            }
        }
    }

    /** Changes the allotted minutes for a running execution (milestone 8). */
    public void updateAllocatedDuration(int executionId, int minutes) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE exam_execution SET allocated_duration = ? WHERE execution_id = ?")) {
            ps.setInt(1, minutes);
            ps.setInt(2, executionId);
            ps.executeUpdate();
        }
    }

    @Override
    public void update(ExamExecution execution) {
        throw new UnsupportedOperationException(
                "A release is a record of an event. Release again instead of editing one.");
    }

    @Override
    public void delete(Integer executionId) {
        // Students' submissions and grades hang off this row.
        throw new UnsupportedOperationException("Executions are not deleted.");
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    /**
     * True if this code is already in use by any execution, ever.
     *
     * <p>Uniqueness is global rather than "among open executions". A student types
     * four characters and nothing else, so the code has to identify one execution
     * on its own; scoping it to a time window is fiddly to get right and 36⁴ is
     * over 1.6 million combinations, far more than a school will use.</p>
     */
    public boolean isCodeTaken(String code) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT 1 FROM exam_execution WHERE execution_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Finds an execution by the code a student typed. Case-insensitive. */
    public ExamExecution findByCode(String code) throws SQLException {
        String sql = baseSelect() + " WHERE x.execution_code = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, code == null ? null : code.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    @Override
    public ExamExecution findById(Integer executionId) throws SQLException {
        String sql = baseSelect() + " WHERE x.execution_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    /** Everything this teacher has released, newest first. */
    public List<ExamExecution> findByTeacher(String userId) throws SQLException {
        String sql = baseSelect() + " WHERE x.released_by = ? ORDER BY x.open_time DESC";
        List<ExamExecution> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    /**
     * Sittings open right now on the courses this student is enrolled in.
     *
     * <p>Enrolment is joined here rather than filtered afterwards, because a
     * sitting she may not take is not one to count on her menu. Whether she has an
     * attempt left is a separate question, answered by {@code SubmissionDAO} with
     * the same arithmetic the code screen uses - so the badge and the screen can
     * never disagree about whether she can go in.</p>
     */
    public List<ExamExecution> findOpenForStudent(String studentId) throws SQLException {
        String sql = baseSelect() + """
             JOIN course_student cs ON cs.course_code = e.course_code
                                   AND cs.user_id     = ?
            WHERE NOW() BETWEEN x.open_time AND x.close_time
            ORDER BY x.close_time""";
        List<ExamExecution> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        return list;
    }

    /** Every release of one exam, whatever version. */
    public List<ExamExecution> findByExam(String examId) throws SQLException {
        String sql = baseSelect() + " WHERE x.exam_id = ? ORDER BY x.open_time DESC";
        List<ExamExecution> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
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
     * How many executions of this exam are open at the given moment.
     *
     * <p>This is what "in the drawer" means, now that it is not a stored status:
     * zero open executions and the exam is in the drawer.</p>
     */
    public int countOpenAt(String examId, LocalDateTime moment) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM exam_execution
            WHERE exam_id = ? AND open_time <= ? AND close_time > ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, examId);
            ps.setTimestamp(2, Timestamp.valueOf(moment));
            ps.setTimestamp(3, Timestamp.valueOf(moment));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public List<ExamExecution> findAll() throws SQLException {
        List<ExamExecution> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                     baseSelect() + " ORDER BY x.open_time DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs));
            }
        }
        return list;
    }

    /**
     * The counts requirement 48 asks for, derived rather than stored.
     *
     * <p>Sub-selects rather than stored columns: a counter somebody forgets to
     * increment is wrong forever and nobody notices until the numbers are read
     * out at a demo.</p>
     */
    private String baseSelect() {
        return """
            SELECT x.execution_id, x.exam_id, x.exam_version, x.execution_code,
                   x.open_time, x.close_time, x.allocated_duration, x.original_duration,
                   x.max_attempts, x.released_by, u.full_name AS released_by_name,
                   x.created_at, c.name AS course_name,
                   (SELECT COUNT(*) FROM student_exam s
                     WHERE s.execution_id = x.execution_id) AS num_started,
                   (SELECT COUNT(*) FROM student_exam s
                     WHERE s.execution_id = x.execution_id AND s.status = 'FINISHED')
                     AS num_finished_self,
                   (SELECT COUNT(*) FROM student_exam s
                     WHERE s.execution_id = x.execution_id AND s.status = 'TIMED_OUT')
                     AS num_timed_out
            FROM exam_execution x
            JOIN users u ON u.user_id     = x.released_by
            JOIN exam  e ON e.exam_id     = x.exam_id AND e.version = x.exam_version
            JOIN course c ON c.course_code = e.course_code""";
    }

    private ExamExecution readRow(ResultSet rs) throws SQLException {
        ExamExecution x = new ExamExecution();
        x.setExecutionId(rs.getInt("execution_id"));
        x.setExamId(rs.getString("exam_id"));
        x.setExamVersion(rs.getInt("exam_version"));
        x.setExecutionCode(rs.getString("execution_code"));
        x.setOpenTime(rs.getTimestamp("open_time").toLocalDateTime());
        x.setCloseTime(rs.getTimestamp("close_time").toLocalDateTime());
        x.setAllocatedDuration(rs.getInt("allocated_duration"));
        x.setOriginalDuration(rs.getInt("original_duration"));
        x.setMaxAttempts(rs.getInt("max_attempts"));
        x.setReleasedBy(rs.getString("released_by"));
        x.setReleasedByName(rs.getString("released_by_name"));
        x.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        x.setCourseName(rs.getString("course_name"));
        x.setNumStarted(rs.getInt("num_started"));
        x.setNumFinishedSelf(rs.getInt("num_finished_self"));
        x.setNumTimedOut(rs.getInt("num_timed_out"));
        return x;
    }

    /** Approved exam versions in the given courses that a teacher may release. */
    public List<Exam> findReleasableForCourses(List<String> courseCodes) throws SQLException {
        if (courseCodes == null || courseCodes.isEmpty()) {
            return new ArrayList<>();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(courseCodes.size(), "?"));
        String sql = """
            SELECT e.exam_id, e.version, e.course_code, e.subject_code, c.name AS course_name,
                   e.duration_minutes, e.instructions_for_students, e.notes_for_teacher,
                   e.author_id, u.full_name AS author_name, e.status, e.rejection_reason,
                   e.approved_by, e.approved_at, e.is_current, e.created_at
            FROM exam e
            JOIN users  u ON u.user_id     = e.author_id
            JOIN course c ON c.course_code = e.course_code
            WHERE e.status = 'APPROVED' AND e.course_code IN (""" + placeholders + """
            )
            ORDER BY e.exam_id, e.version DESC""";

        List<Exam> exams = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < courseCodes.size(); i++) {
                ps.setString(i + 1, courseCodes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Exam exam = new Exam();
                    exam.setExamId(rs.getString("exam_id"));
                    exam.setVersion(rs.getInt("version"));
                    exam.setCourseCode(rs.getString("course_code"));
                    exam.setSubjectCode(rs.getString("subject_code"));
                    exam.setCourseName(rs.getString("course_name"));
                    exam.setDurationMinutes(rs.getInt("duration_minutes"));
                    exam.setInstructionsForStudents(rs.getString("instructions_for_students"));
                    exam.setAuthorId(rs.getString("author_id"));
                    exam.setAuthorName(rs.getString("author_name"));
                    exam.setStatus(hsts.common.enums.ExamStatus.valueOf(rs.getString("status")));
                    exam.setCurrent(rs.getBoolean("is_current"));
                    exam.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    exams.add(exam);
                }
            }
        }
        return exams;
    }
}
