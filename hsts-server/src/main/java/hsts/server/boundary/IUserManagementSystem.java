package hsts.server.boundary;

import hsts.common.entity.User;

/**
 * The boundary to the user-management system.
 *
 * <h2>Why this interface exists when there is nothing external to call</h2>
 *
 * <p>The source documents disagreed, and this interface is how the disagreement
 * was settled.</p>
 *
 * <ul>
 *   <li><b>System description §8</b> says every user detail, including
 *       permissions, is "זמינים במסד הנתונים של המערכת" - <em>available in our own
 *       database</em> - and that an external system merely manages it.</li>
 *   <li><b>Our submitted SUC-1</b> says authentication happens
 *       "מול מערכת ניהול המשתמשים החיצונית" - <em>against the external system</em>.</li>
 * </ul>
 *
 * <p>Both cannot be literally true: there is no external service to call, and
 * §8 says the data is local. Rather than pick one document and contradict the
 * other, the <em>shape</em> of the submitted design is kept - authentication goes
 * through this boundary interface - while the implementation reads our own
 * {@code users} table, as §8 requires.</p>
 *
 * <p>That is not a fudge. It is what a boundary interface is for: the login
 * logic depends on this contract and not on where the answer comes from. If the
 * school ever does provide a real service, one new implementing class replaces
 * {@link LocalUserManagementAdapter} and nothing else changes.</p>
 *
 * <p>This is also a Boundary class that lives on the server. A boundary faces
 * the edge of the system - and that includes the edge facing another system, not
 * only the edge facing a person.</p>
 */
public interface IUserManagementSystem {

    /** True when the username exists and the password matches. */
    boolean verifyCredentials(String username, String password);

    /** The user's details, or null if there is no such user. Never includes secrets. */
    User getUserDetails(String username);
}
