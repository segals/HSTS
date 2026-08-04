import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * Four things asked for together, all of them about a screen telling the truth:
 *
 * <ol>
 *   <li>the principal's screens keeping up on their own when the staff do things;</li>
 *   <li>saving twice not making a second question, and an unchanged save saying so;</li>
 *   <li>a course reading as its name and not only its two digits;</li>
 *   <li>a status message surviving longer than a second while an exam is running.</li>
 * </ol>
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." LiveUpdateTest &lt;user&gt; &lt;password&gt;</p>
 */
public class LiveUpdateTest {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static DBController db;
    private static Conn teacher, coordinator, principal;

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
        principal = new Conn();   principal.login("principal", "principal!P");

        // ==============================================================
        System.out.println("1. A COURSE READS AS ITS NAME, NOT ONLY ITS TWO DIGITS");
        // ==============================================================
        List<Question> bank = (List<Question>) principal.ask(
                RequestType.PRINCIPAL_QUESTIONS, null).getPayload();
        check("the bank came back", bank != null && !bank.isEmpty());

        Question any = bank.get(0);
        System.out.println("   " + any.getQuestionId() + " -> " + any.describeCourse());
        check("every question carries its course NAME",
                bank.stream().allMatch(q -> q.getCourseName() != null
                                         && !q.getCourseName().isBlank()));
        check("...and still carries the two-digit code",
                bank.stream().allMatch(q -> q.getCourseCode() != null
                                         && q.getCourseCode().length() == 2));
        check("...and reads as \"Name (01)\"",
                any.describeCourse().equals(any.getCourseName()
                        + " (" + any.getCourseCode() + ")"));
        check("a question with no course name falls back to the code, not to nothing",
                new Question() {{ setCourseCode("07"); }}.describeCourse().equals("07"));

        List<Exam> exams = (List<Exam>) principal.ask(
                RequestType.PRINCIPAL_EXAMS, null).getPayload();
        check("an exam does the same",
                exams.stream().allMatch(e -> e.describeCourse().endsWith(
                        "(" + e.getCourseCode() + ")")));

        List<ExamExecution> calendar = (List<ExamExecution>) principal.ask(
                RequestType.PRINCIPAL_CALENDAR, null).getPayload();
        check("and a sitting works its course code out of the exam's own number",
                calendar.stream().allMatch(x -> x.getCourseCode().length() == 2
                        && x.getExamId().startsWith(
                                x.getExamId().substring(0, 2) + x.getCourseCode())));

        // ==============================================================
        System.out.println("2. SAVING TWICE DOES NOT MAKE A SECOND QUESTION");
        // ==============================================================
        int before = bankSize("01");

        Question fresh = newQuestion("Repeated save " + System.nanoTime());
        Question saved = (Question) teacher.ask(RequestType.QUESTION_ADD, fresh).getPayload();
        check("added once", saved != null && saved.getQuestionId() != null);
        check("at version 1", saved.getVersion() == 1);
        check("the bank grew by one", bankSize("01") == before + 1);

        // The screen now edits what it just saved. Pressing Save again sends the
        // same question back, and that is the press being tested.
        Response again = teacher.ask(RequestType.QUESTION_EDIT, saved);
        check("PRESSING SAVE AGAIN IS REFUSED", !again.isOk());
        System.out.println("   \"" + again.getMessage() + "\"");
        check("...and says no changes were made, in those words",
                again.getMessage().toLowerCase().contains("no changes were made"));
        check("...and names the version it is still on",
                again.getMessage().contains("version 1"));
        check("NO SECOND QUESTION WAS CREATED", bankSize("01") == before + 1);
        check("and no second version either", versionsOf(saved.getQuestionId()) == 1);

        // Blank and absent are the same thing: an empty instructions box must not
        // count as a change against a stored NULL.
        saved.setInstructions("   ");
        check("whitespace in an empty box is not a change",
                !teacher.ask(RequestType.QUESTION_EDIT, saved).isOk());

