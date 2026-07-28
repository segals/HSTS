package hsts.server.boundary;

import hsts.common.entity.User;
import hsts.server.dao.UserDAO;

import java.sql.SQLException;

/**
 * The implementation of {@link IUserManagementSystem} used in this project:
 * it reads our own {@code users} table.
 *
 * <p>System description §8 puts the user data in our database and gives the
 * external system only the job of maintaining it. So there is nothing to call
 * over a network - the "external system" writes into the same table we read.</p>
 *
 * <p>Consequences, all intended and all matching the plan:</p>
 * <ul>
 *   <li>Users are <b>seeded</b>, never registered. There is no sign-up screen
 *       and no user-administration screen anywhere in HSTS.</li>
 *   <li>Nothing leaves the school network to authenticate, so the demo works
 *       with no internet at all.</li>
 *   <li>Swapping in a real external service later means writing one new class
 *       that implements the interface.</li>
 * </ul>
 */
public class LocalUserManagementAdapter implements IUserManagementSystem {

    private final UserDAO userDAO;

    public LocalUserManagementAdapter(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    public boolean verifyCredentials(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        try {
            return userDAO.verifyCredentials(username, password);
        } catch (SQLException e) {
            // A database failure must never be reported as "wrong password".
            // Failing closed is right, but the real cause has to be visible.
            System.err.println("User lookup failed for '" + username + "': " + e.getMessage());
            return false;
        }
    }

    @Override
    public User getUserDetails(String username) {
        try {
            return userDAO.findByUsername(username);
        } catch (SQLException e) {
            System.err.println("User details failed for '" + username + "': " + e.getMessage());
            return null;
        }
    }
}
