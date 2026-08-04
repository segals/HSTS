import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 7 verification: sitting an exam, with every edge case around the
 * code, the identity check, the server clock, saving, and handing in.
 */
public class M7Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher, coordinator;
    private static String course;

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


    // ---- codes unique to this run ------------------------------------------
    //
    // An execution code is unique for ever, so a fixed literal makes a test
    // runnable exactly once: the second run dies at setup with "that code is
    // already in use", and every later check fails for a reason that has nothing
    // to do with what it was testing. Each run picks a two-character prefix that
    // no existing sitting uses and builds its codes from it, so the suite can be
    // re-run any number of times without resetting the database.
    private static String runPrefix = "AA";

    /** A four-character code: this run's prefix plus a two-character tag. */
    static String code(String tag) {
        return runPrefix + tag;
    }

    static void pickRunPrefix(DBController db) throws Exception {
        // No Z: "ZZZZ" is used elsewhere as a code that must NOT exist.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXY0123456789";
        java.util.Set<String> taken = new java.util.HashSet<>();
        try (java.sql.PreparedStatement ps = db.getConnection()
                     .prepareStatement("SELECT execution_code FROM exam_execution");
             java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                taken.add(rs.getString(1).substring(0, 2).toUpperCase());
            }
        }
        for (char a : alphabet.toCharArray()) {
            for (char b : alphabet.toCharArray()) {
                String p = "" + a + b;
                if (!taken.contains(p)) {
                    runPrefix = p;
                    return;
                }
            }
        }
        throw new IllegalStateException("every code prefix is in use - reset the database");
    }

    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();
        pickRunPrefix(db);

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();          // also starts the exam clock

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C");
        @SuppressWarnings("unchecked")
        List<Course> mine = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = mine.get(0).getCourseCode();

        System.out.println("0. set up an exam and release it, open now");
        List<String> qs = new ArrayList<>();
        for (int i = 0; i < 4; i++) qs.add(addQuestion("M7 question " + i + " " + System.nanoTime()));
        String examId = saveExam(qs);
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));

        LocalDateTime now = LocalDateTime.now();
        ExamExecution live = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(10), now.plusHours(2),
                        code("7A"), 90, 1)).getPayload();
        check("released and open now", live != null && live.getExecutionId() > 0);

        // A student enrolled in this course.
        String studentUser = findEnrolledStudent(db, course);
        System.out.println("   enrolled student = " + studentUser);
        Conn student = new Conn();
        Student me = (Student) student.login(studentUser, studentUser + "!S").getPayload();
        check("student signed in", me != null);

        System.out.println("1. only a student may sit an exam");
        check("teacher refused the code step",
                !teacher.ask(RequestType.TAKE_VALIDATE_CODE, code("7A")).isOk());

        System.out.println("2. the code");
        check("unknown code refused",
                !student.ask(RequestType.TAKE_VALIDATE_CODE, "ZZZZ").isOk());
        Response badFormat = student.ask(RequestType.TAKE_VALIDATE_CODE, "AB");
        check("badly formed code refused", !badFormat.isOk());
        Response accepted = student.ask(RequestType.TAKE_VALIDATE_CODE, code("7A").toLowerCase());
        check("correct code accepted in lower case", accepted.isOk());
        check("no questions handed over yet at the code step",
                accepted.getPayload() instanceof ExamExecution);

        System.out.println("3. a student NOT enrolled is refused");
        String outsider = findNonEnrolledStudent(db, course);
        Conn stranger = new Conn();
        stranger.login(outsider, outsider + "!S");
        Response notEnrolled = stranger.ask(RequestType.TAKE_VALIDATE_CODE, code("7A"));
        check("refused (requirement 21)", !notEnrolled.isOk());
        System.out.println("   " + notEnrolled.getMessage());
        stranger.close();

        System.out.println("4. the identity number");
        int execId = live.getExecutionId();
        check("too short refused",
                !student.ask(RequestType.TAKE_START, new StartExamRequest(execId, "12345")).isOk());
        check("letters refused",
                !student.ask(RequestType.TAKE_START, new StartExamRequest(execId, "12345678a")).isOk());
        Response badDigit = student.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, "123456789"));
        check("valid length but wrong CHECK DIGIT refused", !badDigit.isOk());
        System.out.println("   " + badDigit.getMessage());
        Response notHers = student.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, "123456782"));   // valid ID, not hers
        check("a valid ID that is not hers is refused", !notHers.isOk());
        System.out.println("   " + notHers.getMessage());

        System.out.println("5. starting");
        Response started = student.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, me.getUserId()));
        check("her own ID accepted", started.isOk());
        StudentExam paper = (StudentExam) started.getPayload();
        check("4 questions handed over", paper.getQuestions().size() == 4);
        check("status IN_PROGRESS", paper.getStatus() == SubmissionStatus.IN_PROGRESS);
        check("attempt number 1", paper.getAttemptNo() == 1);
        long allowed = java.time.Duration.between(paper.getStartTime(), paper.getDeadline()).toMinutes();
        System.out.println("   deadline is " + allowed + " minutes after the start");
        check("deadline = start + the 90 minutes allowed", allowed == 90);
        check("blank answer rows exist for every question", paper.getAnswers().size() == 4);

        System.out.println("6. SECURITY - the answer key never reaches the student");
        boolean anyCorrect = false;
        for (ExamQuestion eq : paper.getQuestions())
            for (Answer a : eq.getQuestion().getAnswers())
                if (a.isCorrect()) anyCorrect = true;
        check("no option is marked correct on the student's copy", !anyCorrect);
        check("the teacher's private notes are not on it",
                paper.getInstructionsForStudents() == null
             || !paper.getInstructionsForStudents().contains("SECRET"));

        System.out.println("7. answering");
        ExamQuestion first = paper.getQuestions().get(0);
        Response saved = student.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(
                paper.getSubmissionId(), first.getQuestionId(), first.getQuestionVersion(), 2));
        check("answer accepted", saved.isOk());
        check("the reply carries the seconds remaining", saved.getPayload() instanceof Long);
        check("out-of-range answer refused", !student.ask(RequestType.TAKE_SAVE_ANSWER,
                new AnswerChoice(paper.getSubmissionId(), first.getQuestionId(),
                        first.getQuestionVersion(), 9)).isOk());

        System.out.println("8. resuming - reopening returns the same attempt");
        Response resumed = student.ask(RequestType.TAKE_RESUME, paper.getSubmissionId());
        check("resume works", resumed.isOk());
        StudentExam again = (StudentExam) resumed.getPayload();
        check("same submission", again.getSubmissionId() == paper.getSubmissionId());
        check("the saved answer came back", Integer.valueOf(2).equals(
                again.answerFor(first.getQuestionId())));
        Response reCode = student.ask(RequestType.TAKE_VALIDATE_CODE, code("7A"));
        check("entering the code again sends her back in rather than refusing", reCode.isOk());

        System.out.println("9. another student cannot touch her paper");
        String otherUser = findOtherEnrolledStudent(db, course, me.getUserId());
        Conn other = new Conn();
        other.login(otherUser, otherUser + "!S");
        check("cannot save into her attempt", !other.ask(RequestType.TAKE_SAVE_ANSWER,
                new AnswerChoice(paper.getSubmissionId(), first.getQuestionId(),
                        first.getQuestionVersion(), 1)).isOk());
        check("cannot hand in her attempt",
                !other.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId()).isOk());
        check("cannot even reload it",
                !other.ask(RequestType.TAKE_RESUME, paper.getSubmissionId()).isOk());
        other.close();

        System.out.println("10. handing in");
        Response handedIn = student.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());
        check("accepted", handedIn.isOk());
        StudentExam done = (StudentExam) handedIn.getPayload();
        check("status FINISHED", done.getStatus() == SubmissionStatus.FINISHED);
        check("actual duration recorded (requirement 46)", done.getActualDuration() != null);
        check("end time recorded", done.getEndTime() != null);
        check("answering afterwards is refused", !student.ask(RequestType.TAKE_SAVE_ANSWER,
                new AnswerChoice(paper.getSubmissionId(), first.getQuestionId(),
                        first.getQuestionVersion(), 3)).isOk());
        Response twice = student.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());
        check("handing in twice is harmless", twice.isOk());

        System.out.println("11. acceptance test 2.8 - one attempt means one attempt");
        Response reEnter = student.ask(RequestType.TAKE_VALIDATE_CODE, code("7A"));
        check("she cannot start it again", !reEnter.isOk());
        System.out.println("   " + reEnter.getMessage());

        System.out.println("12. REQUIREMENT 45 - the sitting's close cuts her short");
        // This used to check the opposite, under the reading recorded as answer Q8:
        // the close was a deadline to START, and a girl already inside kept her full
        // allowance past it. The customer changed that on 30 July 2026, and
        // requirement 45 is on their side - "בסיום זמן הבחינה, המערכת תסגור את
        // הבחינה עבור כל התלמידות". The end of the exam time closes it for EVERYBODY.
        LocalDateTime soon = LocalDateTime.now().plusSeconds(30);
        ExamExecution closing = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, LocalDateTime.now().minusMinutes(1),
                        soon, code("7B"), 60, 1)).getPayload();
        Conn late = new Conn();
        Student lateMe = (Student) late.login(otherUser, otherUser + "!S").getPayload();
        check("she may start while the window is still open",
                late.ask(RequestType.TAKE_VALIDATE_CODE, code("7B")).isOk());
        Response lateStart = late.ask(RequestType.TAKE_START,
                new StartExamRequest(closing.getExecutionId(), lateMe.getUserId()));
        StudentExam latePaper = (StudentExam) lateStart.getPayload();
        check("started", latePaper != null);
        System.out.println("   window closes  " + closing.getCloseTime());
        System.out.println("   her deadline   " + latePaper.getDeadline());
        System.out.println("   " + lateStart.getMessage());

        // The two ends are still kept apart in the row: her own allowance is written
        // as she starts, and the sitting's close is carried beside it. Folding them
        // into one column would lose the ability to say WHICH clock stopped her.
        check("her own deadline is still the full 60 minutes", java.time.Duration.between(
                latePaper.getStartTime(), latePaper.getDeadline()).toMinutes() == 60);
        check("which is after the window closes",
                latePaper.getDeadline().isAfter(closing.getCloseTime()));
        check("BUT THE CLOSE IS WHAT WILL STOP HER", latePaper.isCutShortByClose());
        check("so her exam really ends at the close",
                latePaper.effectiveEnd().equals(closing.getCloseTime()));
        check("and she was TOLD that when she started, not left to find out",
                lateStart.getMessage().contains("closes for everyone")
             && !lateStart.getMessage().contains("60 minutes"));

        // Answered now, while the sitting is open, so that requirement 45's other
        // half - "ותשמור את התשובות שהוזנו" - has something real to keep.
        check("she can answer while the sitting is open",
                late.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(
                        latePaper.getSubmissionId(),
                        latePaper.getQuestions().get(0).getQuestionId(),
                        latePaper.getQuestions().get(0).getQuestionVersion(), 1)).isOk());

        System.out.println("13. starting after the window shut is refused");
        try (java.sql.PreparedStatement st = db.getConnection().prepareStatement(
                // Both moved: the schema has CHECK (close_time > open_time), and it is
                // right to - pushing only the close time into the past would make
                // the row describe a window that ends before it starts.
                "UPDATE exam_execution SET open_time = NOW() - INTERVAL 10 MINUTE, "
              + "close_time = NOW() - INTERVAL 1 MINUTE "
              + "WHERE execution_code = ?")) {
            st.setString(1, code("7B"));
            // The code used to be spelled out here as a literal. When the codes
            // became per-run this one was missed, the UPDATE matched no rows, and
            // the two checks below failed - correctly, because the window had not
            // actually been moved. Asserting the row count stops that being silent.
            int moved = st.executeUpdate();
            check("the sitting's window was pushed into the past", moved == 1);
        }
        Conn tooLate = new Conn();
        String thirdUser = findOtherEnrolledStudent(db, course,
                lateMe.getUserId(), me.getUserId());
        tooLate.login(thirdUser, thirdUser + "!S");
        Response shut = tooLate.ask(RequestType.TAKE_VALIDATE_CODE, code("7B"));
        check("refused", !shut.isOk());
        check("refused BECAUSE the window shut, not for some other reason",
                shut.getMessage().toLowerCase().contains("period for starting"));
        System.out.println("   " + shut.getMessage());
        tooLate.close();

        System.out.println("14. THE CLOCK - the room closes, and she is closed with it");
        // Section 13 pushed this sitting's window into the past, which under the new
        // rule ends the exam for everybody still inside it. Nothing else is needed
        // to trigger this: the clock re-reads the sitting every second.
        //
        // 30 seconds against a clock that ticks every second is generous, but this
        // is the one genuinely time-dependent wait in the suite and a loaded machine
        // can miss it. A miss reports itself and carries on rather than taking the
        // rest of the run down with it.
        PushEvent closedPush = pollFor(late, PushType.EXAM_CLOSED_FOR_EVERYONE, 30);
        check("the server pushed EXAM_CLOSED_FOR_EVERYONE, unprompted", closedPush != null);
        if (closedPush != null) {
            System.out.println("   " + closedPush.getMessage());
            check("and it says it was the room, not her own clock",
                    closedPush.getMessage().contains("closed for everyone"));
        }
        check("she cannot answer once the sitting has closed",
                !late.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(
                        latePaper.getSubmissionId(),
                        latePaper.getQuestions().get(0).getQuestionId(),
                        latePaper.getQuestions().get(0).getQuestionVersion(), 2)).isOk());

        Response afterClose = late.ask(RequestType.TAKE_SUBMIT, latePaper.getSubmissionId());
        StudentExam closedPaper = (afterClose.getPayload() instanceof StudentExam se) ? se : null;
        if (closedPaper == null) {
            check("status is TIMED_OUT - NO PAPER CAME BACK: "
                    + afterClose.getMessage(), false);
            closedPaper = latePaper;       // so the checks below still say something
        }
        // TIMED_OUT for both endings on purpose. Requirement 48 counts students who
        // started, finished, and "did not manage" - two outcomes, not three - and a
        // girl the room closed on did not finish by herself either.
        check("status is TIMED_OUT, not FINISHED",
                closedPaper.getStatus() == SubmissionStatus.TIMED_OUT);
        check("HER ANSWER FROM BEFORE THE CLOSE IS KEPT (requirement 45)",
                Integer.valueOf(1).equals(closedPaper.answerFor(
                        latePaper.getQuestions().get(0).getQuestionId())));
        var storedAnswers = new hsts.server.dao.SubmissionDAO()
                .findAnswers(latePaper.getSubmissionId());
        check("and it is genuinely in the DATABASE", storedAnswers.stream()
                .anyMatch(a -> a.getQuestionId().equals(
                        latePaper.getQuestions().get(0).getQuestionId())
                        && Integer.valueOf(1).equals(a.getSelectedAnswerNo())));
        check("a duration was recorded even though she never pressed anything",
                closedPaper.getActualDuration() != null);
        late.close();

        System.out.println("15. THE CLOCK - her OWN time running out still closes it");
        // The other ending, which must keep working: a sitting whose window is wide
        // open, and a girl whose personal deadline arrives first.
        String ownTimeUser = findOtherEnrolledStudent(db, course,
                lateMe.getUserId(), me.getUserId(), idOf(db, thirdUser));
        ExamExecution ownTime = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, LocalDateTime.now().minusMinutes(1),
                        LocalDateTime.now().plusHours(6), code("7E"), 60, 1)).getPayload();
        Conn own = new Conn();
        Student ownMe = (Student) own.login(ownTimeUser, ownTimeUser + "!S").getPayload();
        own.ask(RequestType.TAKE_VALIDATE_CODE, code("7E"));
        StudentExam ownPaper = (StudentExam) own.ask(RequestType.TAKE_START,
                new StartExamRequest(ownTime.getExecutionId(), ownMe.getUserId())).getPayload();
        check("she is sitting a exam whose window stays open for hours", ownPaper != null);
        check("so it is her own clock that binds", !ownPaper.isCutShortByClose());
        check("she can answer", own.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(
                        ownPaper.getSubmissionId(),
                        ownPaper.getQuestions().get(0).getQuestionId(),
                        ownPaper.getQuestions().get(0).getQuestionVersion(), 1)).isOk());

        own.pushes.clear();
        try (Statement st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE student_exam SET deadline = NOW() - INTERVAL 1 SECOND "
                           + "WHERE submission_id = " + ownPaper.getSubmissionId());
        }
        PushEvent autoPush = pollFor(own, PushType.EXAM_AUTO_SUBMITTED, 30);
        check("the server pushed EXAM_AUTO_SUBMITTED, unprompted", autoPush != null);
        if (autoPush != null) {
            System.out.println("   " + autoPush.getMessage());
            check("and this one says it was HER time", autoPush.getMessage()
                    .toLowerCase().contains("your time is up"));
        }
        Response afterAuto = own.ask(RequestType.TAKE_SUBMIT, ownPaper.getSubmissionId());
        StudentExam timedOut = (afterAuto.getPayload() instanceof StudentExam se) ? se : null;
        check("status is TIMED_OUT, not FINISHED",
                timedOut != null && timedOut.getStatus() == SubmissionStatus.TIMED_OUT);
        check("her answer survived that too", timedOut != null
                && Integer.valueOf(1).equals(timedOut.answerFor(
                        ownPaper.getQuestions().get(0).getQuestionId())));
        own.close();

        System.out.println("16. the countdown is pushed while she sits");
        Conn ticker = new Conn();
        Student tickMe = (Student) ticker.login(thirdUser, thirdUser + "!S").getPayload();
        ExamExecution tickExec = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, LocalDateTime.now().minusMinutes(1),
                        LocalDateTime.now().plusHours(1), code("7C"), 45, 1)).getPayload();
        ticker.ask(RequestType.TAKE_VALIDATE_CODE, code("7C"));
        ticker.ask(RequestType.TAKE_START,
                new StartExamRequest(tickExec.getExecutionId(), tickMe.getUserId()));
        ticker.pushes.clear();
        PushEvent tick = pollFor(ticker, PushType.EXAM_TIME_TICK, 8);
        check("a countdown tick arrived without being asked for", tick != null);
        if (tick != null) {
            long left = (Long) tick.getPayload();
            System.out.println("   " + left + " seconds left");
            check("it is a sensible number of seconds", left > 0 && left <= 45 * 60);
        }
        ticker.close();

        System.out.println("17. requirement 48 - the counts come out right");
        ExamExecution recount = new hsts.server.dao.ExecutionDAO().findByCode(code("7A"));
        System.out.println("   started=" + recount.getNumStarted()
                         + " finished=" + recount.getNumFinishedSelf()
                         + " timedOut=" + recount.getNumTimedOut());
        check("one started", recount.getNumStarted() == 1);
        check("one finished by herself", recount.getNumFinishedSelf() == 1);
        check("none timed out on that sitting", recount.getNumTimedOut() == 0);

        student.close(); teacher.close(); coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // ---------- helpers ----------

    static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(1, TimeUnit.SECONDS);
            if (e != null && e.getType() == type) return e;
        }
        return null;
    }

    static String findEnrolledStudent(DBController db, String courseCode) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + courseCode + "' ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Excludes every id given, so it never returns somebody already signed in. */
    static String findOtherEnrolledStudent(DBController db, String courseCode, String... notThese)
            throws Exception {
        StringBuilder exclude = new StringBuilder();
        for (String id : notThese) {
            exclude.append(" AND u.user_id <> '").append(id).append("'");
        }
        try (Statement st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + courseCode + "'" + exclude
                     + " ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** One username's user id, for the "not these" lists above. */
    static String idOf(DBController db, String username) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT user_id FROM users WHERE username = '"
                     + username + "'")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    static String findNonEnrolledStudent(DBController db, String courseCode) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u WHERE u.role='STUDENT' "
                     + "AND u.user_id NOT IN (SELECT user_id FROM course_student "
                     + "WHERE course_code = '" + courseCode + "') ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String saveExam(List<String> ids) throws Exception {
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, ids)).getPayload();
        draft.setName("M7Test exam " + System.nanoTime());
        draft.setDurationMinutes(90);
        draft.setInstructionsForStudents("Answer all four questions.");
        draft.setNotesForTeacher("SECRET teacher note");
        Response r = teacher.ask(RequestType.EXAM_SAVE, draft);
        return r.isOk() ? ((Exam) r.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text) throws Exception {
        Question q = new Question();
        q.setCourseCode(course); q.setText(text); q.setName("M7Test q " + System.nanoTime());
 q.setTopic("M7");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> a = new ArrayList<>();
        for (int i = 1; i <= 4; i++) a.add(new Answer(i, "option " + i, i == 3));
        q.setAnswers(a);
        return ((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload()).getQuestionId();
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(200);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(400);
        final HSTSClient client;
        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
                else if (m instanceof PushEvent p) pushes.offer(p);
            }, r -> {});
            client.openConnection();
        }
        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }
        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            return inbox.poll(15, TimeUnit.SECONDS);
        }
        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
