package hsts.common.protocol;

import java.io.Serializable;

/**
 * One message travelling from the server back to a client.
 *
 * <p>See {@link Request} for why the explicit {@code serialVersionUID} matters.</p>
 */
public class Response implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ResponseType type;
    private final Object payload;

    /** Text meant to be shown to the user as-is. */
    private final String message;

    /** Echoes the {@link Request#getRequestId()} this is answering. */
    private final String requestId;

    public Response(ResponseType type, Object payload, String message, String requestId) {
        this.type = type;
        this.payload = payload;
        this.message = message;
        this.requestId = requestId;
    }

    /** Convenience: a successful reply carrying data. */
    public static Response ok(Object payload, String message) {
        return new Response(ResponseType.OK, payload, message, null);
    }

    /** Convenience: a failure carrying text the user should read. */
    public static Response error(String message) {
        return new Response(ResponseType.ERROR, null, message, null);
    }

    public ResponseType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isOk() {
        return type == ResponseType.OK;
    }

    @Override
    public String toString() {
        return "Response[" + type + (message == null ? "" : ": " + message) + "]";
    }
}
