package hsts.server;

import hsts.common.entity.Exam;
import hsts.common.entity.Question;
import hsts.common.entity.User;
import hsts.common.protocol.CommentRequest;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.GradeChange;
import hsts.common.protocol.ExamBuildCriteria;
import hsts.common.protocol.ExamDecision;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.AnswerChoice;
import hsts.common.protocol.ExamReleaseRequest;
import hsts.common.protocol.StartExamRequest;
import hsts.common.protocol.TimeChangeRequest;
import hsts.common.protocol.PublishRequest;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.BotCreateRequest;
import hsts.common.protocol.BotQuestion;
import hsts.common.protocol.BotStatusRequest;
import hsts.common.protocol.ReportRequest;
import hsts.common.protocol.SourceRequest;
import hsts.common.protocol.ResultsQuery;
import hsts.common.enums.ReportType;
import hsts.server.boundary.GeminiStudyBotService;
import hsts.server.boundary.IStudyBotService;
import hsts.server.boundary.IUserManagementSystem;
import hsts.server.boundary.LocalUserManagementAdapter;
import hsts.server.control.ExamApprovalController;
import hsts.server.control.ExamBuilderController;
import hsts.server.control.ExamExecutionController;
import hsts.server.control.GradingController;
import hsts.server.control.LiveExamController;
import hsts.server.control.BotController;
import hsts.server.control.PrincipalController;
import hsts.server.control.ReportController;
import hsts.server.control.ResultsViewController;
import hsts.server.control.TeacherReportController;
import hsts.server.control.strategy.ReportFactory;
import hsts.server.control.TakeExamController;
import hsts.server.control.LoginController;
import hsts.server.control.QuestionController;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.DBController;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.BotDAO;
import hsts.server.dao.SubmissionDAO;
import hsts.server.dao.QuestionDAO;
import hsts.server.dao.UserDAO;
import hsts.server.push.ExamClockService;
import hsts.server.push.PushService;
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
    private final ExamDAO examDAO = new ExamDAO();
    private final PushService pushService = new PushService(sessions);
    private final QuestionController questionController =
            new QuestionController(questionDAO, courseDAO);
    private final ExamBuilderController examBuilderController =
            new ExamBuilderController(examDAO, questionDAO, courseDAO);
    private final ExamApprovalController examApprovalController =
            new ExamApprovalController(examDAO, userDAO, pushService);
    private final ExecutionDAO executionDAO = new ExecutionDAO();
    private final ExamExecutionController examExecutionController =
            new ExamExecutionController(executionDAO, examDAO);
    private final SubmissionDAO submissionDAO = new SubmissionDAO();
    private final TakeExamController takeExamController =
            new TakeExamController(executionDAO, submissionDAO, examDAO);
    private final ExamClockService examClock = new ExamClockService(submissionDAO, pushService);
    private final LiveExamController liveExamController =
            new LiveExamController(executionDAO, submissionDAO, userDAO, pushService);
    private final GradeDAO gradeDAO = new GradeDAO();
    private final GradingController gradingController = new GradingController(
            gradeDAO, submissionDAO, executionDAO, examDAO, userDAO, pushService);
    private final ResultsViewController resultsViewController =
            new ResultsViewController(gradeDAO, submissionDAO, executionDAO, examDAO);
    private final TeacherReportController teacherReportController =
            new TeacherReportController(examDAO, executionDAO, gradeDAO);
    private final PrincipalController principalController =
            new PrincipalController(questionDAO, examDAO, executionDAO, gradeDAO);
    private final ReportFactory reportFactory =
            new ReportFactory(examDAO, gradeDAO, userDAO, courseDAO);
    private final ReportController reportController = new ReportController(reportFactory);
    private final BotDAO botDAO = new BotDAO();

    /**
     * Requirement 69: the external answering service, behind a boundary interface.
     *
     * <p>Swappable so the automated suites can run every bot rule without the
     * network or an API key - see {@link IStudyBotService}.</p>
     */
    private IStudyBotService botService = new GeminiStudyBotService();
    private BotController botController = new BotController(
            botDAO, courseDAO, questionDAO, submissionDAO, userDAO, botService, pushService);

    /** Used by the tests to put a stub in place of the real Gemini call. */
    public void setStudyBotService(IStudyBotService service) {
        this.botService = service;
        this.botController = new BotController(
                botDAO, courseDAO, questionDAO, submissionDAO, userDAO, service, pushService);
    }

    public IStudyBotService getStudyBotService() {
        return botService;
    }

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
        // Push failures are quiet by design - they must never break the operation
        // that caused them - but they should still be visible on the console.
        pushService.setLogSink(this::log);
        examClock.setLogSink(this::log);
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

                // ---- SUC-3 / SUC-4: building exams ----
                case EXAM_BUILD_DRAFT -> withUser(client, u ->
                        examBuilderController.buildDraft(u, (ExamBuildCriteria) request.getPayload()));
                case EXAM_SAVE        -> withUser(client, u ->
                        examBuilderController.saveExam(u, (Exam) request.getPayload()));
                case EXAM_EDIT        -> withUser(client, u ->
                        examBuilderController.editExam(u, (Exam) request.getPayload()));
                case EXAM_LIST_MINE   -> withUser(client, u ->
                        examBuilderController.listMyExams(u));
                case EXAM_GET         -> withUser(client, u -> {
                        ExamRef ref = (ExamRef) request.getPayload();
                        return examBuilderController.getExam(u, ref.getExamId(), ref.getVersion());
                    });
                case EXAM_VERSIONS    -> withUser(client, u -> {
                        ExamRef ref = (ExamRef) request.getPayload();
                        return examBuilderController.listVersions(u, ref.getExamId());
                    });

                // ---- SUC-5: approving exams ----
                case EXAM_PENDING_FOR_COORDINATOR -> withUser(client, u ->
                        examApprovalController.listPending(u));
                case EXAM_APPROVE -> withUser(client, u ->
                        examApprovalController.approve(u, (ExamDecision) request.getPayload()));
                case EXAM_REJECT  -> withUser(client, u ->
                        examApprovalController.reject(u, (ExamDecision) request.getPayload()));

                // ---- SUC-6: releasing an exam from the drawer ----
                case EXECUTION_RELEASABLE_EXAMS -> withUser(client, u ->
                        examExecutionController.listReleasable(u));
                case EXECUTION_SUGGEST_CODE     -> withUser(client, u ->
                        examExecutionController.suggestCode(u));
                case EXECUTION_RELEASE          -> withUser(client, u ->
                        examExecutionController.release(u,
                                (ExamReleaseRequest) request.getPayload()));
                case EXECUTION_LIST_MINE        -> withUser(client, u ->
                        examExecutionController.listMyExecutions(u));

                // ---- SUC-7: sitting an exam ----
                case TAKE_VALIDATE_CODE -> withUser(client, u ->
                        takeExamController.validateCode(u, (String) request.getPayload()));
                case TAKE_START         -> withUser(client, u ->
                        takeExamController.startExam(u, (StartExamRequest) request.getPayload()));
                case TAKE_SAVE_ANSWER   -> withUser(client, u ->
                        takeExamController.saveAnswer(u, (AnswerChoice) request.getPayload()));
                case TAKE_SUBMIT        -> withUser(client, u ->
                        takeExamController.submitExam(u, (Integer) request.getPayload()));
                case TAKE_RESUME        -> withUser(client, u ->
                        takeExamController.resumeInProgress(u, (Integer) request.getPayload()));

                // ---- SUC-8: managing a sitting while it runs ----
                case LIVE_RUNNING_NOW -> withUser(client, u ->
                        liveExamController.listRunningNow(u));
                case LIVE_STATUS      -> withUser(client, u ->
                        liveExamController.getLiveStatus(u, (Integer) request.getPayload()));
                case LIVE_CHANGE_TIME -> withUser(client, u ->
                        liveExamController.changeTime(u, (TimeChangeRequest) request.getPayload()));

                // ---- SUC-9: marking ----
                case GRADING_SITTINGS -> withUser(client, u ->
                        gradingController.listSittingsToMark(u));
                case GRADING_LIST     -> withUser(client, u ->
                        gradingController.listGrades(u, (Integer) request.getPayload()));
                case GRADING_GET      -> withUser(client, u ->
                        gradingController.getMarkedExam(u, (Integer) request.getPayload()));
                case GRADING_CHANGE   -> withUser(client, u -> {
                        GradeChange c = (GradeChange) request.getPayload();
                        return gradingController.changeGrade(u, c.getSubmissionId(),
                                c.getValue(), c.getExplanation());
                    });
                case GRADING_QUESTION_COMMENT -> withUser(client, u -> {
                        CommentRequest c = (CommentRequest) request.getPayload();
                        return gradingController.addQuestionComment(u, c.getSubmissionId(),
                                c.getQuestionId(), c.getQuestionVersion(), c.getComment());
                    });
                case GRADING_GENERAL_COMMENT -> withUser(client, u -> {
                        CommentRequest c = (CommentRequest) request.getPayload();
                        return gradingController.addGeneralComment(u, c.getSubmissionId(),
                                c.getComment());
                    });
                case GRADING_APPROVE     -> withUser(client, u ->
                        gradingController.approve(u, (Integer) request.getPayload()));
                case GRADING_PUBLISH     -> withUser(client, u ->
                        gradingController.publish(u, (PublishRequest) request.getPayload()));
                case GRADING_APPROVE_ALL -> withUser(client, u ->
                        gradingController.approveAll(u, (Integer) request.getPayload()));
                case GRADING_FACTOR      -> withUser(client, u -> {
                        GradeChange c = (GradeChange) request.getPayload();
                        return gradingController.applyFactor(u, c.getExecutionId(), c.getValue());
                    });
                case GRADING_STATISTICS  -> withUser(client, u ->
                        gradingController.getStatistics(u, (Integer) request.getPayload()));

                // ---- SUC-10: a student reading her results ----
                case RESULTS_MINE        -> withUser(client, u ->
                        resultsViewController.listMyResults(u));
                case RESULTS_MARKED_EXAM -> withUser(client, u ->
                        resultsViewController.getMyMarkedExam(u, (Integer) request.getPayload()));

                // ---- SUC-11: a teacher's results and histogram ----
                case TEACHER_REPORT_EXAMS    -> withUser(client, u ->
                        teacherReportController.listMyExams(u));
                case TEACHER_REPORT_SITTINGS -> withUser(client, u ->
                        teacherReportController.listSittings(u, (String) request.getPayload()));
                case TEACHER_REPORT_RESULTS  -> withUser(client, u ->
                        teacherReportController.getResults(u,
                                (ResultsQuery) request.getPayload()));

                // ---- SUC-12: the principal's read-only browse ----
                case PRINCIPAL_QUESTIONS -> withUser(client, u ->
                        principalController.listQuestions(u));
                case PRINCIPAL_QUESTION_GET -> withUser(client, u ->
                        principalController.getQuestion(u,
                                (QuestionRef) request.getPayload()));
                case PRINCIPAL_EXAMS     -> withUser(client, u ->
                        principalController.listExams(u));
                case PRINCIPAL_EXAM_GET  -> withUser(client, u ->
                        principalController.getExam(u, (ExamRef) request.getPayload()));
                case PRINCIPAL_SITTINGS  -> withUser(client, u ->
                        principalController.listSittings(u, (String) request.getPayload()));
                case PRINCIPAL_RESULTS   -> withUser(client, u ->
                        principalController.getResults(u, (ResultsQuery) request.getPayload()));

                // ---- SUC-11 / SUC-12: statistical reports, via Factory + Strategy ----
                case REPORT_TYPES    -> withUser(client, u ->
                        reportController.listTypes(u));
                case REPORT_SUBJECTS -> withUser(client, u ->
                        reportController.listSubjects(u, (ReportType) request.getPayload()));
                case REPORT_GENERATE -> withUser(client, u ->
                        reportController.generate(u, (ReportRequest) request.getPayload()));

                // ---- SUC-13 / SUC-14 / SUC-15: the course study bot ----
                case BOT_LIST_MINE     -> withUser(client, u ->
                        botController.listMyBots(u));
                case BOT_COURSES_FREE  -> withUser(client, u ->
                        botController.listCoursesWithoutBot(u));
                case BOT_CREATE        -> withUser(client, u -> {
                        BotCreateRequest c = (BotCreateRequest) request.getPayload();
                        return botController.createBot(u, c.getCourseCode(), c.getName());
                    });
                case BOT_SET_STATUS    -> withUser(client, u -> {
                        BotStatusRequest c = (BotStatusRequest) request.getPayload();
                        return botController.setStatus(u, c.getBotId(), c.getStatus());
                    });
                case BOT_DELETE        -> withUser(client, u ->
                        botController.deleteBot(u, (Integer) request.getPayload()));
                case BOT_DELETE_IMPACT -> withUser(client, u ->
                        botController.describeDeletion(u, (Integer) request.getPayload()));
                case BOT_ADD_SOURCE    -> withUser(client, u ->
                        botController.addSource(u, (SourceRequest) request.getPayload()));
                case BOT_REMOVE_SOURCE -> withUser(client, u ->
                        botController.removeSource(u, (Integer) request.getPayload()));
                case BOT_USAGE         -> withUser(client, u ->
                        botController.getUsage(u, (Integer) request.getPayload()));
                case BOT_AVAILABLE     -> withUser(client, u ->
                        botController.listAvailableBots(u));
                case BOT_ASK           -> withUser(client, u ->
                        botController.ask(u, (BotQuestion) request.getPayload()));
                case BOT_MY_HISTORY    -> withUser(client, u ->
                        botController.myHistory(u));
            };

            // A newly saved exam goes straight into a coordinator's queue, so tell
            // her now rather than leaving her to discover it. Done here rather than
            // inside the builder so the two controllers stay independent of each
            // other - the builder does not need to know approval exists.
            if (request.getType() == RequestType.EXAM_SAVE
                    && response.isOk() && response.getPayload() instanceof Exam saved) {
                examApprovalController.notifyCoordinatorOfNewExam(saved);
            }

            // A student starting or handing in changes what the teacher's live
            // view should show, so tell her rather than leaving her to refresh.
            if (response.isOk()
                    && (request.getType() == RequestType.TAKE_START
                     || request.getType() == RequestType.TAKE_SUBMIT)
                    && response.getPayload() instanceof hsts.common.entity.StudentExam attempt) {
                liveExamController.notifyTeacherOfActivity(attempt.getExecutionId(),
                        attempt.getStudentName() + " "
                        + (request.getType() == RequestType.TAKE_START
                           ? "started the exam." : "handed in."));

                // Requirement 49: mark it now rather than waiting for somebody to
                // look. Marking is idempotent, and the teacher's screen marks
                // anything still unmarked - which covers a paper the clock closed
                // while the server happened to be restarting.
                if (request.getType() == RequestType.TAKE_SUBMIT) {
                    try {
                        gradeDAO.autoGrade(attempt.getSubmissionId());
                    } catch (Exception e) {
                        log("Could not mark submission " + attempt.getSubmissionId()
                            + " straight away: " + e.getMessage());
                    }
                }
            }
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
        // The clock has to run whenever the server does: a student's exam must
        // close on time even if nobody happens to be looking at a screen.
        examClock.setLogSink(this::log);
        examClock.setOnExamClosed(liveExamController::notifyTeacherOfActivity);
        examClock.start();
    }

    @Override
    protected void serverStopped() {
        examClock.stop();
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
