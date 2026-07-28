package hsts.server;

import hsts.common.entity.Question;
import hsts.common.entity.User;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.Request;
import hsts.common.protocol.Response;
import hsts.server.boundary.IUserManagementSystem;
import hsts.server.boundary.LocalUserManagementAdapter;
import hsts.server.control.LoginController;
import hsts.server.control.QuestionController;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.DBController;
import hsts.server.dao.QuestionDAO;
import hsts.server.dao.UserDAO;
import hsts.server.push.SessionRegistry;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The HSTS server: the one place every client message arrives.
 *
 * <p><b>Singleton</b> - one of the two required by the submitted class diagram.
 * There must be exactly one listening socket and one registry of who is
 * connected.</p>
 *
 * <p>It extends OCSF's {@link AbstractServer}, which owns the sockets and calls
 * {@link #handleMessageFromClient} once per message, on that client's own thread.</p>
 *
 * <p>This class stays deliberately thin. It decides <em>which controller</em>
 * should deal with a request and does nothing else - no SQL, no business rules.
 * Everything real happens in the Application tier behind it.</p>
 */
public class HSTSServer extends AbstractServer {

    public static final int DEFAULT_PORT = 5555;

    private static HSTSServer instance;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Consumer<String> logSink = System.out::println;

    // ---- Application tier ----
    private final SessionRegistry sessions = new SessionRegistry();
    private final UserDAO userDAO = new UserDAO();
    private final QuestionDAO questionDAO = new QuestionDAO();
    private final CourseDAO courseDAO = new CourseDAO();
    private final IUserManagementSystem userManagement = new LocalUserManagementAdapter(userDAO);
    private final LoginController loginController = new LoginController(userManagement, sessions);
    private final QuestionController questionController =
            new QuestionController(questionDAO, courseDAO);

    private HSTSServer(int port) {
        super(port);
    }

    public static synchronized HSTSServer getInstance() {
        if (instance == null) {
            instance = new HSTSServer(DEFAULT_PORT);
        }
        return instance;
    }

    public SessionRegistry getSessions() {
        return sessions;
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
     * <p>Everything is wrapped in a try/catch. If this method throws, OCSF drops
     * that client's connection with no explanation - which during a live demo
     * looks exactly like a network fault. Catching here means the client always
     * receives something it can display, even when something has gone wrong.</p>
     */
    @Override
    protected void handleMessageFromClient(Object msg, ConnectionToClient client) {

        if (!(msg instanceof Request request)) {
            log("Ignored a message that was not a Request: "
                + (msg == null ? "null" : msg.getClass().getName()));
            sendSafely(client, Response.error("Unrecognised message type."));
            return;
        }

        // Any message counts as activity, which is what the inactivity timeout
        // in requirement 76 will measure against.
        sessions.touch(client, System.currentTimeMillis());

        log("Request " + request.getType() + " from " + describe(client));

        Response response;
        try {
            response = switch (request.getType()) {
                case PING   -> handlePing();
                case LOGIN  -> handleLogin(request, client);
                case LOGOUT -> loginController.logout(client);

                // ---- SUC-2: question bank ----
                // Every one of these needs a signed-in user, and every one
                // re-checks that user's permission inside the controller. The
                // client's menu hides what a user may not do; that is a courtesy,
                // not a defence, because the client can send anything at all.
                case COURSE_LIST_MINE        -> withUser(client, u ->
                        questionController.listMyCourses(u));
                case QUESTION_LIST_BY_COURSE -> withUser(client, u ->
                        questionController.listByCourse(u, (String) request.getPayload()));
                case QUESTION_TOPICS         -> withUser(client, u ->
                        questionController.listTopics(u, (String) request.getPayload()));
                case QUESTION_GET            -> withUser(client, u -> {
                        QuestionRef ref = (QuestionRef) request.getPayload();
                        return questionController.getQuestion(u, ref.getQuestionId(), ref.getVersion());
                    });
                case QUESTION_VERSIONS       -> withUser(client, u -> {
                        QuestionRef ref = (QuestionRef) request.getPayload();
                        return questionController.listVersions(u, ref.getQuestionId());
                    });
                case QUESTION_ADD            -> withUser(client, u ->
                        questionController.addQuestion(u, (Question) request.getPayload()));
                case QUESTION_EDIT           -> withUser(client, u ->
                        questionController.editQuestion(u, (Question) request.getPayload()));
                case QUESTION_DELETE         -> withUser(client, u ->
                        questionController.deleteQuestion(u, (String) request.getPayload()));
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

    /**
     * Runs an action on behalf of the user signed in on this connection.
     *
     * <p>If nobody is signed in, the action never runs. Putting the check here
     * means no handler can forget it - and forgetting it once would expose that
     * one operation to anyone who could open a socket.</p>
     */
    private Response withUser(ConnectionToClient client, java.util.function.Function<User, Response> action) {
        User user = sessions.getUser(client);
        if (user == null) {
            return Response.error("You are not signed in. Log in and try again.");
        }
        return action.apply(user);
    }

    private Response handlePing() throws Exception {
        DBController db = DBController.getInstance();
        return Response.ok("MySQL " + db.getServerVersion(),
                           "Round trip complete: client, server, database, client.");
    }

    private Response handleLogin(Request request, ConnectionToClient client) {
        if (!(request.getPayload() instanceof Credentials credentials)) {
            return Response.error("Login request carried no credentials.");
        }
        Response response = loginController.authenticate(credentials, client);

        if (response.isOk() && response.getPayload() instanceof User user) {
            log("Logged in: " + user.getUsername() + " (" + user.getRole()
                + ") - " + sessions.getActiveCount() + " active session(s)");
        }
        return response;
    }

    private void sendSafely(ConnectionToClient client, Response response) {
        try {
            client.sendToClient(response);
        } catch (IOException e) {
            log("Could not reply to " + describe(client) + ": " + e.getMessage());
        }
    }

    /**
     * A readable name for a connection.
     *
     * <p>OCSF's own {@code toString()} reads the socket address, which is already
     * gone by the time the disconnect hook runs - it prints "null". Preferring
     * the logged-in username avoids that, and is more useful anyway.</p>
     */
    private String describe(ConnectionToClient client) {
        Object username = client.getInfo("username");
        if (username != null) {
            return String.valueOf(username);
        }
        String text = String.valueOf(client);
        return "null".equals(text) ? "a disconnected client" : text;
    }

    // -----------------------------------------------------------------
    //  OCSF lifecycle hooks
    // -----------------------------------------------------------------

    @Override
    protected void clientConnected(ConnectionToClient client) {
        log("Client connected: " + describe(client) + "  (total " + getNumberOfClients() + ")");
    }

    /**
     * A client went away.
     *
     * <p>This is what stops requirement 4 from becoming a trap. Without clearing
     * the session here, closing the client window would leave that user marked as
     * logged in forever, and she could never log in again.</p>
     */
    @Override
    protected synchronized void clientDisconnected(ConnectionToClient client) {
        String who = describe(client);
        loginController.handleDisconnect(client);
        log("Client disconnected: " + who + "  (" + sessions.getActiveCount() + " session(s) left)");
    }

    @Override
    protected synchronized void clientException(ConnectionToClient client, Throwable exception) {
        // Closing the client window arrives here as an EOFException. That is
        // normal, not a fault, so it is logged quietly - but the session must
        // still be released.
        String who = describe(client);
        loginController.handleDisconnect(client);
        log("Client dropped: " + who + " (" + exception.getClass().getSimpleName() + ")");
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
