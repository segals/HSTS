import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 5 verification: approval, rejection with a reason, permissions,
 * and SERVER PUSH - the mechanism NFR 18 requires.
 */
public class M5Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher;
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

    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();

        teacher = new Conn();
        teacher.login("teacher1", "teacher1!T");
        @SuppressWarnings("unchecked")
        List<Course> mine = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = mine.get(0).getCourseCode();

        System.out.println("0. build two exams to decide on");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(addQuestion("M5 question " + i + " " + System.nanoTime(), "M5Topic"));
        }
        String examA = saveExam(ids);
        String examB = saveExam(ids);
        System.out.println("   exams " + examA + " and " + examB);
        check("two exams saved", examA != null && examB != null && !examA.equals(examB));

        System.out.println("1. a TEACHER cannot approve");
        check("teacher refused the pending list",
                !teacher.ask(RequestType.EXAM_PENDING_FOR_COORDINATOR, null).isOk());
        check("teacher refused an approval",
                !teacher.ask(RequestType.EXAM_APPROVE, new ExamDecision(examA, 1, null)).isOk());

        System.out.println("2. the coordinator sees her own subject's queue");
        Conn coordinator = new Conn();
        coordinator.login("coordinator1", "coordinator1!C");   // subject 01
        Response pending = coordinator.ask(RequestType.EXAM_PENDING_FOR_COORDINATOR, null);
        check("coordinator got the list", pending.isOk());
        @SuppressWarnings("unchecked")
        List<Exam> waiting = (List<Exam>) pending.getPayload();
        System.out.println("   " + waiting.size() + " waiting");
        check("both new exams are in it", waiting.stream().anyMatch(e -> e.getExamId().equals(examA))
                                       && waiting.stream().anyMatch(e -> e.getExamId().equals(examB)));
        check("every one is PENDING_APPROVAL",
                waiting.stream().allMatch(e -> e.getStatus() == ExamStatus.PENDING_APPROVAL));

        System.out.println("3. requirement 31 - another subject's coordinator is refused");
        Conn otherCoord = new Conn();
        otherCoord.login("coordinator3", "coordinator3!C");    // subject 03
        Response wrongSubject = otherCoord.ask(RequestType.EXAM_APPROVE,
                new ExamDecision(examA, 1, null));
        check("refused", !wrongSubject.isOk());
        System.out.println("   " + wrongSubject.getMessage());
        @SuppressWarnings("unchecked")
        List<Exam> hers = (List<Exam>) otherCoord.ask(
                RequestType.EXAM_PENDING_FOR_COORDINATOR, null).getPayload();
        check("and she does not even see them",
                hers.stream().noneMatch(e -> e.getExamId().equals(examA)));
        otherCoord.close();

        System.out.println("4. requirement 33 - a rejection needs a reason");
        check("no reason refused",
                !coordinator.ask(RequestType.EXAM_REJECT, new ExamDecision(examA, 1, null)).isOk());
        check("blank reason refused",
                !coordinator.ask(RequestType.EXAM_REJECT, new ExamDecision(examA, 1, "   ")).isOk());

        System.out.println("5. PUSH - the teacher is told, without asking");
        teacher.pushes.clear();
        String reason = "Question 2 is off-syllabus. Replace it before resubmitting.";
        Response rejected = coordinator.ask(RequestType.EXAM_REJECT,
                new ExamDecision(examA, 1, reason));
        check("rejection accepted", rejected.isOk());

        PushEvent push = teacher.pushes.poll(10, TimeUnit.SECONDS);
        check("the teacher received a push without asking for anything", push != null);
        if (push != null) {
            System.out.println("   push: " + push.getType());
            System.out.println("   " + push.getMessage().replace("\n", " | "));
            check("it is an EXAM_REJECTED event", push.getType() == PushType.EXAM_REJECTED);
            check("it names the exam", push.getMessage().contains(examA));
            check("IT CARRIES THE REASON", push.getMessage().contains("off-syllabus"));
        }

        System.out.println("5b. the coordinator can OPEN an exam in a course she does not teach");
        Response opened = coordinator.ask(RequestType.EXAM_GET, new ExamRef(examB, 1));
        check("coordinator can read the exam she must approve", opened.isOk());
        if (!opened.isOk()) System.out.println("   " + opened.getMessage());

        System.out.println("6. the rejection is also stored (requirement 33, second half)");
        Exam storedA = (Exam) teacher.ask(RequestType.EXAM_GET, new ExamRef(examA, 1)).getPayload();
        check("status is REJECTED", storedA.getStatus() == ExamStatus.REJECTED);
        check("reason stored verbatim", reason.equals(storedA.getRejectionReason()));

        System.out.println("7. APPROVE the other one, and push again");
        teacher.pushes.clear();
        Response approved = coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examB, 1, null));
        check("approval accepted", approved.isOk());
        PushEvent approvePush = teacher.pushes.poll(10, TimeUnit.SECONDS);
        check("teacher told about the approval", approvePush != null
                && approvePush.getType() == PushType.EXAM_APPROVED);
        Exam storedB = (Exam) teacher.ask(RequestType.EXAM_GET, new ExamRef(examB, 1)).getPayload();
        check("status is APPROVED", storedB.getStatus() == ExamStatus.APPROVED);

        System.out.println("8. a decided exam cannot be decided twice");
        Response again = coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examB, 1, null));
        check("second decision refused", !again.isOk());
        System.out.println("   " + again.getMessage());

        System.out.println("9. decided exams leave the queue");
        @SuppressWarnings("unchecked")
        List<Exam> after = (List<Exam>) coordinator.ask(
                RequestType.EXAM_PENDING_FOR_COORDINATOR, null).getPayload();
        check("neither is still waiting",
                after.stream().noneMatch(e -> e.getExamId().equals(examA) || e.getExamId().equals(examB)));

        System.out.println("10. PUSH the other way - a new exam reaches the coordinator");
        coordinator.pushes.clear();
        String examC = saveExam(ids);
        PushEvent arrival = coordinator.pushes.poll(10, TimeUnit.SECONDS);
        check("coordinator told about the new exam, unprompted", arrival != null);
        if (arrival != null) {
            System.out.println("   " + arrival.getMessage());
            check("correct event type", arrival.getType() == PushType.EXAM_AWAITING_APPROVAL);
            check("names the new exam", arrival.getMessage().contains(examC));
        }

        System.out.println("11. editing a rejected exam sends it back for approval");
        Exam toFix = (Exam) teacher.ask(RequestType.EXAM_GET, new ExamRef(examA, 1)).getPayload();
        toFix.setName("M5Test exam " + System.nanoTime());
        toFix.setDurationMinutes(75);
        Response reSubmitted = teacher.ask(RequestType.EXAM_EDIT, toFix);
        check("edit accepted", reSubmitted.isOk());
        Exam v2 = (Exam) teacher.ask(RequestType.EXAM_GET, new ExamRef(examA, 2)).getPayload();
        check("version 2 is PENDING_APPROVAL again", v2.getStatus() == ExamStatus.PENDING_APPROVAL);
        check("and its rejection reason was cleared", v2.getRejectionReason() == null);
        Exam v1 = (Exam) teacher.ask(RequestType.EXAM_GET, new ExamRef(examA, 1)).getPayload();
        check("version 1 is still REJECTED with its reason",
                v1.getStatus() == ExamStatus.REJECTED && reason.equals(v1.getRejectionReason()));

        System.out.println("12. a push to somebody who is offline is harmless");
        teacher.close();                       // teacher signs off
        Thread.sleep(400);
        @SuppressWarnings("unchecked")
        List<Exam> queue = (List<Exam>) coordinator.ask(
                RequestType.EXAM_PENDING_FOR_COORDINATOR, null).getPayload();
        Exam target = queue.stream().filter(e -> e.getExamId().equals(examA)).findFirst().orElse(null);
        check("the resubmitted exam is in the queue", target != null);
        if (target != null) {
            Response offline = coordinator.ask(RequestType.EXAM_REJECT,
                    new ExamDecision(target.getExamId(), target.getVersion(), "still not right"));
            check("decision succeeds even though the author is offline", offline.isOk());
        }

        coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    static String saveExam(List<String> questionIds) throws Exception {
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, questionIds)).getPayload();
        draft.setName("M5Test exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        draft.setInstructionsForStudents("Answer everything.");
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        return saved.isOk() ? ((Exam) saved.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text, String topic) throws Exception {
        Question q = new Question();
        q.setCourseCode(course);
        q.setText(text);
        q.setName("M5Test q " + System.nanoTime());
        q.setTopic(topic);
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> answers = new ArrayList<>();
        for (int i = 1; i <= 4; i++) answers.add(new Answer(i, "option " + i, i == 1));
        q.setAnswers(answers);
        return ((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload()).getQuestionId();
    }

    /** Keeps replies and pushes in separate queues, exactly as the real client does. */
    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(60);
        final BlockingQueue<PushEvent> pushes = new ArrayBlockingQueue<>(60);
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
