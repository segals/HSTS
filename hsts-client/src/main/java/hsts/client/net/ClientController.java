package hsts.client.net;

import hsts.common.entity.User;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.Request;
import hsts.common.protocol.Response;
import javafx.application.Platform;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Sits between the screens and {@link HSTSClient}.
 *
 * <p>Screens never touch the socket. They call this class to send, and register
 * a handler to be told about replies. That is the mediator role the submitted
 * class diagram gives it, and it keeps every screen free of networking code.</p>
 *
 * <h2>The threading rule this class enforces</h2>
 *
 * <p>Replies arrive on an OCSF background thread. JavaFX forbids any thread but
 * its own from touching a control. Rather than trusting every screen to remember
 * that, this class wraps every callback in {@code Platform.runLater} <em>once,
 * here</em>. Screens can then treat replies as ordinary code.</p>
 *
 * <p>Getting this wrong does not fail cleanly - it works most of the time and
 * then throws at random, which is a horrible thing to debug during a demo. So it
 * is handled in one place from the very first message.</p>
 */
public class ClientController {

    private static ClientController instance;

    private HSTSClient client;
    private Consumer<Response> responseHandler = response -> { };
    private Consumer<String>   connectionLostHandler = message -> { };

    /**
     * Where unsolicited server messages go.
     *
     * <p>Separate from {@link #responseHandler} on purpose. A reply answers a
     * question this client asked; a push answers nothing and can arrive at any
     * moment, including while a screen is waiting for something else. Routing
     * them through one handler would let an announcement be mistaken for the
     * reply a screen was waiting for.</p>
     */
    private Consumer<PushEvent> pushHandler = event -> { };

    /**
     * Who is signed in on this client.
     *
     * <p>Held only so the screens know which menu to draw and whose name to
     * show. It is <b>not</b> a permission check: the server decides what this
     * user may do, on every single request. If the client were trusted, editing
     * this field would be enough to become the principal.</p>
     */
    private User currentUser;

    private ClientController() {
    }

    public static synchronized ClientController getInstance() {
        if (instance == null) {
            instance = new ClientController();
        }
        return instance;
    }

    /** Opens the connection. Throws if the server is not reachable. */
    public void connect(String host, int port) throws IOException {
        disconnect();
        client = new HSTSClient(host, port, this::dispatch, this::dispatchConnectionLost);
        client.openConnection();
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public void disconnect() {
        if (client != null) {
            try {
                client.closeConnection();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
            client = null;
        }
    }

    public void send(Request request) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to the server.");
        }
        client.sendToServer(request);
    }

    /** The current screen registers here to receive replies. */
    public void setResponseHandler(Consumer<Response> handler) {
        this.responseHandler = (handler == null) ? response -> { } : handler;
    }

    public void setConnectionLostHandler(Consumer<String> handler) {
        this.connectionLostHandler = (handler == null) ? message -> { } : handler;
    }

    public String describeConnection() {
        return isConnected() ? client.getHost() + ":" + client.getPort() : "not connected";
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    public void clearCurrentUser() {
        this.currentUser = null;
    }

    // -----------------------------------------------------------------

    /** The current screen registers here to receive server announcements. */
    public void setPushHandler(Consumer<PushEvent> handler) {
        this.pushHandler = (handler == null) ? event -> { } : handler;
    }

    private void dispatch(Object message) {
        if (message instanceof Response response) {
            Platform.runLater(() -> responseHandler.accept(response));
        } else if (message instanceof PushEvent event) {
            Platform.runLater(() -> pushHandler.accept(event));
        } else {
            Platform.runLater(() -> responseHandler.accept(
                    Response.error("Unrecognised message from the server: "
                                   + (message == null ? "null" : message.getClass().getName()))));
        }
    }

    private void dispatchConnectionLost(String reason) {
        Platform.runLater(() -> connectionLostHandler.accept(reason));
    }
}
