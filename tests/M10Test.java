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
 * The single "Approve and publish" button.
 *
 * <p>Marking used to take four separate presses. They are now one request, and the
 * thing that must not have been lost in the merge is requirement 52: a mark moved
 * by hand needs a reason. These checks exist to prove that, and to prove that a
 * refused publish leaves the paper exactly as it was - not half-written.</p>
 */
public class M10Test {

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

    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C");
        @SuppressWarnings("unchecked")
        List<Course> mine = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = mine.get(0).getCourseCode();

        System.out.println("0. an exam of 4 questions, correct answer is always option 3");
        List<String> qs = new ArrayList<>();
        for (int i = 0; i < 4; i++) qs.add(addQuestion("M10 q" + i + " " + System.nanoTime()));
        String examId = saveExam(qs);
        if (examId == null) {
            throw new IllegalStateException(
                    "The exam could not be saved. The most likely cause is that course "
                  + course + " has reached 99 exams, which is all the 6-digit exam id "
                  + "format allows - the suites accumulate. Reset the demo data.");
        }
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));

        LocalDateTime now = LocalDateTime.now();
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        ExamExecution exec = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(5), now.plusHours(3),
                        code, 90, 1)).getPayload();
        int execId = exec.getExecutionId();
        check("released", execId > 0);

        List<String> names = enrolled(db, course, 2);

        // Dana: 2 of 4 right -> 50
        Conn dana = new Conn();
        Student danaMe = (Student) dana.login(names.get(0), names.get(0) + "!S").getPayload();
        dana.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam danaPaper = (StudentExam) dana.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, danaMe.getUserId())).getPayload();
        answer(dana, danaPaper, 0, 3);
        answer(dana, danaPaper, 1, 3);
        answer(dana, danaPaper, 2, 1);
        dana.ask(RequestType.TAKE_SUBMIT, danaPaper.getSubmissionId());

        // Eve: all 4 right -> 100
        Conn eve = new Conn();
        Student eveMe = (Student) eve.login(names.get(1), names.get(1) + "!S").getPayload();
        eve.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam evePaper = (StudentExam) eve.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, eveMe.getUserId())).getPayload();
        for (int i = 0; i < 4; i++) answer(eve, evePaper, i, 3);
        eve.ask(RequestType.TAKE_SUBMIT, evePaper.getSubmissionId());

        int danaId = danaPaper.getSubmissionId();
        int eveId  = evePaper.getSubmissionId();
        teacher.ask(RequestType.GRADING_LIST, execId);          // forces the automatic marking

        // -------------------------------------------------------------
        System.out.println("1. acceptance test 3.2 - publish it exactly as the system marked it");
        Response asIs = teacher.ask(RequestType.GRADING_PUBLISH,
                new PublishRequest(eveId, 100, null, null, List.of()));
        check("accepted with no reason, because nothing was changed", asIs.isOk());
        System.out.println("   " + asIs.getMessage());
        Grade eveGrade = (Grade) asIs.getPayload();
        check("published", eveGrade.isApproved());
        check("the mark is the automatic one", eveGrade.getFinalGrade() == 100);
        check("and no explanation was invented", eveGrade.getManualChangeExplanation() == null);

        // -------------------------------------------------------------
        System.out.println("2. acceptance test 3.4 - moving the mark with no reason is refused");
        Response noReason = teacher.ask(RequestType.GRADING_PUBLISH,
                new PublishRequest(danaId, 90, "   ", "well done", List.of()));
        check("refused", !noReason.isOk());
        System.out.println("   " + noReason.getMessage());
        check("the message says a reason is needed",
                noReason.getMessage().toLowerCase().contains("reason"));
        check("and says nothing was published",
                noReason.getMessage().toLowerCase().contains("nothing has been published"));

        System.out.println("   ...and NOTHING was written - not the mark, not the comment");
        Grade after = markOf(teacher, danaId);
        check("the mark is untouched", after != null && after.getFinalGrade() == 50);
        check("it was NOT published", after != null && !after.isApproved());
        check("the comment that came with the bad request was not saved",
                after != null && after.getTeacherGeneralComment() == null);

        // -------------------------------------------------------------
        System.out.println("3. acceptance test 3.6 - an impossible mark is refused");
        for (int bad : new int[] { 105, -5, 101 }) {
            Response r = teacher.ask(RequestType.GRADING_PUBLISH,
                    new PublishRequest(danaId, bad, "a reason", null, List.of()));
            check(bad + " refused", !r.isOk());
        }
        Grade stillFifty = markOf(teacher, danaId);
        check("after three bad attempts the mark is still 50", stillFifty.getFinalGrade() == 50);
        check("and still not published", !stillFifty.isApproved());

        // -------------------------------------------------------------
        System.out.println("4. one press saves the mark, the reason, and every comment");
        MarkedExam paper = (MarkedExam) teacher.ask(RequestType.GRADING_GET, danaId).getPayload();
        List<CommentRequest> perQuestion = new ArrayList<>();
        for (ExamQuestion eq : paper.getAttempt().getQuestions()) {
            perQuestion.add(new CommentRequest(danaId, eq.getQuestionId(),
                    eq.getQuestionVersion(), "note on " + eq.getQuestionId()));
        }
        Response one = teacher.ask(RequestType.GRADING_PUBLISH, new PublishRequest(
                danaId, 60, "marked up for effort", "Good work overall.", perQuestion));
        check("accepted", one.isOk());
        System.out.println("   " + one.getMessage());

        Grade dg = markOf(teacher, danaId);
        check("the mark was saved", dg.getFinalGrade() == 60);
        check("the reason was saved with it",
                "marked up for effort".equals(dg.getManualChangeExplanation()));
        check("the automatic mark is still 50, not overwritten", dg.getAutoGrade() == 50);
        check("the overall comment was saved",
                "Good work overall.".equals(dg.getTeacherGeneralComment()));
        check("it was published", dg.isApproved());

        MarkedExam reread = (MarkedExam) teacher.ask(RequestType.GRADING_GET, danaId).getPayload();
        long withComments = reread.getGrade().getFeedback().stream()
                .filter(QuestionFeedback::hasComment).count();
        System.out.println("   " + withComments + " of 4 questions carry a comment");
        check("every question's comment was saved by the same press", withComments == 4);

        // -------------------------------------------------------------
        System.out.println("5. the student sees all of it, from that one press");
        @SuppressWarnings("unchecked")
        List<Grade> hers = (List<Grade>) dana.ask(RequestType.RESULTS_MINE, null).getPayload();
        Grade herRow = hers.stream().filter(g -> g.getSubmissionId() == danaId)
                .findFirst().orElse(null);
        check("her mark is there", herRow != null && herRow.getFinalGrade() == 60);
        MarkedExam herPaper = (MarkedExam) dana.ask(
                RequestType.RESULTS_MARKED_EXAM, danaId).getPayload();
        check("she sees the overall comment",
                "Good work overall.".equals(herPaper.getGrade().getTeacherGeneralComment()));
        check("she sees the per-question comments", herPaper.getGrade().getFeedback().stream()
                .filter(QuestionFeedback::hasComment).count() == 4);
        // This check used to read "she is NOT shown the reason", written as
        // `x == null || !x.isEmpty()` - which is true for very nearly every value,
        // so it asserted nothing. She does in fact receive the reason once the mark
        // is approved; her screen simply does not print it. No requirement says to
        // hide it, and requirement 52 keeps it precisely so a change can be
        // accounted for, so this records what is true rather than inventing a rule.
        check("the reason travels with her own approved record",
                "marked up for effort".equals(
                        herPaper.getGrade().getManualChangeExplanation()));

        // -------------------------------------------------------------
        System.out.println("6. acceptance test 3.12 - publishing again after approval");
        PushEvent told = null;
        dana.pushes.clear();
        Response again = teacher.ask(RequestType.GRADING_PUBLISH, new PublishRequest(
                danaId, 65, "recount", "Good work overall.", List.of()));
        check("accepted", again.isOk());
        System.out.println("   " + again.getMessage());
        check("the wording says it is an update, not a first publication",
                again.getMessage().toLowerCase().contains("updated"));
        check("the new mark is stored", markOf(teacher, danaId).getFinalGrade() == 65);
        told = pollFor(dana, PushType.GRADE_APPROVED, 5);
        check("she was told again, without asking", told != null);
        if (told != null) System.out.println("   " + told.getMessage());

        System.out.println("   a comment left off a later press is not wiped");
        check("the per-question comments are still there",
                ((MarkedExam) teacher.ask(RequestType.GRADING_GET, danaId).getPayload())
                        .getGrade().getFeedback().stream()
                        .filter(QuestionFeedback::hasComment).count() == 4);

        // -------------------------------------------------------------
        System.out.println("7. blanking a comment clears it");
        List<CommentRequest> blanked = new ArrayList<>();
        for (ExamQuestion eq : paper.getAttempt().getQuestions()) {
            blanked.add(new CommentRequest(danaId, eq.getQuestionId(),
                    eq.getQuestionVersion(), "   "));
        }
        check("accepted", teacher.ask(RequestType.GRADING_PUBLISH, new PublishRequest(
                danaId, 65, null, "", blanked)).isOk());
        MarkedExam cleared = (MarkedExam) teacher.ask(RequestType.GRADING_GET, danaId).getPayload();
        check("the per-question comments are gone", cleared.getGrade().getFeedback().stream()
                .noneMatch(QuestionFeedback::hasComment));
        check("the overall comment is gone",
                cleared.getGrade().getTeacherGeneralComment() == null);
        check("the mark and its reason survived",
                cleared.getGrade().getFinalGrade() == 65
             && "recount".equals(cleared.getGrade().getManualChangeExplanation()));

        // -------------------------------------------------------------
        System.out.println("8. permissions on the new request");
        Conn other = new Conn();
        other.login("teacher5", "teacher5!T");
        check("another teacher cannot publish this paper",
                !other.ask(RequestType.GRADING_PUBLISH,
                        new PublishRequest(danaId, 70, "mine now", null, List.of())).isOk());
        other.close();

        check("a student cannot publish her own mark",
                !dana.ask(RequestType.GRADING_PUBLISH,
                        new PublishRequest(danaId, 100, "I deserve it", null, List.of())).isOk());
        check("the mark is untouched by either attempt", markOf(teacher, danaId).getFinalGrade() == 65);

        System.out.println("9. a paper still being sat cannot be published (test 3.11)");
        Conn frank = new Conn();
        List<String> more = enrolled(db, course, 3);
        String third = more.stream().filter(n -> !n.equals(names.get(0))
                                             && !n.equals(names.get(1))).findFirst().orElse(null);
        if (third != null) {
            Student frankMe = (Student) frank.login(third, third + "!S").getPayload();
            frank.ask(RequestType.TAKE_VALIDATE_CODE, code);
            StudentExam inside = (StudentExam) frank.ask(RequestType.TAKE_START,
                    new StartExamRequest(execId, frankMe.getUserId())).getPayload();
            Response early = teacher.ask(RequestType.GRADING_PUBLISH, new PublishRequest(
                    inside.getSubmissionId(), 80, "too soon", null, List.of()));
            check("refused", !early.isOk());
            check("refused BECAUSE she is still sitting it",
                    early.getMessage().toLowerCase().contains("still sitting"));
            System.out.println("   " + early.getMessage());
            frank.close();
        } else {
            System.out.println("   (skipped: no third enrolled student)");
        }

        System.out.println("10. a paper that does not exist");
        check("refused", !teacher.ask(RequestType.GRADING_PUBLISH,
                new PublishRequest(999999, 80, "x", null, List.of())).isOk());
        check("an empty request is refused",
                !teacher.ask(RequestType.GRADING_PUBLISH, null).isOk());

        dana.close(); eve.close(); teacher.close(); coordinator.close();
        server.stopListening(); server.close();
    }

    // -----------------------------------------------------------------

    static Grade markOf(Conn who, int submissionId) throws Exception {
        Object payload = who.ask(RequestType.GRADING_GET, submissionId).getPayload();
        return (payload instanceof MarkedExam m) ? m.getGrade() : null;
    }

    static void answer(Conn who, StudentExam paper, int index, int option) throws Exception {
        ExamQuestion eq = paper.getQuestions().get(index);
        who.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(paper.getSubmissionId(),
                eq.getQuestionId(), eq.getQuestionVersion(), option));
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
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
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
        draft.setName("M10Test exam " + System.nanoTime());
        draft.setDurationMinutes(90);
        draft.setInstructionsForStudents("Answer all four.");
        draft.setNotesForTeacher("SECRET marking note");
        Response r = teacher.ask(RequestType.EXAM_SAVE, draft);
        return r.isOk() ? ((Exam) r.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text) throws Exception {
        Question q = new Question();
        q.setCourseCode(course); q.setText(text); q.setName("M10Test q " + System.nanoTime());
 q.setTopic("M10");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> a = new ArrayList<>();
        for (int i = 1; i <= 4; i++) a.add(new Answer(i, "option " + i, i == 3));
        q.setAnswers(a);
        return ((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload()).getQuestionId();
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(300);
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
