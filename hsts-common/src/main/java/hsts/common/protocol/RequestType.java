package hsts.common.protocol;

/**
 * Every kind of message a client can send to the server.
 *
 * <p>The whole client-server conversation goes through one envelope class
 * ({@link Request}) carrying one of these values. That keeps the server's
 * dispatch to a single readable switch statement, and means adding a feature
 * later costs one new enum value rather than a new class and a new handler.</p>
 *
 * <p>Milestone 1 defines only the two values the walking skeleton needs.
 * The rest arrive with the milestone that uses them.</p>
 */
public enum RequestType {

    /** Walking-skeleton probe: proves client to server to database and back. */
    PING,

    /** Username and password check. Milestone 1 uses it to prove salted hashing. */
    LOGIN
}
