package hsts.client.net;

import ocsf.client.AbstractClient;

import java.util.function.Consumer;

/**
 * The client's end of the OCSF connection.
 *
 * <p>Extends OCSF's {@link AbstractClient}, which owns the socket and calls
 * {@link #handleMessageFromServer} whenever something arrives - on its own
 * background thread, not on the JavaFX thread.</p>
 *
 * <p>This class deliberately knows nothing about screens or about what the
 * messages mean. It receives an object and hands it to whoever is listening.
 * Interpreting it is {@link ClientController}'s job. That separation is what
 * makes the <b>Observer</b> pattern work here: the screens observe, and the
 * server drives - which is how the system meets NFR 18, "no manual screen
 * refresh".</p>
 */
public class HSTSClient extends AbstractClient {

    private final Consumer<Object> onMessage;
    private final Consumer<String> onConnectionClosed;

    public HSTSClient(String host, int port,
                      Consumer<Object> onMessage,
                      Consumer<String> onConnectionClosed) {
        super(host, port);
        this.onMessage = onMessage;
        this.onConnectionClosed = onConnectionClosed;
    }

    @Override
    protected void handleMessageFromServer(Object msg) {
        onMessage.accept(msg);
    }

    /** Called by OCSF when the server goes away or the socket dies. */
    @Override
    protected void connectionClosed() {
        onConnectionClosed.accept("The connection to the server was closed.");
    }

    @Override
    protected void connectionException(Exception exception) {
        onConnectionClosed.accept("Connection lost: " + exception.getMessage());
    }
}
