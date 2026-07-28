package hsts.server.dao;

import hsts.common.entity.Course;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQL concerning courses.
 *
 * <p>Courses and subjects are managed by an external system (requirement 11), so
 * this DAO only reads. Refusing to write is not laziness - it is the requirement.</p>
 */
public class CourseDAO implements IDAO<Course, String> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    /** The courses one teacher teaches. Requirements 14 and 20 both depend on this. */
    public List<Course> findByTeacher(String userId) throws SQLException {
        String sql = """
            SELECT c.course_code, c.name, c.subject_code, s.name AS subject_name
            FROM course c
            JOIN subject s        ON s.subject_code = c.subject_code
            JOIN course_teacher ct ON ct.course_code = c.course_code
            WHERE ct.user_id = ?
            ORDER BY c.course_code""";
        return query(sql, userId);
    }

    /** The courses one student is enrolled in. Requirements 21 and 70. */
    public List<Course> findByStudent(String userId) throws SQLException {
        String sql = """
            SELECT c.course_code, c.name, c.subject_code, s.name AS subject_name
            FROM course c
            JOIN subject s        ON s.subject_code = c.subject_code
            JOIN course_student cs ON cs.course_code = c.course_code
            WHERE cs.user_id = ?
            ORDER BY c.course_code""";
        return query(sql, userId);
    }

    /** Every course in a subject - what a coordinator is responsible for. */
    public List<Course> findBySubject(String subjectCode) throws SQLException {
        String sql = """
            SELECT c.course_code, c.name, c.subject_code, s.name AS subject_name
            FROM course c
            JOIN subject s ON s.subject_code = c.subject_code
            WHERE c.subject_code = ?
            ORDER BY c.course_code""";
        return query(sql, subjectCode);
    }

    @Override
    public Course findById(String courseCode) throws SQLException {
        String sql = """
            SELECT c.course_code, c.name, c.subject_code, s.name AS subject_name
            FROM course c
            JOIN subject s ON s.subject_code = c.subject_code
            WHERE c.course_code = ?""";
        List<Course> found = query(sql, courseCode);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<Course> findAll() throws SQLException {
        String sql = """
            SELECT c.course_code, c.name, c.subject_code, s.name AS subject_name
            FROM course c
            JOIN subject s ON s.subject_code = c.subject_code
            ORDER BY c.subject_code, c.course_code""";
        List<Course> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(read(rs));
            }
        }
        return list;
    }

    private List<Course> query(String sql, String parameter) throws SQLException {
        List<Course> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(read(rs));
                }
            }
        }
        return list;
    }

    private Course read(ResultSet rs) throws SQLException {
        Course course = new Course(rs.getString("course_code"),
                                   rs.getString("name"),
                                   rs.getString("subject_code"));
        course.setSubjectName(rs.getString("subject_name"));
        return course;
    }

    // Courses come from an external system (requirement 11). HSTS never writes them.
    @Override public void insert(Course course) {
        throw new UnsupportedOperationException("Courses are managed externally.");
    }
    @Override public void update(Course course) {
        throw new UnsupportedOperationException("Courses are managed externally.");
    }
    @Override public void delete(String courseCode) {
        throw new UnsupportedOperationException("Courses are managed externally.");
    }
}
