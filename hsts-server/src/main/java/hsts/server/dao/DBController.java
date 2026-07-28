package hsts.server.dao;

import hsts.common.util.PasswordHasher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The single point through which the server reaches MySQL.
 *
 * <p><b>Singleton</b> - one of the two required by the submitted class diagram.
 * One instance means one connection and therefore one source of truth; two would
 * mean two transaction contexts and a class of bug that is very hard to see.</p>
 *
 * <p>Only the server ever uses this class. The client has no database dependency
 * at all, which is what makes the three-tier split real rather than decorative,
 * and is why only the server laptop needs a database password.</p>
 */
public class DBController {

    private static DBController instance;

    private Connection connection;
    private String describedUrl;

    private DBController() {
        // private: use getInstance()
    }

    /**
     * Returns the one instance, creating it on first use.
     *
     * <p>Synchronized because OCSF handles each client on its own thread, so two
     * clients really can arrive here at the same moment.</p>
     */
    public static synchronized DBController getInstance() {
        if (instance == null) {
            instance = new DBController();
        }
        return instance;
    }

    /**
     * Opens the connection, creating the database itself if it is not there yet.
     *
     * <p>This runs in two steps on purpose. Connecting straight to a database
     * that does not exist fails, so it first connects to the server without
     * naming one, issues {@code CREATE DATABASE IF NOT EXISTS}, and only then
     * connects properly. That means a fresh laptop needs no manual SQL setup.</p>
     */
    public void connect(String host, int port, String database, String user, String password)
            throws SQLException {

        String base = "jdbc:mysql://" + host + ":" + port + "/";

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("useUnicode", "true");
        props.setProperty("characterEncoding", "UTF-8");

        // Time zone - do NOT change this to "SERVER" without reading this note.
        //
        // "SERVER" makes the driver ask MySQL which time zone it is in. On
        // Windows, MySQL answers with a Windows zone name such as
        // "Jerusalem Daylight Time". The driver only understands IANA names
        // like "Asia/Jerusalem", so it gives up with:
        //
        //   The server time zone value 'Jerusalem Daylight Time' is not
        //   recognized or represents more than one time zone
        //
        // ...which looks like a connection failure but happens *after* the
        // password has already been accepted.
        //
        // "LOCAL" tells the driver to use this JVM's own time zone and skip
        // asking the server entirely. That is correct here because MySQL always
        // runs on the same machine as the server program - the client never
        // touches the database, so there is no second machine to disagree with.
        props.setProperty("connectionTimeZone", "LOCAL");
        props.setProperty("forceConnectionTimeZoneToSession", "false");

        props.setProperty("allowPublicKeyRetrieval", "true");
        props.setProperty("useSSL", "false");

        // Step 1 - create the schema if this is a fresh machine.
        try (Connection boot = DriverManager.getConnection(base, props);
             Statement st = boot.createStatement()) {
            st.executeUpdate(
                "CREATE DATABASE IF NOT EXISTS `" + database + "` "
                + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        // Step 2 - connect to it for real.
        connection = DriverManager.getConnection(base + database, props);
        describedUrl = base + database;
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public String getDescribedUrl() {
        return describedUrl;
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing useful to do while shutting down.
            }
            connection = null;
        }
    }

    // =================================================================
    //  MILESTONE 1 ONLY - throwaway skeleton schema.
    //
    //  These two tables exist purely to prove that JDBC works end to end.
    //  Milestone 2 replaces them with the real schema from the plan
    //  (docs/01_implementation_plan.md section 2). Nothing else should
    //  ever depend on them.
    // =================================================================

    /** Creates the skeleton tables if absent and makes sure the test user exists. */
    public void ensureSkeletonSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS m1_skeleton (
                  id         INT PRIMARY KEY,
                  note       VARCHAR(200) NOT NULL,
                  created_at DATETIME     NOT NULL
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS m1_skeleton_user (
                  username      VARCHAR(50) PRIMARY KEY,
                  password_hash CHAR(64)    NOT NULL,
                  password_salt CHAR(32)    NOT NULL,
                  full_name     VARCHAR(100) NOT NULL
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                INSERT IGNORE INTO m1_skeleton (id, note, created_at)
                VALUES (1, 'HSTS walking skeleton row - written by the server on first start', NOW())""");
        }

        // Seed the one test user, hashed with a fresh random salt.
        // Password follows the documented convention: username + '!' + role initial.
        seedSkeletonUser("teacher1", "teacher1!T", "Test Teacher One");
    }

    private void seedSkeletonUser(String username, String plainPassword, String fullName)
            throws SQLException {

        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(plainPassword, salt);

        String sql = """
            INSERT INTO m1_skeleton_user (username, password_hash, password_salt, full_name)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE full_name = VALUES(full_name)""";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, fullName);
            ps.executeUpdate();
        }
    }

    /** Reads the skeleton row back - this is what the PING request returns. */
    public String readSkeletonRow() throws SQLException {
        String sql = "SELECT note, created_at FROM m1_skeleton WHERE id = 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getString("note") + "  (written " + rs.getTimestamp("created_at") + ")";
            }
            return "skeleton row missing";
        }
    }

    /**
     * Checks a username and password against the stored salt and hash.
     *
     * <p>The password is never stored and never compared directly - it is hashed
     * with that user's salt and the two hashes are compared.</p>
     */
    public String checkSkeletonLogin(String username, String password) throws SQLException {
        String sql = "SELECT password_hash, password_salt, full_name "
                   + "FROM m1_skeleton_user WHERE username = ?";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;   // no such user
                }
                boolean good = PasswordHasher.matches(
                        password, rs.getString("password_salt"), rs.getString("password_hash"));
                return good ? rs.getString("full_name") : null;
            }
        }
    }

    /** Server version string, shown on the console screen as proof of a real connection. */
    public String getServerVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            return rs.next() ? rs.getString(1) : "unknown";
        }
    }
}
