package hsts.common.protocol;

/**
 * Every kind of message a client can send to the server.
 *
 * <p>The whole client-server conversation goes through one envelope class
 * ({@link Request}) carrying one of these values. That keeps the server's
 * dispatch to a single readable switch statement, and means adding a feature
 * later costs one new enum value rather than a new class and a new handler.</p>
 *
 * <p>Values are added milestone by milestone, as the feature that needs them is
 * built. An unused value would be a promise the server does not keep.</p>
 */
public enum RequestType {

    // ---- infrastructure ----

    /** Health probe: proves client to server to database and back. */
    PING,

    // ---- SUC-1: login (milestone 2) ----

    /** Username and password. Payload is a {@link Credentials}. */
    LOGIN,

    /** Ends the session held by this connection. */
    LOGOUT
}
