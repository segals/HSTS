package hsts.common.protocol;

import java.io.Serializable;

/**
 * One message travelling from a client to the server.
 *
 * <p>OCSF sends a bare {@code Object} down an {@code ObjectOutputStream}, so
 * everything we send has to be serializable and has to be understood identically
 * by both jars. Wrapping every message in this single envelope is what lets the
 * server keep one dispatch switch instead of one handler class per message.</p>
 *
 * <p><b>serialVersionUID is set explicitly and must not change.</b> If it were
 * left out, the compiler would compute one from the class's shape, and the two
 * jars would silently disagree the moment this file was edited - producing an
 * {@code InvalidClassException} on the first message, which is a miserable thing
 * to debug at a demo.</p>
 */
public class Request implements Serializable {

    private static final long serialVersionUID = 1L;

    private final RequestType type;
    private final Object payload;

    /** Lets a reply be matched back to the screen that asked for it. */
    private final String requestId;

    public Request(RequestType type, Object payload, String requestId) {
        this.type = type;
        this.payload = payload;
        this.requestId = requestId;
    }

    public Request(RequestType type, Object payload) {
        this(type, payload, null);
    }

    public Request(RequestType type) {
        this(type, null, null);
    }

    public RequestType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    public String getRequestId() {
        return requestId;
    }

    @Override
    public String toString() {
        return "Request[" + type + "]";
    }
}
