package hsts.server.dao;

import hsts.common.entity.Principal;
import hsts.common.entity.Student;
import hsts.common.entity.Subject;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.enums.UserRole;
import hsts.common.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQL concerning users.
 *
 * <h2>The one rule this class exists to enforce</h2>
 *
 * <p>The password hash and salt are read, used, and dropped <em>inside this
 * class</em>. They are never placed on a {@link User} object, because a
 * {@code User} is sent to the client after login and everything on it crosses
 * the network.</p>
 */
public class UserDAO implements IDAO<User, String> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Authentication
    // -----------------------------------------------------------------

    /**
     * Checks a username and password against the stored salt and hash.
     *
     * <p>Note what happens when the username does not exist: the code still runs
     * a hash calculation before returning false. Skipping it would make a
     * missing username measurably faster to reject than a wrong password, and
     * that difference alone tells an attacker which usernames are real.</p>
     */
    public boolean verifyCredentials(String username, String password) throws SQLException {
        String sql = "SELECT password_hash, password_salt FROM users WHERE username = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Spend the same effort as a real check before failing.
                    PasswordHasher.hash(password, "0000000000000000000000000000000f");
                    return false;
                }
                return PasswordHasher.matches(password,
                        rs.getString("password_salt"), rs.getString("password_hash"));
            }
        }
    }

    // -----------------------------------------------------------------
    //  Reading users
    // -----------------------------------------------------------------

    public User findByUsername(String username) throws SQLException {
        String sql = """
            SELECT user_id, username, full_name, role, coordinated_subject
            FROM users WHERE username = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? buildUser(rs) : null;
            }
        }
    }

    @Override
    public User findById(String userId) throws SQLException {
        String sql = """
            SELECT user_id, username, full_name, role, coordinated_subject
            FROM users WHERE user_id = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? buildUser(rs) : null;
            }
        }
    }

    @Override
    public List<User> findAll() throws SQLException {
        String sql = """
            SELECT user_id, username, full_name, role, coordinated_subject
            FROM users ORDER BY role, username""";
        List<User> users = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                users.add(buildUser(rs));
            }
        }
        return users;
    }

    /**
     * Turns one row into the right subclass, and fills in that user's courses.
     *
     * <p>This is where the {@code role} column becomes real Java polymorphism -
     * a {@code COORDINATOR} row becomes a {@link SubjectCoordinator}, so
     * {@code checkPermission} behaves correctly without anyone testing the role
     * again later.</p>
     */
    private User buildUser(ResultSet rs) throws SQLException {
        String userId   = rs.getString("user_id");
        String username = rs.getString("username");
        String fullName = rs.getString("full_name");
        UserRole role   = UserRole.valueOf(rs.getString("role"));

        User user = switch (role) {
            case TEACHER -> {
                Teacher t = new Teacher(userId, username, fullName);
                t.setTaughtCourseCodes(findTaughtCourseCodes(userId));
                yield t;
            }
            case COORDINATOR -> {
                SubjectCoordinator c = new SubjectCoordinator(
                        userId, username, fullName, rs.getString("coordinated_subject"));
                c.setTaughtCourseCodes(findTaughtCourseCodes(userId));
                yield c;
            }
            case STUDENT -> {
                Student s = new Student(userId, username, fullName);
                s.setEnrolledCourseCodes(findEnrolledCourseCodes(userId));
                yield s;
            }
            case PRINCIPAL -> new Principal(userId, username, fullName);
        };
        return user;
    }

    public List<String> findTaughtCourseCodes(String userId) throws SQLException {
        return findCourseCodes("SELECT course_code FROM course_teacher WHERE user_id = ?", userId);
    }

    public List<String> findEnrolledCourseCodes(String userId) throws SQLException {
        return findCourseCodes("SELECT course_code FROM course_student WHERE user_id = ?", userId);
    }

    private List<String> findCourseCodes(String sql, String userId) throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
            }
        }
        return codes;
    }

    // -----------------------------------------------------------------
    //  Writing (used by the seeder only - HSTS never creates users itself)
    // -----------------------------------------------------------------

    /**
     * Inserts a user together with a freshly salted password hash.
     *
     * <p>Used only by the seeder. System description §8 puts user management in
     * an external system, so HSTS has no sign-up screen and no way for anyone to
     * reach this method through the GUI.</p>
     */
    public void insertWithPassword(User user, String plainPassword) throws SQLException {
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(plainPassword, salt);

        String sql = """
            INSERT INTO users (user_id, username, password_hash, password_salt,
                               full_name, role, coordinated_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, user.getUserId());
            ps.setString(2, user.getUsername());
            ps.setString(3, hash);
            ps.setString(4, salt);
            ps.setString(5, user.getFullName());
            ps.setString(6, user.getRole().name());
            ps.setString(7, (user instanceof SubjectCoordinator c)
                    ? c.getCoordinatedSubjectCode() : null);
            ps.executeUpdate();
        }
    }

    @Override
    public void insert(User user) {
        throw new UnsupportedOperationException(
                "Users are seeded with a password - use insertWithPassword(user, password).");
    }

    @Override
    public void update(User user) throws SQLException {
        String sql = "UPDATE users SET full_name = ? WHERE user_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, user.getFullName());
            ps.setString(2, user.getUserId());
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(String userId) {
        // Deleting a user would orphan her exams, grades and bot history.
        // System description §8 says an external system owns user records.
        throw new UnsupportedOperationException("HSTS does not delete users.");
    }

    // -----------------------------------------------------------------
    //  Reference data
    // -----------------------------------------------------------------

    public List<Subject> findAllSubjects() throws SQLException {
        List<Subject> subjects = new ArrayList<>();
        String sql = "SELECT subject_code, name FROM subject ORDER BY subject_code";
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                subjects.add(new Subject(rs.getString(1), rs.getString(2)));
            }
        }
        return subjects;
    }
}
