package hsts.server.push;

import ocsf.server.ConnectionToClient;

import java.io.IOException;

/**
 * The one way anything is written to a client.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Two threads write to the same connection all the time, and until this class
 * they did it without asking each other. A student sitting an exam is sent the
 * seconds remaining by the clock thread once a second, while her own requests are
 * answered on her connection's own thread. OCSF's {@code sendToClient} is:</p>
 *
 * <pre>    output.writeObject(msg);</pre>
 *
 * <p>with no synchronisation at all. Two calls can interleave inside
 * {@code ObjectOutputStream}, the bytes of one object end up inside the other, and
 * the stream is finished - the client cannot read past the damage and drops the
 * connection. What the user sees is a request that never comes back, in the middle
 * of an exam.</p>
 *
 * <h2>How it was found</h2>
 *
 * <p>As a flake: one run in ten of the badge suite failed with "no reply to
 * TAKE_START" while the server logged "connection was aborted". A fault that rare
 * is easy to blame on the network. {@code StreamRaceTest} makes it happen on
 * purpose instead - a girl answering as fast as she can for fourteen seconds while
 * the clock ticks at her - and before this class it broke the connection on
 * <b>every</b> run.</p>
 *
 * <h2>The lock</h2>
 *
 * <p>The connection object itself. Every writer in the system goes through here,
 * so they all take the same one, and a write can no longer begin while another is
 * half done. Holding a lock across a socket write is safe because these writes go
 * to a buffered stream on a local network and are measured in microseconds; the
 * alternative - a queue and a writer thread per client - is a great deal of
 * machinery for a school with thirty terminals.</p>
 */
public final class Transport {

    private Transport() {
    }

    /**
     * Writes one object to one client, waiting for any write already in progress.
     *
     * @throws IOException exactly as {@code sendToClient} does, so callers that
     *                     already handle a dropped connection keep working
     */
    public static void send(ConnectionToClient connection, Object message) throws IOException {
        if (connection == null) {
            return;
        }
        synchronized (connection) {
            connection.sendToClient(message);
        }
    }
}
