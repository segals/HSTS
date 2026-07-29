package hsts.server.dao;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamQuestion;
import hsts.common.enums.ExamStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQL concerning exams.
 *
 * <p>Versioned the same way questions are: editing writes a new row rather than
 * changing an old one, because מתווה scenario 3 item 5 requires
 * "המבחן הקודם נשאר במאגר".</p>
 */
public class ExamDAO implements IDAO<Exam, String> {

    private final QuestionDAO questionDAO = new QuestionDAO();

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Identifier generation
    // -----------------------------------------------------------------

    /**
     * Builds the next free 6-digit exam id.
     *
     * <p>Format fixed by system description §3.2: digits 0-1 the exam code,
     * digits 2-3 the course code, digits 4-5 the subject code. Exam 3 of course
     * 05 in subject 02 is {@code 030502}.</p>
     *
     * <p>As with questions, the maximum is taken across all versions so a number
     * is never reused - two exams sharing an id would make every stored result
     * ambiguous.</p>
     */
    public String generateNextExamId(String courseCode, String subjectCode) throws SQLException {
        String sql = """
            SELECT MAX(CAST(SUBSTRING(exam_id, 1, 2) AS UNSIGNED))
            FROM exam WHERE course_code = ?""";

        int next = 1;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    next = rs.getInt(1) + 1;
                }
            }
        }
        if (next > 99) {
            throw new SQLException(
                "Course " + courseCode + " already has 99 exams, which is all the 6-digit "
              + "format allows (2 digits for the exam code).");
        }
        return String.format("%02d%s%s", next, courseCode, subjectCode);
    }

    // -----------------------------------------------------------------
    //  Writing
    // -----------------------------------------------------------------

    @Override
    public void insert(Exam exam) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            exam.setVersion(1);
            exam.setCurrent(true);
            exam.setStatus(ExamStatus.PENDING_APPROVAL);
            exam.setCreatedAt(LocalDateTime.now());

            insertRow(exam);
            insertQuestions(exam);

            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /**
     * Saves an edit as a new version, leaving the old one in place.
     *
     * <p>The new version goes back to {@code PENDING_APPROVAL} whatever the old
     * one was. An approved exam that is then edited is no longer the exam the
     * coordinator approved, so it has to be looked at again - otherwise editing
     * would be a way to slip changes past approval entirely.</p>
     *
     * @return the version number that was written
     */
    public int createNewVersion(Exam edited) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int latest = findLatestVersion(edited.getExamId());
            if (latest == 0) {
                throw new SQLException("No exam with id " + edited.getExamId());
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE exam SET is_current = FALSE WHERE exam_id = ?")) {
                ps.setString(1, edited.getExamId());
                ps.executeUpdate();
            }

            edited.setVersion(latest + 1);
            edited.setCurrent(true);
            edited.setStatus(ExamStatus.PENDING_APPROVAL);
            edited.setRejectionReason(null);
            edited.setApprovedBy(null);
            edited.setApprovedAt(null);
            edited.setCreatedAt(LocalDateTime.now());

            insertRow(edited);
            insertQuestions(edited);

            c.commit();
            return edited.getVersion();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private void insertRow(Exam exam) throws SQLException {
        String sql = """
            INSERT INTO exam (exam_id, version, course_code, subject_code, duration_minutes,
                              instructions_for_students, notes_for_teacher, author_id,
                              status, rejection_reason, approved_by, approved_at,
                              is_current, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, exam.getExamId());
            ps.setInt(2, exam.getVersion());
            ps.setString(3, exam.getCourseCode());
            ps.setString(4, exam.getSubjectCode());
            ps.setInt(5, exam.getDurationMinutes());
            ps.setString(6, exam.getInstructionsForStudents());
            ps.setString(7, exam.getNotesForTeacher());
            ps.setString(8, exam.getAuthorId());
            ps.setString(9, exam.getStatus().name());
            ps.setString(10, exam.getRejectionReason());
            ps.setString(11, exam.getApprovedBy());
            ps.setTimestamp(12, exam.getApprovedAt() == null
                    ? null : Timestamp.valueOf(exam.getApprovedAt()));
            ps.setBoolean(13, exam.isCurrent());
            ps.setTimestamp(14, Timestamp.valueOf(exam.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    private void insertQuestions(Exam exam) throws SQLException {
        String sql = """
            INSERT INTO exam_question (exam_id, exam_version, question_id, question_version,
                                       points, q_order)
            VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (ExamQuestion eq : exam.getQuestions()) {
                ps.setString(1, exam.getExamId());
                ps.setInt(2, exam.getVersion());
                ps.setString(3, eq.getQuestionId());
                ps.setInt(4, eq.getQuestionVersion());
                ps.setInt(5, eq.getPoints());
                ps.setInt(6, eq.getOrder());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int findLatestVersion(String examId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT MAX(version) FROM exam WHERE exam_id = ?")) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Used by the approval milestone. */
    public void updateStatus(String examId, int version, ExamStatus status,
                             String reason, String byUserId) throws SQLException {
        String sql = """
            UPDATE exam SET status = ?, rejection_reason = ?, approved_by = ?, approved_at = ?
            WHERE exam_id = ? AND version = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, reason);
            ps.setString(3, byUserId);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(5, examId);
            ps.setInt(6, version);
            ps.executeUpdate();
        }
    }

    @Override
    public void update(Exam exam) {
        throw new UnsupportedOperationException("Exams are versioned - use createNewVersion(exam).");
    }

    @Override
    public void delete(String examId) {
        // An exam may already have been sat. Removing it would orphan every
        // submission and grade that points at it.
        throw new UnsupportedOperationException("Exams are not deleted.");
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    public Exam findByIdAndVersion(String examId, int version) throws SQLException {
        String sql = baseSelect() + " WHERE e.exam_id = ? AND e.version = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, examId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Exam exam = readRow(rs);
                exam.setQuestions(findExamQuestions(examId, version, true));
                return exam;
            }
        }
    }

    @Override
    public Exam findById(String examId) throws SQLException {
        int latest = findLatestVersion(examId);
        return latest == 0 ? null : findByIdAndVersion(examId, latest);
    }

    /** Current versions of every exam this teacher wrote. */
    public List<Exam> findCurrentByAuthor(String authorId) throws SQLException {
        String sql = baseSelect() + " WHERE e.author_id = ? AND e.is_current = TRUE"
                   + " ORDER BY e.created_at DESC";
        return queryList(sql, authorId, null);
    }

    /** Current versions of every exam written for one course (מתווה 12). */
    public List<Exam> findCurrentByCourse(String courseCode) throws SQLException {
        String sql = baseSelect() + " WHERE e.course_code = ? AND e.is_current = TRUE"
                   + " ORDER BY e.exam_id";
        return queryList(sql, courseCode, null);
    }

    /** Every exam awaiting approval in one subject - the coordinator's list. */
    public List<Exam> findPendingBySubject(String subjectCode) throws SQLException {
        String sql = baseSelect()
                   + " WHERE e.subject_code = ? AND e.is_current = TRUE AND e.status = ?"
                   + " ORDER BY e.created_at";
        return queryList(sql, subjectCode, ExamStatus.PENDING_APPROVAL.name());
    }

    public List<Exam> findAllVersions(String examId) throws SQLException {
        String sql = baseSelect() + " WHERE e.exam_id = ? ORDER BY e.version DESC";
        return queryList(sql, examId, null);
    }

    @Override
    public List<Exam> findAll() throws SQLException {
        String sql = baseSelect() + " WHERE e.is_current = TRUE ORDER BY e.exam_id";
        List<Exam> exams = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                exams.add(readRow(rs));
            }
        }
        for (Exam exam : exams) {
            exam.setQuestions(findExamQuestions(exam.getExamId(), exam.getVersion(), false));
        }
        return exams;
    }

    private List<Exam> queryList(String sql, String p1, String p2) throws SQLException {
        List<Exam> exams = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, p1);
            if (p2 != null) {
                ps.setString(2, p2);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    exams.add(readRow(rs));
                }
            }
        }
        for (Exam exam : exams) {
            exam.setQuestions(findExamQuestions(exam.getExamId(), exam.getVersion(), false));
        }
        return exams;
    }

    private String baseSelect() {
        return """
            SELECT e.exam_id, e.version, e.course_code, e.subject_code, c.name AS course_name,
                   e.duration_minutes, e.instructions_for_students, e.notes_for_teacher,
                   e.author_id, u.full_name AS author_name, e.status, e.rejection_reason,
                   e.approved_by, e.approved_at, e.is_current, e.created_at
            FROM exam e
            JOIN users  u ON u.user_id     = e.author_id
            JOIN course c ON c.course_code = e.course_code""";
    }

    /**
     * The questions of one exam version.
     *
     * @param withFullQuestions load each question's own text and answers. False
     *        for list views, which only need the count and the points - fetching
     *        every question of every exam to draw a list would be wasteful.
     */
    private List<ExamQuestion> findExamQuestions(String examId, int version,
                                                 boolean withFullQuestions) throws SQLException {
        String sql = """
            SELECT question_id, question_version, points, q_order
            FROM exam_question
            WHERE exam_id = ? AND exam_version = ?
            ORDER BY q_order""";

        List<ExamQuestion> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, examId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new ExamQuestion(rs.getString(1), rs.getInt(2),
                                              rs.getInt(3), rs.getInt(4)));
                }
            }
        }

        if (withFullQuestions) {
            for (ExamQuestion eq : list) {
                // Note the exact version: this is what makes an old exam keep
                // showing the wording its students actually saw.
                eq.setQuestion(questionDAO.findByIdAndVersion(
                        eq.getQuestionId(), eq.getQuestionVersion()));
            }
        }
        return list;
    }

    private Exam readRow(ResultSet rs) throws SQLException {
        Exam exam = new Exam();
        exam.setExamId(rs.getString("exam_id"));
        exam.setVersion(rs.getInt("version"));
        exam.setCourseCode(rs.getString("course_code"));
        exam.setSubjectCode(rs.getString("subject_code"));
        exam.setCourseName(rs.getString("course_name"));
        exam.setDurationMinutes(rs.getInt("duration_minutes"));
        exam.setInstructionsForStudents(rs.getString("instructions_for_students"));
        exam.setNotesForTeacher(rs.getString("notes_for_teacher"));
        exam.setAuthorId(rs.getString("author_id"));
        exam.setAuthorName(rs.getString("author_name"));
        exam.setStatus(ExamStatus.valueOf(rs.getString("status")));
        exam.setRejectionReason(rs.getString("rejection_reason"));
        exam.setApprovedBy(rs.getString("approved_by"));
        Timestamp approved = rs.getTimestamp("approved_at");
        exam.setApprovedAt(approved == null ? null : approved.toLocalDateTime());
        exam.setCurrent(rs.getBoolean("is_current"));
        exam.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return exam;
    }
}
