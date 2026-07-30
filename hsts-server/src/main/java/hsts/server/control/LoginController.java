package hsts.server.control;

import hsts.common.entity.User;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.Response;
import hsts.server.boundary.IUserManagementSystem;
import hsts.server.dao.LoginAttemptDAO;
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
    private final LoginAttemptDAO loginAttempts;

    public LoginController(IUserManagementSystem userManagement, SessionRegistry sessions,
                           LoginAttemptDAO loginAttempts) {
        this.userManagement = userManagement;
        this.sessions = sessions;
        this.loginAttempts = loginAttempts;
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

        // The five-strikes lock, before the password is even looked at. Checking it
        // afterwards would let somebody keep guessing while locked out, which is
        // the one thing the lock exists to stop.
        try {
            java.time.Duration locked = loginAttempts.remainingLock(username);
            if (locked != null) {
                return Response.error("Too many failed sign-ins. This account is "
                        + "locked for another " + describe(locked) + ".");
            }
        } catch (java.sql.SQLException e) {
            // Never fail OPEN. If the lock cannot be read, nobody signs in - the
            // alternative is that a database fault silently removes the lock.
            return Response.error("Could not check the sign-in attempts for this "
                    + "account. Please try again shortly.");
        }

        if (!userManagement.verifyCredentials(username, credentials.getPassword())) {
            // One message for both cases on purpose. Saying "no such user" would
            // let anyone discover which usernames exist just by trying them - and
            // for the same reason the count is kept against names that do not exist.
            return Response.error(countFailure(username, "Incorrect username or password."));
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

        // A good sign-in wipes the slate: the five are consecutive, so four slips
        // last week cannot combine with one today to lock somebody out.
        try {
            loginAttempts.clear(username);
        } catch (java.sql.SQLException e) {
            // She is in; a stale counter is not worth refusing her for. It clears
            // itself the next time she signs in successfully.
            System.err.println("Could not clear the sign-in attempts for "
                             + username + ": " + e.getMessage());
        }

        return Response.ok(user, "Welcome, " + user.getFullName() + ".");
    }

    /**
     * Counts a failed sign-in.
     *
     * <h2>Why this does NOT say how many tries are left</h2>
     *
     * <p>It did, and that was a mistake caught by the login suite. The count is kept
     * against the <b>typed username</b>, so a real account that has already
     * accumulated failures shows a different number from a name nobody has ever
     * used - and comparing those two messages tells an attacker which usernames are
     * real. That is precisely what the single shared refusal above exists to
     * prevent, and a countdown quietly undid it.</p>
     *
     * <p>So the wording is identical for a wrong password and an unknown user, every
     * time, until the account is actually locked. The lock message is safe: it
     * appears for a made-up username too, after five tries the attacker made
     * himself, and reveals nothing he did not already know.</p>
     *
     * <p>The cost is that somebody who has genuinely forgotten her password gets no
     * warning before the ten minutes. That is the smaller of the two harms, and the
     * lock message tells her exactly what happened when it arrives.</p>
     */
    private String countFailure(String username, String refusal) {
        try {
            int failures = loginAttempts.recordFailure(username);
            if (failures >= LoginAttemptDAO.STRIKES) {
                return refusal + " That was " + LoginAttemptDAO.STRIKES
                     + " failed attempts, so this account is locked for "
                     + LoginAttemptDAO.LOCK_FOR.toMinutes() + " minutes.";
            }
            return refusal;
        } catch (java.sql.SQLException e) {
            System.err.println("Could not record a failed sign-in: " + e.getMessage());
            return refusal;
        }
    }

    /** "10 minutes", "1 minute", "under a minute" - never "PT9M59S". */
    private static String describe(java.time.Duration left) {
        long minutes = left.toMinutes();
        if (minutes < 1) {
            return "under a minute";
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
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
