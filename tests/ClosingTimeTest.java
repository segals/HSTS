import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import hsts.server.push.ExamClockService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * The sitting's closing time ends the exam for everybody.
 *
 * <p>Requirement 45: <i>"בסיום זמן הבחינה, המערכת תסגור את הבחינה עבור כל התלמידות
 * ותשמור את התשובות שהוזנו"</i>. This replaces the earlier reading, recorded as
 * answer Q8 in the understanding document, under which the closing moment was only
 * a deadline to <em>start</em> and a girl who began in time kept her full
 * allowance past it.</p>
 *
 * <p>What is checked here:</p>
 * <ol>
 *   <li>the two ends, and which one wins, as pure logic;</li>
 *   <li>the countdown she is shown is the end that will really stop her;</li>
 *   <li>she is warned five minutes before the close - and the 90% popup is NOT
 *       also sent, because only one warning belongs to any one attempt;</li>
 *   <li>the 90% popup still arrives when her own time is the binding end;</li>
 *   <li>at the close, EVERY student still inside is handed in, her answers kept;</li>
 *   <li>the wording of both warnings.</li>
 * </ol>
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." ClosingTimeTest &lt;user&gt; &lt;password&gt;</p>
 */
public class ClosingTimeTest {

    private static final int PORT = freePort();
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

        // ==============================================================
        System.out.println("1. the two ends, as pure logic");
        // ==============================================================
        LocalDateTime start = LocalDateTime.of(2026, 5, 1, 10, 0);

        StudentExam roomFirst = attempt(start, start.plusMinutes(90), start.plusMinutes(20));
        check("close before her deadline -> the room wins", roomFirst.isCutShortByClose());
        check("...and that is the effective end",
                roomFirst.effectiveEnd().equals(start.plusMinutes(20)));

        StudentExam hersFirst = attempt(start, start.plusMinutes(90), start.plusMinutes(200));
        check("close after her deadline -> her own time wins", !hersFirst.isCutShortByClose());
        check("...and that is the effective end",
                hersFirst.effectiveEnd().equals(start.plusMinutes(90)));

        StudentExam tie = attempt(start, start.plusMinutes(90), start.plusMinutes(90));
        check("a tie goes to her own time, which is the one requirement 43 warns about",
                !tie.isCutShortByClose());

        StudentExam noClose = attempt(start, start.plusMinutes(90), null);
        check("no close known -> her deadline, unchanged", !noClose.isCutShortByClose()
                && noClose.effectiveEnd().equals(start.plusMinutes(90)));

        // The countdown and the expiry must both follow the effective end, or the
        // screen and the clock would disagree about when her exam is over.
        LocalDateTime tenPast = start.plusMinutes(10);
        check("seconds remaining counts to the room's close, not hers",
                roomFirst.secondsRemainingAt(tenPast) == 600);
        check("and she is expired once the room has closed",
                roomFirst.isExpiredAt(start.plusMinutes(21)));
        check("but not before it", !roomFirst.isExpiredAt(start.plusMinutes(19)));

        // ==============================================================
        System.out.println("2. the wording of the two warnings");
        // ==============================================================
        check("90% wording is unchanged",
                "90% of the exam time has gone. You have 6 minutes and 42 seconds left."
                        .equals(ExamClockService.describeWarning(402)));
        String closing = ExamClockService.describeClosingWarning(
                252, LocalDateTime.of(2026, 5, 1, 13, 30));
        System.out.println("   " + closing);
        check("the closing warning names the wall-clock time", closing.contains("13:30"));
        check("...says it is for everyone", closing.contains("closes for everyone"));
        check("...names minutes AND seconds",
                closing.contains("4 minutes and 12 seconds"));
        check("...and says the paper will be handed in",
                closing.contains("handed in for you"));
        check("singular reads properly",
                ExamClockService.describeClosingWarning(61, null).contains("1 minute and 1 second"));
        check("under a minute names only seconds",
                ExamClockService.describeClosingWarning(45, null).contains("45 seconds")
             && !ExamClockService.describeClosingWarning(45, null).contains("minute"));
        check("no \"less than a minute\" wording anywhere",
                !closing.toLowerCase().contains("less than a minute"));

