import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.common.util.ExecutionCode;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import hsts.server.dao.ExecutionDAO;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 6 verification: releasing an exam from the drawer, and every edge
 * case around approval, codes, windows, durations and attempts.
 */
public class M6Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher;      // teacher1, teaches course 01
    private static Conn coordinator;  // coordinator1, subject 01
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
        try {
            run(args);
        } catch (Throwable t) {
            failed++;
            System.out.println("   [FAIL] harness threw:");
            t.printStackTrace(System.out);
        } finally {
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
        server.listen();

        teacher = new Conn();
        teacher.login("teacher1", "teacher1!T");
        coordinator = new Conn();
        coordinator.login("coordinator1", "coordinator1!C");

        @SuppressWarnings("unchecked")
        List<Course> mine = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = mine.get(0).getCourseCode();

        System.out.println("0. set up: one approved exam, one pending, one rejected");
        List<String> qs = new ArrayList<>();
        for (int i = 0; i < 4; i++) qs.add(addQuestion("M6 q" + i + " " + System.nanoTime()));

        String approvedExam = saveExam(qs);
        String pendingExam  = saveExam(qs);
        String rejectedExam = saveExam(qs);

        check("approve succeeded", coordinator.ask(RequestType.EXAM_APPROVE,
                new ExamDecision(approvedExam, 1, null)).isOk());
        check("reject succeeded", coordinator.ask(RequestType.EXAM_REJECT,
                new ExamDecision(rejectedExam, 1, "not this time")).isOk());
        System.out.println("   approved=" + approvedExam + " pending=" + pendingExam
                         + " rejected=" + rejectedExam);

        LocalDateTime open  = LocalDateTime.now().plusMinutes(5);
        LocalDateTime close = open.plusHours(2);

        System.out.println("1. only APPROVED versions are offered");
        @SuppressWarnings("unchecked")
        List<Exam> releasable = (List<Exam>) teacher.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        check("the approved one is offered",
                releasable.stream().anyMatch(e -> e.getExamId().equals(approvedExam)));
        check("the pending one is NOT offered",
                releasable.stream().noneMatch(e -> e.getExamId().equals(pendingExam)));
        check("the rejected one is NOT offered",
                releasable.stream().noneMatch(e -> e.getExamId().equals(rejectedExam)));

        System.out.println("2. requirement 35 - unapproved versions cannot be released");
        Response pendingTry = release(teacher, pendingExam, 1, open, close, code("AA"), 60, 1);
        check("pending refused", !pendingTry.isOk());
        System.out.println("   " + pendingTry.getMessage());
        check("rejected refused",
                !release(teacher, rejectedExam, 1, open, close, code("AB"), 60, 1).isOk());
        check("a version that does not exist is refused",
                !release(teacher, approvedExam, 99, open, close, code("AC"), 60, 1).isOk());

        System.out.println("3. code format");
        check("3 characters refused",
                !release(teacher, approvedExam, 1, open, close, "ABC", 60, 1).isOk());
        check("5 characters refused",
                !release(teacher, approvedExam, 1, open, close, "ABCDE", 60, 1).isOk());
        check("empty refused",
                !release(teacher, approvedExam, 1, open, close, "", 60, 1).isOk());
        check("null refused",
                !release(teacher, approvedExam, 1, open, close, null, 60, 1).isOk());
        Response punct = release(teacher, approvedExam, 1, open, close, "AB-2", 60, 1);
        check("punctuation refused", !punct.isOk());
        System.out.println("   " + punct.getMessage());
        check("a space inside refused",
                !release(teacher, approvedExam, 1, open, close, "AB 2", 60, 1).isOk());

        System.out.println("4. window");
        check("close before open refused",
                !release(teacher, approvedExam, 1, close, open, code("BB"), 60, 1).isOk());
        check("close equal to open refused",
                !release(teacher, approvedExam, 1, open, open, code("BC"), 60, 1).isOk());
        Response past = release(teacher, approvedExam, 1,
                LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1), code("BD"), 60, 1);
        check("a window that already closed is refused", !past.isOk());
        System.out.println("   " + past.getMessage());

        System.out.println("5. duration and attempts");
        check("zero minutes refused",
                !release(teacher, approvedExam, 1, open, close, code("CA"), 0, 1).isOk());
        check("negative minutes refused",
                !release(teacher, approvedExam, 1, open, close, code("CB"), -30, 1).isOk());
        check("601 minutes refused",
                !release(teacher, approvedExam, 1, open, close, code("CC"), 601, 1).isOk());
        check("zero attempts refused",
                !release(teacher, approvedExam, 1, open, close, code("CD"), 60, 0).isOk());
        check("11 attempts refused",
                !release(teacher, approvedExam, 1, open, close, code("CE"), 60, 11).isOk());
        check("600 minutes accepted at the boundary",
                release(teacher, approvedExam, 1, open, close, code("CF"), 600, 10).isOk());

        System.out.println("6. a successful release");
        String lower = code("K2").toLowerCase();  // deliberately lower case
        Response ok = release(teacher, approvedExam, 1, open, close, lower, 90, 2);
        check("accepted", ok.isOk());
        ExamExecution x = (ExamExecution) ok.getPayload();
        System.out.println("   " + ok.getMessage());
        check("code stored UPPER CASE", code("K2").equals(x.getExecutionCode()));
        check("execution id assigned", x.getExecutionId() > 0);
        check("allocated duration kept", x.getAllocatedDuration() == 90);
        check("original duration equals allocated at release",
                x.getOriginalDuration() == x.getAllocatedDuration());
        check("attempts kept", x.getMaxAttempts() == 2);
        check("released by this teacher", x.getReleasedBy() != null);
        check("counts all start at zero",
                x.getNumStarted() == 0 && x.getNumFinishedSelf() == 0 && x.getNumTimedOut() == 0);
        check("pins the exam version", x.getExamVersion() == 1);

        System.out.println("7. codes are unique, whatever the case");
        check("the same code again is refused",
                !release(teacher, approvedExam, 1, open, close, code("K2"), 60, 1).isOk());
        Response caseClash = release(teacher, approvedExam, 1, open, close,
                             code("K2").toLowerCase(), 60, 1);
        check("the same code in another case is also refused", !caseClash.isOk());
        System.out.println("   " + caseClash.getMessage());

        System.out.println("8. lookup by code is case-insensitive");
        ExecutionDAO dao = new ExecutionDAO();
        check("found by upper case", dao.findByCode(code("K2")) != null);
        check("found by lower case", dao.findByCode(code("K2").toLowerCase()) != null);
        // The literal here was padded with spaces, so the sweep that made these
        // codes per-run did not match it. It kept passing only while K7M2 still
        // happened to exist in the database from an earlier run.
        check("found with surrounding spaces",
                dao.findByCode("  " + code("K2").toLowerCase() + "  ") != null);
        check("an unknown code finds nothing", dao.findByCode("ZZZZ") == null);

        System.out.println("9. requirement 36 - the same exam can be released again");
        Response second = release(teacher, approvedExam, 1,
                open.plusDays(7), close.plusDays(7), code("N8"), 90, 1);
        check("a second release of the same exam is accepted", second.isOk());
        ExamExecution x2 = (ExamExecution) second.getPayload();
        check("it is a different execution", x2.getExecutionId() != x.getExecutionId());
        check("with its own code", !x2.getExecutionCode().equals(x.getExecutionCode()));
        check("both point at the same exam version",
                x2.getExamId().equals(x.getExamId()) && x2.getExamVersion() == x.getExamVersion());

        System.out.println("10. \"in the drawer\" = no execution open right now");
        LocalDateTime now = LocalDateTime.now();
        String openNowCode = code("R9");
        Response openNow = release(teacher, approvedExam, 1,
                now.minusHours(1), now.plusHours(3), openNowCode, 60, 1);
        check("an execution open right now was created", openNow.isOk());
        check("the exam is OUT of the drawer now",
                dao.countOpenAt(approvedExam, now) >= 1);
        check("it was in the drawer a week ago",
                dao.countOpenAt(approvedExam, now.minusDays(7)) == 0);
        check("a future-only release does not count as open now",
                dao.countOpenAt(approvedExam, now.plusDays(30)) == 0);

        System.out.println("11. who may release");
        Conn otherTeacher = new Conn();
        otherTeacher.login("teacher5", "teacher5!T");      // teaches course 05, not 01
        Response wrongCourse = release(otherTeacher, approvedExam, 1, open, close, code("W1"), 60, 1);
        check("a teacher of another course is refused", !wrongCourse.isOk());
        System.out.println("   " + wrongCourse.getMessage());
        check("she does not see it as releasable either",
                ((List<Exam>) otherTeacher.ask(RequestType.EXECUTION_RELEASABLE_EXAMS, null)
                        .getPayload()).stream().noneMatch(e -> e.getExamId().equals(approvedExam)));
        otherTeacher.close();

        Conn student = new Conn();
        student.login("student1", "student1!S");
        check("a student cannot release",
                !release(student, approvedExam, 1, open, close, code("W2"), 60, 1).isOk());
        check("a student cannot even list releasable exams",
                !student.ask(RequestType.EXECUTION_RELEASABLE_EXAMS, null).isOk());
        student.close();

        System.out.println("12. decision 4 - a colleague on the same course MAY release it");
        Conn colleague = new Conn();
        colleague.login("teacher2", "teacher2!T");         // also teaches course 01
        Response byColleague = release(colleague, approvedExam, 1,
                open.plusDays(14), close.plusDays(14), code("Q7"), 60, 1);
        check("teacher2 can release teacher1's approved exam", byColleague.isOk());
        if (!byColleague.isOk()) System.out.println("   " + byColleague.getMessage());
        colleague.close();

        System.out.println("13. the teacher's own list of releases");
        @SuppressWarnings("unchecked")
        List<ExamExecution> myReleases = (List<ExamExecution>) teacher.ask(
                RequestType.EXECUTION_LIST_MINE, null).getPayload();
        System.out.println("   " + myReleases.size() + " released by teacher1");
        check("contains the one we made", myReleases.stream()
                .anyMatch(e -> code("K2").equals(e.getExecutionCode())));
        check("does NOT contain the colleague's release", myReleases.stream()
                .noneMatch(e -> code("Q7").equals(e.getExecutionCode())));

        System.out.println("14. suggested codes are valid and free");
        Set<String> suggestions = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            String s = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
            suggestions.add(s);
            if (!ExecutionCode.isValid(s)) { check("suggestion " + s + " is a valid code", false); }
            if (dao.isCodeTaken(s))        { check("suggestion " + s + " is unused", false); }
        }
        check("6 suggestions were all valid and unused", true);
        check("they are not all identical", suggestions.size() > 1);
        System.out.println("   e.g. " + suggestions);

        System.out.println("15. the code helper itself");
        check("4 digits valid (מתווה wording)",      ExecutionCode.isValid("1234"));
        check("4 letters valid",                     ExecutionCode.isValid("ABCD"));
        check("mixed valid (system description)",    ExecutionCode.isValid("A1B2"));
        check("3 chars invalid",                     !ExecutionCode.isValid("ABC"));
        check("5 chars invalid",                     !ExecutionCode.isValid("ABCDE"));
        check("punctuation invalid",                 !ExecutionCode.isValid("AB!2"));
        check("null invalid",                        !ExecutionCode.isValid(null));
        check("normalise upper-cases",               "K7M2".equals(ExecutionCode.normalise(" k7m2 ")));
        check("normalise rejects bad input",         ExecutionCode.normalise("XX") == null);

        System.out.println("16. an approved version stays releasable after a newer draft exists");
        Exam approvedV1 = (Exam) teacher.ask(RequestType.EXAM_GET,
                new ExamRef(approvedExam, 1)).getPayload();
        approvedV1.setName("M6Test exam " + System.nanoTime());
        approvedV1.setDurationMinutes(45);
        check("edit created version 2", teacher.ask(RequestType.EXAM_EDIT, approvedV1).isOk());
        @SuppressWarnings("unchecked")
        List<Exam> nowReleasable = (List<Exam>) teacher.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        check("version 1 (approved) is STILL releasable", nowReleasable.stream()
                .anyMatch(e -> e.getExamId().equals(approvedExam) && e.getVersion() == 1));
        check("version 2 (pending) is NOT releasable", nowReleasable.stream()
                .noneMatch(e -> e.getExamId().equals(approvedExam) && e.getVersion() == 2));
        check("and releasing version 2 is refused",
                !release(teacher, approvedExam, 2, open, close, code("VN"), 60, 1).isOk());

        System.out.println("17. window boundaries and stored precision");
        LocalDateTime o = LocalDateTime.now().plusMinutes(1).withNano(123456789);
        Response prec = release(teacher, approvedExam, 1, o, o.plusHours(1), code("P4"), 30, 1);
        check("a release carrying nanoseconds is accepted", prec.isOk());
        ExamExecution stored = dao.findByCode(code("P4"));
        check("the stored time has no sub-second part", stored.getOpenTime().getNano() == 0);
        check("the object returned matches the stored row",
                stored.getOpenTime().equals(((ExamExecution) prec.getPayload()).getOpenTime())
             && stored.getCloseTime().equals(((ExamExecution) prec.getPayload()).getCloseTime()));
        check("open exactly AT the opening instant", stored.isOpenAt(stored.getOpenTime()));
        check("one second before opening it is not yet open",
                stored.isNotYetOpenAt(stored.getOpenTime().minusSeconds(1)));
        check("one second before closing it is still open",
                stored.isOpenAt(stored.getCloseTime().minusSeconds(1)));
        check("exactly AT the closing instant it is closed",
                !stored.isOpenAt(stored.getCloseTime())
             && stored.hasClosedAt(stored.getCloseTime()));
        check("the SQL agrees with the entity at the opening instant",
                dao.countOpenAt(approvedExam, stored.getOpenTime()) >= 1);

        teacher.close();
        coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    static Response release(Conn who, String examId, int version,
                            LocalDateTime open, LocalDateTime close,
                            String code, int minutes, int attempts) throws Exception {
        return who.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, version, open, close, code, minutes, attempts));
    }

    static String saveExam(List<String> questionIds) throws Exception {
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, questionIds)).getPayload();
        draft.setName("M6Test exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        return saved.isOk() ? ((Exam) saved.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text) throws Exception {
        Question q = new Question();
        q.setCourseCode(course);
        q.setText(text);
        q.setName("M6Test q " + System.nanoTime());
        q.setTopic("M6Topic");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> answers = new ArrayList<>();
        for (int i = 1; i <= 4; i++) answers.add(new Answer(i, "option " + i, i == 1));
        q.setAnswers(answers);
        return ((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload()).getQuestionId();
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(80);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(80);
        final HSTSClient client;
        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r)       inbox.add(r);
                else if (m instanceof PushEvent p) pushes.add(p);
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
