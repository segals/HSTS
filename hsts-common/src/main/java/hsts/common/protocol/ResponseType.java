package hsts.common.protocol;

/**
 * The outcome of a request.
 *
 * <p>Deliberately only two values. Anything the user needs to read travels in
 * {@link Response#getMessage()}, which is how every "error message" required by
 * the acceptance tests reaches the screen.</p>
 */
public enum ResponseType {

    /** The request succeeded. Any data is in the payload. */
    OK,

    /** The request failed. The reason is in the message, ready to show the user. */
    ERROR
}