        // ==============================================================
        System.out.println("3. a sitting about to close: TWO students inside");
        // ==============================================================
        String examId = buildExam();
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        LocalDateTime now = LocalDateTime.now();
        ExamExecution sitting = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(2), now.plusHours(3),
                        code, 100, 1)).getPayload();
        check("a sitting, 100 minutes long, closing in three hours", sitting != null);

        String userA = enrolledFreeStudent("01");
        String userB = enrolledFreeStudent("01");
        Conn aliceConn = new Conn();
        Student alice = (Student) aliceConn.login(userA, userA + "!S").getPayload();
        Conn bethConn = new Conn();
        Student beth = (Student) bethConn.login(userB, userB + "!S").getPayload();

        aliceConn.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam alicePaper = (StudentExam) aliceConn.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), alice.getUserId())).getPayload();
        bethConn.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam bethPaper = (StudentExam) bethConn.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), beth.getUserId())).getPayload();
        check("both are sitting it", alicePaper != null && bethPaper != null);

        // Each answers one question, so requirement 45's "ותשמור את התשובות שהוזנו"
        // has something real to keep.
        String firstQuestion = alicePaper.getQuestions().get(0).getQuestionId();
        check("Alice's answer is accepted while the sitting is open",
                aliceConn.ask(RequestType.TAKE_SAVE_ANSWER,
                        new AnswerChoice(alicePaper.getSubmissionId(), firstQuestion, 1, 3)).isOk());
        check("and Beth's",
                bethConn.ask(RequestType.TAKE_SAVE_ANSWER,
                        new AnswerChoice(bethPaper.getSubmissionId(), firstQuestion, 1, 2)).isOk());

        // ==============================================================
        System.out.println("4. the close moves to four minutes away");
        // ==============================================================
        // Moved with SQL rather than by waiting three hours. The clock re-reads the
        // sitting on every tick, so this is exactly what a real approaching close
        // looks like to it.
        moveClose(sitting.getExecutionId(), "NOW() + INTERVAL 4 MINUTE");
        aliceConn.pushes.clear();
        bethConn.pushes.clear();

        PushEvent closingWarning = pollFor(aliceConn, PushType.EXAM_CLOSING_WARNING, 15);
        check("ALICE IS WARNED THAT THE ROOM IS CLOSING", closingWarning != null);
        if (closingWarning != null) {
            System.out.println("   " + closingWarning.getMessage());
            check("it carries the seconds left", closingWarning.getPayload() instanceof Long);
            long left = (Long) closingWarning.getPayload();
            System.out.println("   " + left + " seconds");
            check("about four minutes' worth", left > 180 && left <= 240);
            check("the message is the closing one, not the 90% one",
                    closingWarning.getMessage().startsWith("This exam closes for everyone"));
        }
        check("Beth is warned too - it is the whole room",
                pollFor(bethConn, PushType.EXAM_CLOSING_WARNING, 15) != null);

        // The point of the change: ONE popup, the relevant one. She has used four
        // minutes of a hundred, so the 90% mark of her own time is nowhere near -
        // and it must never arrive, because the room will stop her long before.
        check("NO 90% popup as well - only one warning per student",
                pollFor(aliceConn, PushType.EXAM_TIME_WARNING, 4) == null);
        check("and the closing warning is not repeated every second",
                pollFor(aliceConn, PushType.EXAM_CLOSING_WARNING, 4) == null);

        // Her screen must count to the end that will really stop her.
        PushEvent tick = pollFor(aliceConn, PushType.EXAM_TIME_TICK, 5);
        check("the countdown is still running", tick != null);
        if (tick != null) {
            long seconds = (Long) tick.getPayload();
            System.out.println("   countdown says " + seconds + " seconds");
            check("and it counts to the CLOSE, not to her hundred minutes",
                    seconds <= 240);
        }

        // The teacher adding time cannot help while the room is the binding end, and
        // she is told so rather than left wondering why nothing changed.
        Response added = teacher.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(sitting.getExecutionId(), 10));
        System.out.println("   " + added.getMessage());
        check("adding time is allowed", added.isOk());
        check("but the teacher is told the close caps it",
                added.getMessage().contains("closes at")
             && added.getMessage().contains("nothing from the extra time"));

        // ==============================================================
        System.out.println("5. the close arrives - everyone is handed in");
        // ==============================================================
        moveClose(sitting.getExecutionId(), "NOW() + INTERVAL 2 SECOND");

        PushEvent aliceClosed = pollFor(aliceConn, PushType.EXAM_CLOSED_FOR_EVERYONE, 20);
        check("ALICE'S PAPER IS HANDED IN FOR HER", aliceClosed != null);
        if (aliceClosed != null) {
            System.out.println("   " + aliceClosed.getMessage());
            check("and she is told it was the room, not her own clock",
                    aliceClosed.getMessage().contains("closed for everyone"));
            check("...and that her answers were kept",
                    aliceClosed.getMessage().contains("saved"));
        }
        check("BETH'S TOO - every student still inside",
                pollFor(bethConn, PushType.EXAM_CLOSED_FOR_EVERYONE, 20) != null);

        Thread.sleep(500);
        check("Alice's paper is closed in the database",
                statusOf(alicePaper.getSubmissionId()) == SubmissionStatus.TIMED_OUT);
        check("Beth's too",
                statusOf(bethPaper.getSubmissionId()) == SubmissionStatus.TIMED_OUT);
        check("her recorded end time is the sitting's close, not the moment the tick ran",
                endsAtClose(alicePaper.getSubmissionId()));
        check("and a duration was recorded (requirement 46)",
                durationOf(alicePaper.getSubmissionId()) != null);

        // Requirement 45: "ותשמור את התשובות שהוזנו".
        check("the answer Alice had chosen is still there",
                chosenAnswer(alicePaper.getSubmissionId(), firstQuestion) != null);
        check("and Beth's", chosenAnswer(bethPaper.getSubmissionId(), firstQuestion) != null);

        Response late = aliceConn.ask(RequestType.TAKE_SAVE_ANSWER,
                new AnswerChoice(alicePaper.getSubmissionId(), firstQuestion, 1, 4));
        check("she cannot answer after the close", !late.isOk());
        System.out.println("   " + late.getMessage());

        Response tooLate = aliceConn.ask(RequestType.TAKE_VALIDATE_CODE, code);
        check("and nobody can start it now", !tooLate.isOk());
        System.out.println("   " + tooLate.getMessage());

        // ==============================================================
        System.out.println("6. the OTHER branch: her own time is the binding end");
        // ==============================================================
        String exam2 = buildExam();
        String code2 = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        now = LocalDateTime.now();
        ExamExecution far = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(exam2, 1, now.minusMinutes(2), now.plusHours(6),
                        code2, 100, 1)).getPayload();
        String userC = enrolledFreeStudent("01");
        Conn carol = new Conn();
        Student carolMe = (Student) carol.login(userC, userC + "!S").getPayload();
        carol.ask(RequestType.TAKE_VALIDATE_CODE, code2);
        StudentExam carolPaper = (StudentExam) carol.ask(RequestType.TAKE_START,
                new StartExamRequest(far.getExecutionId(), carolMe.getUserId())).getPayload();
        check("she is sitting a exam that closes in six hours", carolPaper != null);

        // Both ends of HER window move, so she is nine tenths through a 100-minute
        // exam with 7 minutes left - and the room is still hours away.
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE student_exam SET "
                           + "start_time = NOW() - INTERVAL 93 MINUTE, "
                           + "deadline   = NOW() + INTERVAL 7 MINUTE "
                           + "WHERE submission_id = " + carolPaper.getSubmissionId());
        }
        carol.pushes.clear();
        PushEvent ninety = pollFor(carol, PushType.EXAM_TIME_WARNING, 15);
        check("THE 90% POPUP STILL ARRIVES when her own time is what will stop her",
                ninety != null);
        if (ninety != null) {
            System.out.println("   " + ninety.getMessage());
            check("and it is the 90% wording", ninety.getMessage().startsWith("90%"));
        }
        check("and NOT the closing one",
                pollFor(carol, PushType.EXAM_CLOSING_WARNING, 4) == null);
        carol.ask(RequestType.TAKE_SUBMIT, carolPaper.getSubmissionId());

        // ==============================================================
        System.out.println("7. starting late in the window is told the truth");
        // ==============================================================
        String exam3 = buildExam();
        String code3 = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        now = LocalDateTime.now();
        ExamExecution soon = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(exam3, 1, now.minusMinutes(2), now.plusHours(3),
                        code3, 100, 1)).getPayload();
        moveClose(soon.getExecutionId(), "NOW() + INTERVAL 7 MINUTE");

        String userD = enrolledFreeStudent("01");
        Conn dina = new Conn();
        Student dinaMe = (Student) dina.login(userD, userD + "!S").getPayload();
        dina.ask(RequestType.TAKE_VALIDATE_CODE, code3);
        Response started = dina.ask(RequestType.TAKE_START,
                new StartExamRequest(soon.getExecutionId(), dinaMe.getUserId()));
        check("she may start - a short time is still a time", started.isOk());
        System.out.println("   " + started.getMessage());
        check("SHE IS TOLD SHE HAS SEVEN MINUTES, NOT A HUNDRED",
                started.getMessage().contains("7 minutes")
             && started.getMessage().contains("closes for everyone"));
        check("and not the full allowance", !started.getMessage().contains("100 minutes"));
        dina.ask(RequestType.TAKE_SUBMIT,
                ((StudentExam) started.getPayload()).getSubmissionId());

        aliceConn.close(); bethConn.close(); carol.close(); dina.close();
        teacher.close(); coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // -----------------------------------------------------------------

    private static StudentExam attempt(LocalDateTime start, LocalDateTime deadline,
                                       LocalDateTime close) {
        StudentExam s = new StudentExam();
        s.setStartTime(start);
        s.setDeadline(deadline);
        s.setCloseTime(close);
        return s;
    }

    private static void moveClose(int executionId, String sqlMoment) throws Exception {
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE exam_execution SET close_time = " + sqlMoment
                           + " WHERE execution_id = " + executionId);
        }
    }

    private static SubmissionStatus statusOf(int submissionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT status FROM student_exam WHERE submission_id = "
                     + submissionId)) {
            return rs.next() ? SubmissionStatus.valueOf(rs.getString(1)) : null;
        }
    }

    private static boolean endsAtClose(int submissionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery(
                 "SELECT s.end_time, x.close_time FROM student_exam s "
               + "JOIN exam_execution x ON x.execution_id = s.execution_id "
               + "WHERE s.submission_id = " + submissionId)) {
            if (!rs.next()) return false;
            java.sql.Timestamp end = rs.getTimestamp(1), close = rs.getTimestamp(2);
            System.out.println("   end_time " + end + " vs close_time " + close);
            return end != null && close != null
                && Math.abs(end.getTime() - close.getTime()) < 1000;
        }
    }

    private static Integer durationOf(int submissionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT actual_duration FROM student_exam "
                     + "WHERE submission_id = " + submissionId)) {
            if (!rs.next()) return null;
            int minutes = rs.getInt(1);
            return rs.wasNull() ? null : minutes;
        }
    }

    private static Integer chosenAnswer(int submissionId, String questionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT selected_answer_no FROM student_answer "
                     + "WHERE submission_id = " + submissionId
                     + " AND question_id = '" + questionId + "'")) {
            if (!rs.next()) return null;
            int chosen = rs.getInt(1);
            return rs.wasNull() ? null : chosen;
        }
    }

    private static String buildExam() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Question q = new Question();
            q.setCourseCode("01");
            q.setText("closing-time q" + i + " " + System.nanoTime());
            q.setName("ClosingTimeTest q " + System.nanoTime());
            q.setTopic("Closing");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("ClosingTimeTest exam " + System.nanoTime());
        draft.setDurationMinutes(100);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (course 01 may have reached 99 exams - reset the demo data)");
        }
        String examId = ((Exam) saved.getPayload()).getExamId();
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));
        return examId;
    }

    /** Every account this harness has already signed in. See NFR 16. */
    private static final Set<String> TAKEN = new LinkedHashSet<>();

    /**
     * Enrolled in the course, not mid-exam anywhere, and not one this harness is
     * already holding open.
     *
     * <p>The last clause is not tidiness. A user may not be signed in twice at once
     * (NFR 16), so handing back a girl who is already on another connection makes
     * the login fail and the failure surfaces far away as a null payload - which is
     * exactly what happened the first time this ran.</p>
     */
    private static String enrolledFreeStudent(String course) throws Exception {
        StringBuilder exclude = new StringBuilder();
        for (String used : TAKEN) {
            exclude.append(" AND u.username <> '").append(used).append("'");
        }
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + course + "'"
                     + exclude
                     + " AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id = u.user_id AND s.status = 'IN_PROGRESS') "
                     + "ORDER BY u.username LIMIT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("No free student left in course " + course);
            }
            String username = rs.getString(1);
            TAKEN.add(username);
            return username;
        }
    }

    private static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(400, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(400);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(900);
        final HSTSClient client;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
                else if (m instanceof PushEvent p) pushes.offer(p);
            }, r -> { });
            client.openConnection();
        }

        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }

        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            Response r = inbox.poll(15, TimeUnit.SECONDS);
            if (r == null) throw new IllegalStateException("no reply to " + t);
            return r;
        }

        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }
}
