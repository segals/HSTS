package hsts.common.protocol;

import java.io.Serializable;

/**
 * A message the server sends to a client on its own initiative.
 *
 * <p>Deliberately a different class from {@link Response}. A {@code Response}
 * answers a question the client asked and carries the {@code requestId} of that
 * question; a {@code PushEvent} answers nothing. Keeping them apart means a
 * screen waiting for its own reply can never mistake an unrelated announcement
 * for the answer it was expecting - which, with one shared class, would be a
 * genuinely nasty intermittent bug.</p>
 */
public class PushEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final PushType type;
    private final Object payload;

    /** Ready to show the user as it is. */
    private final String message;

    public PushEvent(PushType type, Object payload, String message) {
        this.type = type;
        this.payload = payload;
        this.message = message;
    }

    public PushType getType()   { return type; }
    public Object getPayload()  { return payload; }
    public String getMessage()  { return message; }

    @Override
    public String toString() {
        return "PushEvent[" + type + ": " + message + "]";
    }
}
