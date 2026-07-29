package hsts.server.push;

import hsts.common.protocol.PushEvent;
import ocsf.server.ConnectionToClient;

import java.io.IOException;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Sends messages to clients that did not ask for them.
 *
 * <p>This is the piece that lets the system meet NFR 18 - no manual screen
 * refresh. Something changes on the server, and the people affected are told
 * straight away rather than finding out the next time they happen to look.</p>
 *
 * <h2>Delivery is best-effort, on purpose</h2>
 *
 * <p>A push is a courtesy, never the source of truth. If the recipient is not
 * signed in, or her connection died a moment ago, the event is dropped and
 * nothing else is affected - the decision is already committed to the database,
 * and she will see it the moment she next opens the screen.</p>
 *
 * <p>That is why nothing here throws. A failed push must never break the
 * operation that caused it: a coordinator's rejection has to succeed whether or
 * not the teacher happens to be online to hear about it.</p>
 */
public class PushService {

    private final SessionRegistry sessions;

    /** Where to report delivery problems. Wired to the server console. */
    private Consumer<String> logSink = message -> { };

    public PushService(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = (sink == null) ? message -> { } : sink;
    }

    /**
     * Sends an event to one user, if she is signed in.
     *
     * @return true if it was delivered; false if she is not connected.
     */
    public boolean toUsername(String username, PushEvent event) {
        ConnectionToClient connection = sessions.getConnection(username);
        if (connection == null) {
            return false;   // not signed in - nothing to do, and nothing wrong
        }
        try {
            connection.sendToClient(event);
            return true;
        } catch (IOException e) {
            logSink.accept("Push to " + username + " failed: " + e.getMessage());
            return false;
        }
    }

    /** Sends to every signed-in user in the collection. Returns how many got it. */
    public int toUsernames(Collection<String> usernames, PushEvent event) {
        int delivered = 0;
        for (String username : usernames) {
            if (toUsername(username, event)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Sends to everyone currently signed in. */
    public int toEveryone(PushEvent event) {
        return toUsernames(sessions.getLoggedInUsernames(), event);
    }
}
