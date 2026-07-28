package hsts.server.push;

import hsts.common.entity.User;
import ocsf.server.ConnectionToClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is logged in, and on which connection.
 *
 * <p>Three separate requirements land on this one class:</p>
 * <ul>
 *   <li><b>Requirement 4</b> - the same user may not be logged in twice at once.</li>
 *   <li><b>NFR 18</b> - the server pushes updates, so it must know where to send them.</li>
 *   <li><b>Requirement 76</b> - inactivity logout needs a record of the last activity.</li>
 * </ul>
 *
 * <h2>Why this is in memory and not a database column</h2>
 *
 * <p>A {@code logged_in} column sounds simpler until the server is killed, or a
 * laptop lid is closed mid-session. The flag survives, the session does not, and
 * that user can never log in again without someone editing the database by hand -
 * which is exactly the sort of thing that happens ten minutes before a demo.</p>
 *
 * <p>Held in memory, the problem cannot occur: OCSF's {@code clientDisconnected}
 * hook clears the entry, and if the server restarts, everyone is logged out,
 * which is the truth anyway.</p>
 *
 * <p>{@code ConcurrentHashMap} because OCSF handles each client on its own
 * thread, so two people really can log in at the same instant.</p>
 */
public class SessionRegistry {

    /** One session per logged-in user. */
    public static class Session {
        private final User user;
        private final ConnectionToClient connection;
        private volatile long lastActivityMillis;

        Session(User user, ConnectionToClient connection, long now) {
            this.user = user;
            this.connection = connection;
            this.lastActivityMillis = now;
        }

        public User getUser()                    { return user; }
        public ConnectionToClient getConnection() { return connection; }
        public long getLastActivityMillis()      { return lastActivityMillis; }
        void touch(long now)                     { this.lastActivityMillis = now; }
    }

    private final Map<String, Session> byUsername = new ConcurrentHashMap<>();
    private final Map<ConnectionToClient, String> usernameByConnection = new ConcurrentHashMap<>();

    /**
     * Records a login.
     *
     * @return true if it was accepted; false when that user is already logged in
     *         somewhere else, which requirement 4 forbids.
     */
    public synchronized boolean login(User user, ConnectionToClient connection, long now) {
        if (byUsername.containsKey(user.getUsername())) {
            return false;
        }
        byUsername.put(user.getUsername(), new Session(user, connection, now));
        usernameByConnection.put(connection, user.getUsername());
        return true;
    }

    /** Clears a session by connection. Called on logout and on disconnect. */
    public synchronized User logout(ConnectionToClient connection) {
        String username = usernameByConnection.remove(connection);
        if (username == null) {
            return null;
        }
        Session session = byUsername.remove(username);
        return session == null ? null : session.getUser();
    }

    public boolean isLoggedIn(String username) {
        return byUsername.containsKey(username);
    }

    /** The user on this connection, or null if this connection has not logged in. */
    public User getUser(ConnectionToClient connection) {
        String username = usernameByConnection.get(connection);
        return username == null ? null : byUsername.get(username).getUser();
    }

    public ConnectionToClient getConnection(String username) {
        Session session = byUsername.get(username);
        return session == null ? null : session.getConnection();
    }

    /** Marks activity, so the inactivity timer measures idleness rather than uptime. */
    public void touch(ConnectionToClient connection, long now) {
        String username = usernameByConnection.get(connection);
        if (username != null) {
            Session session = byUsername.get(username);
            if (session != null) {
                session.touch(now);
            }
        }
    }

    public int getActiveCount() {
        return byUsername.size();
    }

    public Collection<Session> getAllSessions() {
        return new ArrayList<>(byUsername.values());
    }

    public List<String> getLoggedInUsernames() {
        return new ArrayList<>(byUsername.keySet());
    }
}
