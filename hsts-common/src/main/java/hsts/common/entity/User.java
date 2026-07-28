package hsts.common.entity;

import hsts.common.enums.UserRole;

import java.io.Serializable;

/**
 * Base class for everyone who can log in.
 *
 * <h2>What is deliberately NOT in this class</h2>
 *
 * <p>There is no password field, no hash and no salt. A {@code User} object is
 * sent to the client after a successful login, and anything on it travels across
 * the network. The stored hash and salt never leave the server - they are read
 * inside the database layer, compared there, and discarded.</p>
 *
 * <p>Leaving them out is not an oversight to be tidied up later; putting them
 * back would hand every client a copy of the credential material for whoever
 * logged in.</p>
 */
public abstract class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Israeli ID number (תעודת זהות). Also what a student types before an exam. */
    protected String userId;

    protected String username;
    protected String fullName;
    protected UserRole role;

    protected User() {
        // for frameworks and subclasses
    }

    protected User(String userId, String username, String fullName, UserRole role) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.role = role;
    }

    public String getUserId()   { return userId; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public UserRole getRole()   { return role; }

    public void setUserId(String userId)     { this.userId = userId; }
    public void setUsername(String username) { this.username = username; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setRole(UserRole role)       { this.role = role; }

    /**
     * Whether this user may perform an action.
     *
     * <p>Kept from the submitted class diagram. Each subclass answers for itself,
     * which is why the check is a method rather than a pile of {@code if (role ==
     * ...)} tests scattered through the controllers.</p>
     *
     * <p>Permission is always re-checked on the <em>server</em>. The client hides
     * buttons a user may not use, but hiding a button is a courtesy, not
     * security - a client is just a program, and the server must never trust it.</p>
     */
    public abstract boolean checkPermission(String action);

    @Override
    public String toString() {
        return fullName + " (" + role.getDisplayName() + ")";
    }
}
