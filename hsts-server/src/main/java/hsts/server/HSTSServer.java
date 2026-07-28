package hsts.server;

import hsts.common.protocol.Credentials;
import hsts.common.protocol.Request;
import hsts.common.protocol.Response;
import hsts.server.dao.DBController;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The HSTS server: the one place every client message arrives.
 *
 * <p><b>Singleton</b> - the second of the two required by the submitted class
 * diagram. There must be exactly one listening socket and one registry of
 * connected users.</p>
 *
 * <p>It extends OCSF's {@link AbstractServer}, which does the socket work and
 * calls {@link #handleMessageFromClient} once per incoming message, on that
 * client's own thread.</p>
 *
 * <p>In the finished system this class stays thin: it decides which controller
 * should deal with a request and does nothing else. Milestone 1 answers the two
 * skeleton requests inline, because no controllers exist yet.</p>
 */
public class HSTSServer extends AbstractServer {

    public static final int DEFAULT_PORT = 5555;

    private static HSTSServer instance;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Where log lines go. The console screen replaces this to show them on screen. */
    private Consumer<String> logSink = System.out::println;

    private HSTSServer(int port) {
        super(port);
    }

    public static synchronized HSTSServer getInstance() {
        if (instance == null) {
            instance = new HSTSServer(DEFAULT_PORT);
        }
        return instance;
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = sink;
    }

    private void log(String text) {
        logSink.accept("[" + LocalTime.now().format(CLOCK) + "] " + text);
    }

    // -----------------------------------------------------------------
    //  Message handling
    // -----------------------------------------------------------------

    /**
     * Called by OCSF for every message from every client.
     *
     * <p>Note the try/catch around everything. If this method throws, OCSF drops
     * that client's connection with no explanation, which during a live demo
     * looks exactly like a network fault. Catching here means the client always
     * gets an answer it can display, even when something has gone wrong.</p>
     */
    @Override
    protected void handleMessageFromClient(Object msg, ConnectionToClient client) {

        if (!(msg instanceof Request request)) {
            log("Ignored a message that was not a Request: "
                + (msg == null ? "null" : msg.getClass().getName()));
            sendSafely(client, Response.error("Unrecognised message type."));
            return;
        }

        log("Request " + request.getType() + " from " + client);

        Response response;
        try {
            response = switch (request.getType()) {
                case PING  -> handlePing();
                case LOGIN -> handleLogin(request);
            };
        } catch (Exception e) {
            log("FAILED to handle " + request.getType() + ": " + e);
            response = Response.error("Server error: " + e.getMessage());
        }

        // Stamp the reply with the id of the request it answers, so the screen
        // that asked can recognise its own answer. Done once here rather than in
        // every handler, so no handler can forget it.
        response = new Response(response.getType(), response.getPayload(),
                                response.getMessage(), request.getRequestId());

        sendSafely(client, response);
    }

    /** Walking-skeleton probe: proves the server can reach MySQL and answer. */
    private Response handlePing() throws Exception {
        DBController db = DBController.getInstance();
        String payload = "MySQL " + db.getServerVersion() + "  |  " + db.readSkeletonRow();
        return Response.ok(payload, "Round trip complete: client, server, database, client.");
    }

    /** Milestone 1 login: proves the salted hash comparison works against a real row. */
    private Response handleLogin(Request request) throws Exception {
        if (!(request.getPayload() instanceof Credentials credentials)) {
            return Response.error("Login request carried no credentials.");
        }

        String fullName = DBController.getInstance()
                .checkSkeletonLogin(credentials.getUsername(), credentials.getPassword());

        if (fullName == null) {
            // Deliberately does not say which of the two was wrong.
            return Response.error("Incorrect username or password.");
        }
        return Response.ok(fullName, "Welcome, " + fullName + ".");
    }

    private void sendSafely(ConnectionToClient client, Response response) {
        try {
            client.sendToClient(response);
        } catch (IOException e) {
            log("Could not reply to " + client + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  OCSF lifecycle hooks
    // -----------------------------------------------------------------

    @Override
    protected void clientConnected(ConnectionToClient client) {
        log("Client connected: " + client + "  (total " + getNumberOfClients() + ")");
    }

    @Override
    protected synchronized void clientDisconnected(ConnectionToClient client) {
        log("Client disconnected: " + client);
    }

    @Override
    protected synchronized void clientException(ConnectionToClient client, Throwable exception) {
        // A client closing its window arrives here as an EOFException. That is
        // normal, not an error, so it is logged quietly.
        log("Client dropped: " + client + " (" + exception.getClass().getSimpleName() + ")");
    }

    @Override
    protected void serverStarted() {
        log("Listening on port " + getPort());
    }

    @Override
    protected void serverStopped() {
        log("Stopped listening.");
    }

    /** Stops listening and closes every client connection. Safe to call twice. */
    public void shutdown() {
        try {
            if (isListening()) {
                close();
            }
        } catch (IOException e) {
            log("Error during shutdown: " + e.getMessage());
        }
    }
}
