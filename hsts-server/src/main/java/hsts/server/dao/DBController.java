package hsts.server.dao;

import java.sql.Connection;
import java.sql.DriverManager;
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

    /**
     * Builds the schema if needed, then seeds the test data if the database is empty.
     *
     * <p>Both steps are safe to run repeatedly, so restarting the server never
     * duplicates anything. Seeding only happens when the {@code users} table has
     * no rows, so real data is never overwritten by accident.</p>
     */
    public void initialiseSchema() throws SQLException {
        SchemaManager.createSchema(connection);
    }

    /** Counts rows in a table - used to decide whether seeding is needed, and by tests. */
    public int countRows(String table) throws SQLException {
        // The table name cannot be a bind parameter, so it is checked against a
        // strict pattern rather than interpolated blindly.
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("suspicious table name: " + table);
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
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
