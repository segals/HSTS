import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connection, written to from two threads at once.
 *
 * <h2>What this is chasing</h2>
 *
 * <p>A student sitting an exam is sent a countdown by the clock thread once a
 * second, while her own requests are answered on her connection's thread. Both
 * write to the same {@code ObjectOutputStream}, and OCSF's {@code sendToClient}
 * does not synchronise - so two writes can interleave, the stream is corrupted,
 * and the connection dies. What the user sees is a request that never comes back.</p>
 *
 * <p>It was seen once in ten runs of the badge suite as "no reply to TAKE_START",
 * with the server logging "connection was aborted". A fault that rare is easy to
 * blame on the network and impossible to fix by guessing, so this makes it happen
 * on purpose: a girl in an exam answering as fast as she can, for long enough that
 * the once-a-second tick has many chances to land in the middle of a reply.</p>
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." StreamRaceTest &lt;user&gt; &lt;password&gt;</p>
 */
public class StreamRaceTest {

    private static final int PORT = freePort();
    /** Long enough for a dozen ticks to be sent into the middle of a reply. */
    private static final int SECONDS = 14;

    private static int passed = 0, failed = 0;
    private static DBController db;
    private static Conn teacher, coordinator;

    /**
     * A port nobody is using, found at the moment of asking.
     *
     * <p>These suites used to each hold a fixed port in the 155xx range. On this
     * machine Windows hands out ephemeral ports from 1024 upwards - the whole range
     * - so an outbound connection belonging to any other program can be sitting on
     * the number a suite is about to listen on. It happened: one run failed with
     * "Address already in use" and the port turned out to be held by an unrelated
     * process. A fixed number was never safe, it was just usually lucky.</p>
     */
    private static int freePort() {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            probe.setReuseAddress(true);
            return probe.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { failed++; System.out.println("   [FAIL] harness threw:");
                              t.printStackTrace(System.out); }
        finally {
            System.out.println();
            System.out.println("==== passed " + passed + ", failed " + failed + " ====");
            System.exit(failed > 0 ? 1 : 0);
        }
    }

    static void run(String[] args) throws Exception {
        db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> { });
        server.setPort(PORT);
        server.listen();

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C");

        String examId = buildExam();
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        LocalDateTime now = LocalDateTime.now();
        ExamExecution sitting = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(1), now.plusHours(2),
                        code, 90, 1)).getPayload();

        String username = enrolledFreeStudent("01");
        Conn pupil = new Conn();
        Student her = (Student) pupil.login(username, username + "!S").getPayload();
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam paper = (StudentExam) pupil.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), her.getUserId())).getPayload();
        check("she is sitting an exam, so the clock is pushing to her", paper != null);

        String question = paper.getQuestions().get(0).getQuestionId();
        int version = paper.getQuestions().get(0).getQuestionVersion();

        System.out.println("   answering as fast as possible for " + SECONDS
                + " seconds while the clock ticks...");

        int sent = 0, missing = 0;
        long until = System.currentTimeMillis() + SECONDS * 1000L;
        while (System.currentTimeMillis() < until && pupil.alive) {
            Response r = pupil.askQuietly(RequestType.TAKE_SAVE_ANSWER,
                    new AnswerChoice(paper.getSubmissionId(), question, version,
                            1 + (sent % 4)));
            sent++;
            if (r == null) {
                missing++;
                break;              // the stream is gone; carrying on proves nothing
            }
        }

        System.out.println("   requests sent: " + sent
                + ", ticks received: " + pupil.ticks.get()
                + ", replies lost: " + missing
                + (pupil.dropped == null ? "" : ", connection: " + pupil.dropped));

        check("the clock really was pushing while she worked", pupil.ticks.get() >= 5);
        check("she sent a serious number of requests", sent >= 100);
        check("EVERY REQUEST CAME BACK - the stream was never corrupted", missing == 0);
        check("and the connection is still up", pupil.alive);

        pupil.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());
        pupil.close();
        teacher.close();
        coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // -----------------------------------------------------------------

    private static String buildExam() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Question q = new Question();
            q.setCourseCode("01");
            q.setText("race q" + i + " " + System.nanoTime());
            q.setName("StreamRaceTest q " + System.nanoTime());
            q.setTopic("Race");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("StreamRaceTest exam " + System.nanoTime());
        draft.setDurationMinutes(90);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (course 01 may have reached 99 exams - reset the demo data)");
        }
        String id = ((Exam) saved.getPayload()).getExamId();
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(id, 1, null));
        return id;
    }

    private static String enrolledFreeStudent(String course) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + course + "' "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id = u.user_id AND s.status = 'IN_PROGRESS') "
                     + "ORDER BY u.username LIMIT 1")) {
            if (!rs.next()) throw new IllegalStateException("no free student");
            return rs.getString(1);
        }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(4000);
        final AtomicInteger ticks = new AtomicInteger();
        final HSTSClient client;
        volatile boolean alive = true;
        volatile String dropped;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) {
                    inbox.offer(r);
                } else if (m instanceof PushEvent p
                        && p.getType() == PushType.EXAM_TIME_TICK) {
                    ticks.incrementAndGet();
                }
            }, reason -> { alive = false; dropped = reason; });
            client.openConnection();
        }

        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }

        Response ask(RequestType t, Object payload) throws Exception {
            Response r = askQuietly(t, payload);
            if (r == null) throw new IllegalStateException("no reply to " + t);
            return r;
        }

        /** Null when nothing came back, instead of throwing - the point of the test. */
        Response askQuietly(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            return inbox.poll(10, TimeUnit.SECONDS);
        }

        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }
}
