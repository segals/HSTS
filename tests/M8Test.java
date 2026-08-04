import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.SubmissionDAO;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 8 verification: watching a sitting and changing its time while it
 * runs, and the pushes that make acceptance test 2.7 work.
 */
public class M8Test {

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
        server.listen();

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C");
        @SuppressWarnings("unchecked")
        List<Course> mine = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = mine.get(0).getCourseCode();

        System.out.println("0. an exam, approved and running now");
        List<String> qs = new ArrayList<>();
        for (int i = 0; i < 4; i++) qs.add(addQuestion("M8 q" + i + " " + System.nanoTime()));
        String examId = saveExam(qs);
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));

        LocalDateTime now = LocalDateTime.now();
        ExamExecution exec = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(5), now.plusHours(3),
                        code("8A"), 60, 1)).getPayload();
        check("released", exec != null);
        int execId = exec.getExecutionId();

        List<String> students = enrolled(db, course, 3);
        Conn s1 = new Conn();
        Student me1 = (Student) s1.login(students.get(0), students.get(0) + "!S").getPayload();
        s1.ask(RequestType.TAKE_VALIDATE_CODE, code("8A"));
        StudentExam paper1 = (StudentExam) s1.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, me1.getUserId())).getPayload();
        check("a student is inside", paper1 != null);
        LocalDateTime deadlineBefore = paper1.getDeadline();

        System.out.println("1. permissions");
        check("a student cannot watch",
                !s1.ask(RequestType.LIVE_RUNNING_NOW, null).isOk());
        check("a student cannot change the time", !s1.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(execId, 10)).isOk());
        Conn other = new Conn();
        other.login("teacher5", "teacher5!T");
        Response notHers = other.ask(RequestType.LIVE_CHANGE_TIME, new TimeChangeRequest(execId, 10));
        check("another teacher cannot change somebody else's sitting", !notHers.isOk());
        System.out.println("   " + notHers.getMessage());
        check("nor read its student list",
                !other.ask(RequestType.LIVE_STATUS, execId).isOk());
        other.close();

        System.out.println("2. the running list");
        @SuppressWarnings("unchecked")
        List<ExamExecution> running = (List<ExamExecution>) teacher.ask(
                RequestType.LIVE_RUNNING_NOW, null).getPayload();
        check("the sitting is listed", running.stream()
                .anyMatch(x -> x.getExecutionId() == execId));

        System.out.println("3. the student list and its counts");
        @SuppressWarnings("unchecked")
        List<StudentExam> inside = (List<StudentExam>) teacher.ask(
                RequestType.LIVE_STATUS, execId).getPayload();
        check("one student showing", inside.size() == 1);
        check("she is in progress", inside.get(0).isInProgress());

        System.out.println("4. rejected changes");
        check("a change of zero is refused",
                !teacher.ask(RequestType.LIVE_CHANGE_TIME, new TimeChangeRequest(execId, 0)).isOk());
        Response huge = teacher.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(execId, 500));
        check("an absurd change is refused", !huge.isOk());
        System.out.println("   " + huge.getMessage());
        Response tooMuchOff = teacher.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(execId, -120));
        check("taking away more than she has left is refused", !tooMuchOff.isOk());
        System.out.println("   " + tooMuchOff.getMessage());

        System.out.println("5. ACCEPTANCE TEST 2.7 - extra time, pushed live");
        s1.pushes.clear();
        Response added = teacher.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(execId, 15));
        check("accepted", added.isOk());
        System.out.println("   " + added.getMessage());

        PushEvent moved = pollFor(s1, PushType.EXAM_TIME_CHANGED, 8);
        check("THE STUDENT WAS TOLD, without asking for anything", moved != null);
        if (moved != null) {
            System.out.println("   push: " + moved.getMessage());
            long secondsNow = (Long) moved.getPayload();
            System.out.println("   " + secondsNow + " seconds left after the change");
            check("the push carries her new remaining seconds",
                    secondsNow > 60 * 60);       // was <= 60 min, now must exceed it
            check("the message says how much was added",
                    moved.getMessage().contains("15"));
        }

        System.out.println("6. her deadline really moved, in the database");
        StudentExam after = new SubmissionDAO().findById(paper1.getSubmissionId());
        long shift = Duration.between(deadlineBefore, after.getDeadline()).toMinutes();
        System.out.println("   deadline moved by " + shift + " minutes");
        check("moved by exactly 15 minutes", shift == 15);

        System.out.println("7. the sitting's allowance changed, the EXAM did not");
        ExamExecution reread = new ExecutionDAO().findById(execId);
        check("allowance is now 75", reread.getAllocatedDuration() == 75);
        check("the original is still recorded as 60", reread.getOriginalDuration() == 60);
        check("the change is visible as a change", reread.isDurationExtended());
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT duration_minutes FROM exam WHERE exam_id='"
                     + examId + "' AND version=1")) {
            rs.next();
            System.out.println("   exam.duration_minutes = " + rs.getInt(1));
            check("REQUIREMENT 47 - the exam itself is untouched", rs.getInt(1) == 60);
        }

        System.out.println("8. a student starting AFTER the change gets the new allowance");
        Conn s2 = new Conn();
        Student me2 = (Student) s2.login(students.get(1), students.get(1) + "!S").getPayload();
        s2.ask(RequestType.TAKE_VALIDATE_CODE, code("8A"));
        StudentExam paper2 = (StudentExam) s2.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, me2.getUserId())).getPayload();
        long allowed2 = Duration.between(paper2.getStartTime(), paper2.getDeadline()).toMinutes();
        System.out.println("   she gets " + allowed2 + " minutes");
        check("75 minutes, not the original 60", allowed2 == 75);

        System.out.println("9. taking time back");
        s1.pushes.clear();
        Response removed = teacher.ask(RequestType.LIVE_CHANGE_TIME,
                new TimeChangeRequest(execId, -5));
        check("accepted", removed.isOk());
        PushEvent shrunk = pollFor(s1, PushType.EXAM_TIME_CHANGED, 8);
        check("the student was told about that too", shrunk != null);
        StudentExam after2 = new SubmissionDAO().findById(paper1.getSubmissionId());
        check("her deadline moved back by 5",
                Duration.between(after.getDeadline(), after2.getDeadline()).toMinutes() == -5);
        check("the allowance is 70 now",
                new ExecutionDAO().findById(execId).getAllocatedDuration() == 70);

        System.out.println("10. the TEACHER is told when a student acts");
        teacher.pushes.clear();
        Conn s3 = new Conn();
        Student me3 = (Student) s3.login(students.get(2), students.get(2) + "!S").getPayload();
        s3.ask(RequestType.TAKE_VALIDATE_CODE, code("8A"));
        s3.ask(RequestType.TAKE_START, new StartExamRequest(execId, me3.getUserId()));
        PushEvent activity = pollFor(teacher, PushType.EXAM_LIVE_STATUS, 8);
        check("the teacher got a live-status push when a student started", activity != null);
        if (activity != null) System.out.println("   " + activity.getMessage());

        teacher.pushes.clear();
        s3.ask(RequestType.TAKE_SUBMIT, ((StudentExam) s3.ask(RequestType.TAKE_RESUME,
                findSubmission(db, execId, me3.getUserId())).getPayload()).getSubmissionId());
        PushEvent handedIn = pollFor(teacher, PushType.EXAM_LIVE_STATUS, 8);
        check("and again when she handed in", handedIn != null);
        if (handedIn != null) System.out.println("   " + handedIn.getMessage());

        System.out.println("11. counts after all that");
        @SuppressWarnings("unchecked")
        List<StudentExam> finalList = (List<StudentExam>) teacher.ask(
                RequestType.LIVE_STATUS, execId).getPayload();
        long working = finalList.stream().filter(StudentExam::isInProgress).count();
        long done = finalList.stream()
                .filter(x -> x.getStatus() == hsts.common.enums.SubmissionStatus.FINISHED).count();
        System.out.println("   " + finalList.size() + " started, " + working
                         + " still working, " + done + " finished");
        check("three started", finalList.size() == 3);
        check("two still working", working == 2);
        check("one finished", done == 1);

        System.out.println("12. a sitting stays visible while somebody is still inside it");
        try (Statement st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE exam_execution SET open_time = NOW() - INTERVAL 20 MINUTE, "
                           + "close_time = NOW() - INTERVAL 1 MINUTE WHERE execution_id = " + execId);
        }
        @SuppressWarnings("unchecked")
        List<ExamExecution> stillThere = (List<ExamExecution>) teacher.ask(
                RequestType.LIVE_RUNNING_NOW, null).getPayload();
        check("window closed, but it is still on the teacher's screen",
                stillThere.stream().anyMatch(x -> x.getExecutionId() == execId));
        check("and she can still give those students more time",
                teacher.ask(RequestType.LIVE_CHANGE_TIME, new TimeChangeRequest(execId, 5)).isOk());

        s1.close(); s2.close(); s3.close();
        teacher.close(); coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // ---------- helpers ----------

    static Integer findSubmission(DBController db, int execId, String studentId) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT submission_id FROM student_exam WHERE "
                     + "execution_id=" + execId + " AND student_id='" + studentId + "'")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(1, TimeUnit.SECONDS);
            if (e != null && e.getType() == type) return e;
        }
        return null;
    }

    static List<String> enrolled(DBController db, String courseCode, int howMany) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code='" + courseCode + "' ORDER BY u.username LIMIT "
                     + howMany)) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    static String saveExam(List<String> ids) throws Exception {
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, ids)).getPayload();
        draft.setName("M8Test exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        Response r = teacher.ask(RequestType.EXAM_SAVE, draft);
        return r.isOk() ? ((Exam) r.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text) throws Exception {
        Question q = new Question();
        q.setCourseCode(course); q.setText(text); q.setName("M8Test q " + System.nanoTime());
 q.setTopic("M8");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> a = new ArrayList<>();
        for (int i = 1; i <= 4; i++) a.add(new Answer(i, "option " + i, i == 2));
        q.setAnswers(a);
        return ((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload()).getQuestionId();
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(200);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(600);
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
