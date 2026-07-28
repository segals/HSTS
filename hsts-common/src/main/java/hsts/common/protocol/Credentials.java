package hsts.common.protocol;

import java.io.Serializable;

/**
 * The payload of a {@link RequestType#LOGIN} request.
 *
 * <p>The password travels as typed. It is hashed on the <em>server</em>, because
 * that is where the salt lives - the client is never told a user's salt, and
 * never sees a stored hash.</p>
 */
public class Credentials implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final String password;

    public Credentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        // Deliberately never prints the password - this object ends up in log lines.
        return "Credentials[" + username + "]";
    }
}
