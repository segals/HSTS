import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * The principal's calendar and activity log, and a member of staff's to-do list.
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." StaffViewTest &lt;user&gt; &lt;password&gt;</p>
 */
public class StaffViewTest {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static DBController db;
    private static Conn teacher, coordinator;

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

    @SuppressWarnings("unchecked")
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
        Conn head = new Conn();   head.login("principal", "principal!P");

        // ==============================================================
        System.out.println("1. THE SCHOOL CALENDAR");
        // ==============================================================
        List<ExamExecution> calendar = (List<ExamExecution>) head.ask(
                RequestType.PRINCIPAL_CALENDAR, null).getPayload();
        check("the principal may read it", calendar != null);
        System.out.println("   " + calendar.size() + " sitting(s)");
        check("it has the demo sittings in it", calendar.size() >= 7);
        check("every sitting in the school, not one exam's",
                calendar.size() == countSittings());

        ExamExecution first = calendar.get(0);
        System.out.println("   newest: " + first.describeExam() + "  ·  "
                + first.getCourseName() + "  ·  code " + first.getExecutionCode()
                + "  ·  " + first.getOpenTime() + " -> " + first.getCloseTime()
                + "  ·  by " + first.getReleasedByName());
        check("a row carries the exam's NAME, not just its number",
                first.getExamName() != null && !first.getExamName().isBlank());
        check("...and both dates", first.getOpenTime() != null && first.getCloseTime() != null);
        check("...and who gave it out", first.getReleasedByName() != null);
        check("...and the code the class was read", first.getExecutionCode() != null);
        check("newest first", calendar.size() < 2
                || !calendar.get(0).getOpenTime().isBefore(calendar.get(1).getOpenTime()));

        // Nothing is filtered out: a past sitting is the record of the year.
        boolean anyFinished = false;
        LocalDateTime now = LocalDateTime.now();
        for (ExamExecution x : calendar) {
            if (x.getCloseTime().isBefore(now)) {
                anyFinished = true;
            }
        }
        check("finished sittings are still there", anyFinished);

        check("A TEACHER CANNOT READ THE WHOLE SCHOOL'S CALENDAR",
                !teacher.ask(RequestType.PRINCIPAL_CALENDAR, null).isOk());
        check("nor a coordinator",
                !coordinator.ask(RequestType.PRINCIPAL_CALENDAR, null).isOk());

        // ==============================================================
        System.out.println("2. THE TO-DO LIST");
        // ==============================================================
        check("a student has no to-do list", !studentCannotAsk());
        check("nor the principal", !head.ask(RequestType.MY_TODO, null).isOk());

        // Build one of each kind of line and watch them appear.
        List<TodoItem> before = (List<TodoItem>) teacher.ask(
                RequestType.MY_TODO, null).getPayload();
        int pendingBefore = countOf(before, "with the coordinator");

        String pendingExam = buildExam(false);
        List<TodoItem> afterWriting = (List<TodoItem>) teacher.ask(
                RequestType.MY_TODO, null).getPayload();
        System.out.println("   after writing one:");
        for (TodoItem item : afterWriting) {
            System.out.println("      " + (item.isMine() ? "[me]   " : "[them] ")
                    + item.getCount() + "  " + item.getTitle());
        }
        check("AN EXAM SHE WROTE APPEARS AS WAITING FOR THE COORDINATOR",
                countOf(afterWriting, "with the coordinator") == pendingBefore + 1);
        check("and it is NOT counted as her own work",
                lineWith(afterWriting, "with the coordinator") != null
             && !lineWith(afterWriting, "with the coordinator").isMine());
        check("every line points at a screen",
                afterWriting.stream().allMatch(i -> i.getScreen() != null));
        check("and no line is empty - a row saying nought is a row nobody reads",
                afterWriting.stream().allMatch(i -> i.getCount() > 0));

        // The coordinator sees it as hers to approve.
        List<TodoItem> hers = (List<TodoItem>) coordinator.ask(
                RequestType.MY_TODO, null).getPayload();
        TodoItem approving = lineWith(hers, "waiting for your approval");
        check("THE COORDINATOR SEES IT AS HERS TO APPROVE", approving != null);
        if (approving != null) {
            System.out.println("   coordinator: " + approving.getCount() + "  "
                    + approving.getTitle());
            check("and it is her own work to do", approving.isMine());
            check("pointing at the approval screen",
                    "/fxml/ExamApproval.fxml".equals(approving.getScreen()));
        }

