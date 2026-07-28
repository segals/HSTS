package hsts.server.control;

import hsts.common.entity.User;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.Response;
import hsts.server.boundary.IUserManagementSystem;
import hsts.server.push.SessionRegistry;
import ocsf.server.ConnectionToClient;

/**
 * SUC-1: logging in and out.
 *
 * <p>Requirements 2 (identify with a username and password), 4 (not logged in
 * twice at once) and 5 (details come from the user-management system).</p>
 *
 * <p>The controller does not touch the database or the network. It asks
 * {@link IUserManagementSystem} whether the credentials are good, asks
 * {@link SessionRegistry} whether that user is already logged in somewhere, and
 * returns a {@link Response}. That is what keeps it testable and what keeps the
 * three tiers apart.</p>
 */
public class LoginController {

    private final IUserManagementSystem userManagement;
    private final SessionRegistry sessions;

    public LoginController(IUserManagementSystem userManagement, SessionRegistry sessions) {
        this.userManagement = userManagement;
        this.sessions = sessions;
    }

    /**
     * Attempts a login for one client connection.
     *
     * <p>On success the {@link User} travels back to the client, which uses the
     * role to decide which menu to show. It carries no hash and no salt.</p>
     */
    public Response authenticate(Credentials credentials, ConnectionToClient connection) {

        if (credentials == null
                || isBlank(credentials.getUsername())
                || isBlank(credentials.getPassword())) {
            return Response.error("Enter both a username and a password.");
        }

        String username = credentials.getUsername().trim();

        if (!userManagement.verifyCredentials(username, credentials.getPassword())) {
            // One message for both cases on purpose. Saying "no such user" would
            // let anyone discover which usernames exist just by trying them.
            return Response.error("Incorrect username or password.");
        }

        User user = userManagement.getUserDetails(username);
        if (user == null) {
            // Credentials verified but details missing - the row changed underneath
            // us, or the database is inconsistent. Never fail open.
            return Response.error("Your user record could not be loaded. Contact the administrator.");
        }

        // Requirement 4: the same user may not be connected twice at once.
        if (!sessions.login(user, connection, System.currentTimeMillis())) {
            return Response.error(
                    "This user is already logged in on another computer. "
                  + "Log out there first, or close that program.");
        }

        // Handy on the server side: OCSF can tell us who a connection belongs to
        // without a lookup, which the log lines and later push code both use.
        connection.setInfo("username", user.getUsername());
        connection.setInfo("userId", user.getUserId());

        return Response.ok(user, "Welcome, " + user.getFullName() + ".");
    }

    /** Ends the session on this connection. Safe to call when not logged in. */
    public Response logout(ConnectionToClient connection) {
        User user = sessions.logout(connection);
        if (user == null) {
            return Response.ok(null, "Already logged out.");
        }
        connection.setInfo("username", null);
        connection.setInfo("userId", null);
        return Response.ok(null, "Goodbye, " + user.getFullName() + ".");
    }

    /** Called when a connection drops without a clean logout. */
    public void handleDisconnect(ConnectionToClient connection) {
        sessions.logout(connection);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
