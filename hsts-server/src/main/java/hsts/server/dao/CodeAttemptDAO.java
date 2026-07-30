package hsts.server.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The three-strikes lock on entering an execution code (requirement 39).
 *
 * <p><i>"תלמידה מזינה את קוד הביצוע למערכת... לאחר שלושה נסיונות שגויים המבחן
 * נחסם ל-10 דק"</i> - after three wrong codes, she is locked out for ten minutes.</p>
 *
 * <h2>Why this is in the database and not in memory</h2>
 *
 * <p>A counter held in the server's memory is cleared by a restart, and a lockout
 * that a student can end by waiting for someone to restart the server is not a
 * lockout. It also has to survive her closing the client and opening it again,
 * which an in-memory map keyed by connection would not.</p>
 *
 * <p>Per <b>student</b>, not per sitting. She has one identity and the requirement
 * blocks her, not one particular attempt - otherwise three wrong guesses at each of
 * four sittings would cost her nothing.</p>
 */
public class CodeAttemptDAO {

    /** Requirement 39: three. */
    public static final int STRIKES = 3;

    /** Requirement 39: ten minutes. */
    public static final Duration LOCK_FOR = Duration.ofMinutes(10);

    private java.sql.Connection conn() {
        return DBController.getInstance().getConnection();
    }

    /**
     * How much longer she is locked out, or null if she is not.
     *
     * <p>Read from the stored moment rather than from a countdown, so it is the
     * <b>server's</b> clock that decides - the same principle as the exam timer.</p>
     */
    public Duration remainingLock(String studentId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT locked_until FROM code_attempt WHERE student_id = ?")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp until = rs.getTimestamp("locked_until");
                if (until == null) {
                    return null;
                }
                Duration left = Duration.between(LocalDateTime.now(),
                        until.toLocalDateTime());
                return left.isNegative() || left.isZero() ? null : left;
            }
        }
    }

    /**
     * Records a wrong code.
     *
     * @return how many wrong attempts she has now made in this run of three
     */
    public int recordFailure(String studentId) throws SQLException {
        String sql = """
            INSERT INTO code_attempt (student_id, fail_count, locked_until)
            VALUES (?, 1, NULL)
            ON DUPLICATE KEY UPDATE fail_count = fail_count + 1""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.executeUpdate();
        }

        int failures = failureCount(studentId);
        if (failures >= STRIKES) {
            lock(studentId);
        }
        return failures;
    }

    private int failureCount(String studentId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT fail_count FROM code_attempt WHERE student_id = ?")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Starts the ten minutes and resets the counter.
     *
     * <p>The counter goes back to zero with the lock, so that when the ten minutes
     * are up she gets a fresh three tries rather than being re-locked by her next
     * single mistake.</p>
     */
    private void lock(String studentId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE code_attempt SET fail_count = 0, locked_until = ? "
              + "WHERE student_id = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(
                    LocalDateTime.now().plus(LOCK_FOR).withNano(0)));
            ps.setString(2, studentId);
            ps.executeUpdate();
        }
    }

    /** A correct code wipes the slate: the three are consecutive, not cumulative. */
    public void clear(String studentId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM code_attempt WHERE student_id = ?")) {
            ps.setString(1, studentId);
            ps.executeUpdate();
        }
    }

    /** How many tries she has left before the lock. For the message on screen. */
    public int triesLeft(String studentId) throws SQLException {
        return Math.max(0, STRIKES - failureCount(studentId));
    }
}