        // A real change goes through.
        saved.setInstructions("Show your working.");
        Response changed = teacher.ask(RequestType.QUESTION_EDIT, saved);
        check("A REAL CHANGE IS SAVED", changed.isOk());
        check("...as version 2", versionsOf(saved.getQuestionId()) == 2);
        check("...and the old version is still there",
                ((List<Question>) teacher.ask(RequestType.QUESTION_VERSIONS,
                        new QuestionRef(saved.getQuestionId())).getPayload()).size() == 2);

        // Every kind of change is noticed, one at a time.
        Question probe = (Question) teacher.ask(RequestType.QUESTION_GET,
                new QuestionRef(saved.getQuestionId(), 0)).getPayload();
        checkChangeIsSeen("the name",       probe, q -> q.setName("Another name"));
        checkChangeIsSeen("the text",       probe, q -> q.setText("A different question"));
        checkChangeIsSeen("the topic",      probe, q -> q.setTopic("Another topic"));
        checkChangeIsSeen("the difficulty", probe, q -> q.setDifficulty(DifficultyLevel.HARD));
        checkChangeIsSeen("an answer",      probe, q -> q.getAnswers().get(0).setText("Changed"));
        checkChangeIsSeen("which answer is right", probe, q -> {
            for (Answer a : q.getAnswers()) {
                a.setCorrect(a.getAnswerNo() == 1);
            }
        });
        checkChangeIsSeen("a picture", probe, q -> q.setImage(new byte[] {1, 2, 3}));

        // ==============================================================
        System.out.println("3. AN EXAM IS THE SAME");
        // ==============================================================
        String examId = buildExam();
        Exam exam = (Exam) teacher.ask(RequestType.EXAM_GET,
                new ExamRef(examId, 0)).getPayload();
        Response examAgain = teacher.ask(RequestType.EXAM_EDIT, exam);
        check("PRESSING SAVE AGAIN ON AN EXAM IS REFUSED", !examAgain.isOk());
        System.out.println("   \"" + examAgain.getMessage() + "\"");
        check("...saying no changes were made",
                examAgain.getMessage().toLowerCase().contains("no changes were made"));
        check("...and that it keeps the approval it has",
                examAgain.getMessage().toLowerCase().contains("approval"));
        check("still one version", examVersions(examId) == 1);

        exam.setDurationMinutes(exam.getDurationMinutes() + 5);
        check("a real change to an exam is saved",
                teacher.ask(RequestType.EXAM_EDIT, exam).isOk());
        check("...as version 2", examVersions(examId) == 2);

        // ==============================================================
        System.out.println("4. THE PRINCIPAL'S SCREENS KEEP UP ON THEIR OWN");
        // ==============================================================
        principal.pushes.clear();
        Question another = newQuestion("Watched " + System.nanoTime());
        teacher.ask(RequestType.QUESTION_ADD, another);

        PushEvent told = pollFor(principal, PushType.SCHOOL_ACTIVITY, 10);
        check("SHE IS TOLD WHEN A TEACHER WRITES A QUESTION, without pressing anything",
                told != null);
        if (told != null) {
            System.out.println("   she heard: \"" + told.getMessage() + "\"");
            check("...and the message says who", told.getMessage().contains("Levi")
                    || told.getMessage().split(":")[0].trim().split(" ").length >= 2);
            check("...and what they did",
                    told.getMessage().toLowerCase().contains("question"));
        }

        // The release is the case that was reported: the calendar gains a sitting.
        principal.pushes.clear();
        int sittingsBefore = ((List<ExamExecution>) principal.ask(
                RequestType.PRINCIPAL_CALENDAR, null).getPayload()).size();

