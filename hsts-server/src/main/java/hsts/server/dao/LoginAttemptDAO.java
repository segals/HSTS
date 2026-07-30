package hsts.server.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The five-strikes lock on signing in.
 *
 * <p>Asked for by the customer, for <b>every</b> kind of user - teacher,
 * coordinator, student and principal alike. Five wrong sign-ins and the account
 * cannot be used for ten minutes.</p>
 *
 * <p>It is the same shape as {@link CodeAttemptDAO}, which locks a student out
 * after three wrong <em>execution codes</em> (requirement 39). The two are kept
 * apart on purpose: they count different things, they have different limits, and
 * being unable to sit an exam is a different problem from being unable to sign in.
 * Sharing one table would mean a student who mistyped a code three times could not
 * log in either.</p>
 *
 * <h2>Keyed by the typed username, not by a user id</h2>
 *
 * <p>A wrong password comes with a real username; a wrong <em>username</em> comes
 * with nothing at all. Counting by what was typed catches both, and it also stops
 * somebody working through a list of names three tries at a time.</p>
 *
 * <p>Stored in the database rather than in memory for the same reason as the code
 * lock: a lockout that ends when the server restarts is not a lockout, and it must
 * survive the client being closed and reopened.</p>
 */
public class LoginAttemptDAO {

    /** The customer's number: five. */
    public static final int STRIKES = 5;

    /** The customer's number: ten minutes. */
    public static final Duration LOCK_FOR = Duration.ofMinutes(10);

    private java.sql.Connection conn() {
        return DBController.getInstance().getConnection();
    }

    /** How much longer this username is locked out, or null if it is not. */
    public Duration remainingLock(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT locked_until FROM login_attempt WHERE username = ?")) {
            ps.setString(1, username.trim());
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
     * Records one failed sign-in.
     *
     * @return how many failures this username has now accumulated, or
     *         {@link #STRIKES} once it has been locked
     */
    public int recordFailure(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return 0;
        }
        String key = username.trim();

        String sql = """
            INSERT INTO login_attempt (username, fail_count, locked_until)
            VALUES (?, 1, NULL)
            ON DUPLICATE KEY UPDATE fail_count = fail_count + 1""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        }

        int failures = failureCount(key);
        if (failures >= STRIKES) {
            lock(key);
        }
        return failures;
    }

    private int failureCount(String username) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT fail_count FROM login_attempt WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Starts the ten minutes and resets the counter.
     *
     * <p>Zeroing the count with the lock means that when the ten minutes are up she
     * gets a fresh five, rather than being locked again by her next single slip.</p>
     */
    private void lock(String username) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE login_attempt SET fail_count = 0, locked_until = ? "
              + "WHERE username = ?")) {
            ps.setTimestamp(1, Timestamp.valueOf(
                    LocalDateTime.now().plus(LOCK_FOR).withNano(0)));
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    /** A successful sign-in wipes the slate: the five are consecutive. */
    public void clear(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM login_attempt WHERE username = ?")) {
            ps.setString(1, username.trim());
            ps.executeUpdate();
        }
    }

    /** How many tries are left before the lock, for the message on screen. */
    public int triesLeft(String username) throws SQLException {
        if (username == null || username.isBlank()) {
            return STRIKES;
        }
        return Math.max(0, STRIKES - failureCount(username.trim()));
    }
}