        // Approve it: it leaves her list and becomes "never given to a class" on his.
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(pendingExam, 1, null));
        List<TodoItem> afterApproval = (List<TodoItem>) teacher.ask(
                RequestType.MY_TODO, null).getPayload();
        TodoItem toRelease = lineWith(afterApproval, "never given to a class");
        check("ONCE APPROVED IT BECOMES HERS TO RELEASE", toRelease != null);
        if (toRelease != null) {
            check("and that is her own work", toRelease.isMine());
            check("pointing at the release screen",
                    "/fxml/ExamRelease.fxml".equals(toRelease.getScreen()));
        }
        check("it is no longer with the coordinator",
                countOf(afterApproval, "with the coordinator") == pendingBefore);

        // A rejection is its own line.
        String rejectMe = buildExam(false);
        coordinator.ask(RequestType.EXAM_REJECT,
                new ExamDecision(rejectMe, 1, "Two of these were on last term's paper."));
        List<TodoItem> afterRejection = (List<TodoItem>) teacher.ask(
                RequestType.MY_TODO, null).getPayload();
        check("A REJECTED EXAM IS ITS OWN LINE",
                lineWith(afterRejection, "came back rejected") != null);

        // ==============================================================
        System.out.println("3. WHAT THE STAFF HAVE DONE");
        // ==============================================================
        List<ActivityEntry> log = (List<ActivityEntry>) head.ask(
                RequestType.PRINCIPAL_ACTIVITY, 200).getPayload();
        check("the principal may read it", log != null && !log.isEmpty());
        System.out.println("   " + log.size() + " entries, newest first:");
        for (int i = 0; i < Math.min(5, log.size()); i++) {
            ActivityEntry e = log.get(i);
            System.out.println("      " + e.getAt() + "  " + e.getUserName()
                    + " (" + e.getRole() + ")  " + e.getAction());
        }

        check("newest first", log.size() < 2
                || !log.get(0).getAt().isBefore(log.get(1).getAt()));
        check("every entry has a moment", log.stream().allMatch(e -> e.getAt() != null));
        check("...a person", log.stream().allMatch(e -> e.getUserName() != null));
        check("...and what she did", log.stream().allMatch(e -> e.getAction() != null));

        check("ONLY TEACHERS AND COORDINATORS", log.stream()
                .allMatch(e -> "TEACHER".equals(e.getRole()) || "COORDINATOR".equals(e.getRole())));

        boolean sawApproval = false, sawWriting = false, sawRejection = false;
        for (ActivityEntry e : log) {
            if ("Approved an exam".equals(e.getAction())) sawApproval = true;
            if ("Wrote an exam".equals(e.getAction()))    sawWriting = true;
            if ("Rejected an exam".equals(e.getAction())) sawRejection = true;
        }
        check("the exam written a moment ago is in it", sawWriting);
        check("so is the approval", sawApproval);
        check("and the rejection", sawRejection);

        // The detail is what the person was told at the time.
        ActivityEntry anApproval = null;
        for (ActivityEntry e : log) {
            if ("Approved an exam".equals(e.getAction()) && anApproval == null) {
                anApproval = e;
            }
        }
        check("the detail is the sentence she was given",
                anApproval != null && anApproval.getDetail() != null
                     && anApproval.getDetail().contains("approved"));
        check("...and it names the exam, not just its number",
                anApproval != null && anApproval.getDetail().contains("StaffViewTest exam"));
        System.out.println("   detail: " + (anApproval == null ? "-" : anApproval.getDetail()));

        // Reading a screen is not an action.
        int sizeBefore = log.size();
        head.ask(RequestType.PRINCIPAL_EXAMS, null);
        teacher.ask(RequestType.EXAM_LIST_MINE, null);
        teacher.ask(RequestType.MY_TODO, null);
        List<ActivityEntry> after = (List<ActivityEntry>) head.ask(
                RequestType.PRINCIPAL_ACTIVITY, 200).getPayload();
        check("READING A SCREEN IS NOT RECORDED", after.size() == sizeBefore);

        // A refusal changed nothing, so it is not recorded either.
        Conn stranger = new Conn();
        stranger.login("teacher5", "teacher5!T");
        stranger.ask(RequestType.EXAM_APPROVE, new ExamDecision(pendingExam, 1, null));
        List<ActivityEntry> afterRefusal = (List<ActivityEntry>) head.ask(
                RequestType.PRINCIPAL_ACTIVITY, 200).getPayload();
        check("and neither is an action that was REFUSED",
                afterRefusal.size() == sizeBefore);
        stranger.close();

        check("a teacher cannot read the log",
                !teacher.ask(RequestType.PRINCIPAL_ACTIVITY, 50).isOk());
        check("nor a coordinator",
                !coordinator.ask(RequestType.PRINCIPAL_ACTIVITY, 50).isOk());

        head.close();
        teacher.close();
        coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // -----------------------------------------------------------------

    private static boolean studentCannotAsk() throws Exception {
        String username;
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT username FROM users WHERE role='STUDENT' "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id = users.user_id AND s.status='IN_PROGRESS') "
                     + "ORDER BY username LIMIT 1")) {
            username = rs.next() ? rs.getString(1) : null;
        }
        Conn pupil = new Conn();
        pupil.login(username, username + "!S");
        boolean allowed = pupil.ask(RequestType.MY_TODO, null).isOk();
        pupil.close();
        return allowed;
    }

    private static int countSittings() throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM exam_execution")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int countOf(List<TodoItem> items, String titlePart) {
        TodoItem found = lineWith(items, titlePart);
        return found == null ? 0 : found.getCount();
    }

    private static TodoItem lineWith(List<TodoItem> items, String titlePart) {
        for (TodoItem item : items) {
            if (item.getTitle().contains(titlePart)) {
                return item;
            }
        }
        return null;
    }

    private static String buildExam(boolean approve) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Question q = new Question();
            q.setCourseCode("01");
            q.setName("StaffViewTest q " + System.nanoTime());
            q.setText("staff view q" + i + " " + System.nanoTime());
            q.setTopic("Staff view");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("StaffViewTest exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (course 01 may have reached 99 exams - reset the demo data)");
        }
        String examId = ((Exam) saved.getPayload()).getExamId();
        if (approve) {
            coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));
        }
        return examId;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(400);
        final HSTSClient client;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
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