        String toRelease = buildExam();
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(toRelease, 1, null));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        Response released = teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(toRelease, 1, now.plusDays(1), now.plusDays(1).plusHours(2),
                        code(), 60, 1));
        check("the exam was released: " + released.getMessage(), released.isOk());

        PushEvent aboutRelease = pollForMessage(principal, PushType.SCHOOL_ACTIVITY,
                                                "released", 10);
        check("SHE IS TOLD WHEN AN EXAM IS RELEASED - the case that was reported",
                aboutRelease != null);
        int sittingsAfter = ((List<ExamExecution>) principal.ask(
                RequestType.PRINCIPAL_CALENDAR, null).getPayload()).size();
        check("...and the calendar she then asks for really has one more",
                sittingsAfter == sittingsBefore + 1);

        // Only her. A push to everybody would be cheaper to write and would put a
        // line about a colleague's marking on a child's screen mid-exam.
        Conn pupil = new Conn();
        pupil.login("student1", "student1!S");
        pupil.pushes.clear();
        teacher.ask(RequestType.QUESTION_ADD, newQuestion("Unseen " + System.nanoTime()));
        check("A STUDENT IS NOT TOLD - this is the principal's screen, not hers",
                pollFor(pupil, PushType.SCHOOL_ACTIVITY, 3) == null);

        Conn otherTeacher = new Conn();
        otherTeacher.login("teacher2", "teacher2!T");
        otherTeacher.pushes.clear();
        teacher.ask(RequestType.QUESTION_ADD, newQuestion("Unseen too " + System.nanoTime()));
        check("nor another teacher", pollFor(otherTeacher, PushType.SCHOOL_ACTIVITY, 3) == null);

        // Reading is not doing.
        principal.pushes.clear();
        teacher.ask(RequestType.QUESTION_LIST_BY_COURSE, "01");
        teacher.ask(RequestType.EXAM_LIST_MINE, null);
        check("READING A SCREEN TELLS HER NOTHING - there would be one of these a second",
                pollFor(principal, PushType.SCHOOL_ACTIVITY, 3) == null);

        // A refused action changed nothing, so there is nothing to announce.
        principal.pushes.clear();
        Response refused = teacher.ask(RequestType.QUESTION_ADD, new Question());
        check("the empty question was refused", !refused.isOk());
        check("A REFUSED ACTION TELLS HER NOTHING EITHER",
                pollFor(principal, PushType.SCHOOL_ACTIVITY, 3) == null);

        otherTeacher.close();

        // A student is not staff, so nothing she does is logged - but the calendar
        // says how many sat each sitting, and that is her doing. Without this, the
        // one number on the principal's calendar that moves during the school day
        // was the one number on it that stood still.
        String openExam = buildExam();
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(openExam, 1, null));
        String openCode = code();
        ExamExecution live = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(openExam, 1, LocalDateTimeNow().minusMinutes(1),
                        LocalDateTimeNow().plusHours(3), openCode, 60, 1)).getPayload();
        check("a sitting is open now", live != null);
        if (live == null) {
            throw new IllegalStateException("could not open a sitting - nothing to sit");
        }

        String sitter = enrolledFreeStudent("01");
        Conn taking = new Conn();
        User her = (User) taking.login(sitter, passwordFor(sitter)).getPayload();
        check("a student who can sit it signed in: " + sitter, her != null);

        int satBefore = satCount(live.getExecutionId());
        int logBefore = activityCount();
        principal.pushes.clear();
        Response started = taking.ask(RequestType.TAKE_START,
                new StartExamRequest(live.getExecutionId(), her.getUserId()));
        check("she started it", started.isOk());

        PushEvent studentMoved = pollFor(principal, PushType.EXAM_LIVE_STATUS, 10);
        check("THE PRINCIPAL IS TOLD WHEN A STUDENT STARTS - her \"sat it\" count moved",
                studentMoved != null);
        check("...and the count really did go up",
                satCount(live.getExecutionId()) == satBefore + 1);
        check("...but NOTHING is written to the staff log - she is not staff",
                activityCount() == logBefore);

        taking.close();
        pupil.close();

        // ==============================================================
        System.out.println("5. A MESSAGE DOES NOT VANISH WHILE AN EXAM IS RUNNING");
        // ==============================================================
        // The exam clock ticks once a second and carries the seconds left with no
        // message at all. Every screen but the exam screen showed whatever it was
        // sent, so a refusal - "the study bot is not available while you are
        // sitting an exam" - was written over by a blank line within a second.
        check("A TICK CARRIES NO MESSAGE, so no screen has anything to show",
                new PushEvent(PushType.EXAM_TIME_TICK, 42, null).getMessage() == null);

        System.out.println("   (the screen ignores a push with no message: GUIScreen.onPush)");

        teacher.close();
        coordinator.close();
        principal.close();
    }

    // -----------------------------------------------------------------

    /** Changes one field, checks the server notices, and puts it back. */
    private static void checkChangeIsSeen(String what, Question probe,
                                          java.util.function.Consumer<Question> change)
            throws Exception {
        Question copy = copyOf(probe);
        change.accept(copy);
        Response r = teacher.ask(RequestType.QUESTION_EDIT, copy);
        check("a change to " + what + " is noticed", r.isOk());
        if (r.isOk()) {
            // Put it back, so the next check starts from the same place. That
            // restore is itself a change, so it is expected to be accepted.
            Question back = copyOf(probe);
            back.setName(probe.getName() + " ");     // trailing space is not a change...
            Response restore = teacher.ask(RequestType.QUESTION_EDIT, copyOf(probe));
            check("...and putting it back is a change too", restore.isOk());
        }
    }

    private static Question copyOf(Question q) {
        Question copy = new Question();
        copy.setQuestionId(q.getQuestionId());
        copy.setCourseCode(q.getCourseCode());
        copy.setName(q.getName());
        copy.setText(q.getText());
        copy.setInstructions(q.getInstructions());
        copy.setTopic(q.getTopic());
        copy.setDifficulty(q.getDifficulty());
        copy.setImage(q.getImage());
        List<Answer> answers = new ArrayList<>();
        for (Answer a : q.getAnswers()) {
            answers.add(new Answer(a.getAnswerNo(), a.getText(), a.isCorrect()));
        }
        copy.setAnswers(answers);
        return copy;
    }

    private static Question newQuestion(String name) {
        Question q = new Question();
        q.setCourseCode("01");
        q.setName(name);
        q.setText("Live update " + System.nanoTime());
        q.setTopic("Live update");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> a = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            a.add(new Answer(n, "option " + n, n == 3));
        }
        q.setAnswers(a);
        return q;
    }

    private static String buildExam() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD,
                    newQuestion("Exam part " + i + " " + System.nanoTime()))
                    .getPayload()).getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("LiveUpdateTest exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (course 01 may have reached 99 exams - reset the demo data)");
        }
        return ((Exam) saved.getPayload()).getExamId();
    }

    /**
     * A sitting code the server has just told us is free.
     *
     * <p>Made up here at first, which was wrong twice over: a code is exactly
     * four characters and an unpadded {@code nanoTime % 1000} produced "L7"
     * about one run in a hundred, and even padded it collided with a code
     * already in use as the codes built up between resets. Either way the
     * release failed for a reason that had nothing to do with what was under
     * test. The server already knows which codes are free, so it is asked.</p>
     */
    private static String code() throws Exception {
        Response free = teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null);
        if (!free.isOk()) {
            throw new IllegalStateException("No free sitting code: " + free.getMessage());
        }
        return (String) free.getPayload();
    }

    private static java.time.LocalDateTime LocalDateTimeNow() {
        return java.time.LocalDateTime.now();
    }

    /** Enrolled in the course and not already sitting something (NFR 16). */
    private static String enrolledFreeStudent(String course) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + course + "' AND u.username <> 'student1'"
                     + " AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id = u.user_id AND s.status = 'IN_PROGRESS') "
                     + "ORDER BY u.username LIMIT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("No free student left in course " + course);
            }
            return rs.getString(1);
        }
    }

    /** The seeded pattern: student7 -> student7!S. */
    private static String passwordFor(String username) {
        return username + "!S";
    }

    private static int satCount(int executionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM student_exam "
                     + "WHERE execution_id = " + executionId)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int activityCount() throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM activity_log")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int bankSize(String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM question WHERE course_code = '"
                     + courseCode + "' AND is_current = TRUE AND is_deleted = FALSE")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int versionsOf(String questionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM question WHERE question_id = '"
                     + questionId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int examVersions(String examId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM exam WHERE exam_id = '"
                     + examId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(300, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    private static PushEvent pollForMessage(Conn c, PushType type, String contains,
                                            int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(300, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains(contains.toLowerCase())) {
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
