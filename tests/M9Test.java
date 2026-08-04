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
 * Milestones 9 and 10: marking, approving, statistics, and what a student may
 * see of her own results.
 */
public class M9Test {

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
        for (int i = 0; i < 4; i++) qs.add(addQuestion("M9 q" + i + " " + System.nanoTime()));
        String examId = saveExam(qs);
        if (examId == null) {
            throw new IllegalStateException(
                    "The exam could not be saved. The most likely cause is that course "
                  + course + " has reached 99 exams, which is all the 6-digit exam id "
                  + "format allows - the suites accumulate. Reset the demo data.");
        }
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));

        LocalDateTime now = LocalDateTime.now();
        // A code the server confirms is free. A hard-coded one would make this
        // test runnable exactly once, since codes are unique for ever.
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        Response releaseResponse = teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(5), now.plusHours(3),
                        code, 90, 1));
        check("release accepted (" + code + ")", releaseResponse.isOk());
        ExamExecution exec = (ExamExecution) releaseResponse.getPayload();
        int execId = exec.getExecutionId();
        check("released", execId > 0);

        List<String> names = enrolled(db, course, 3);

        // Ann answers 2 right, 1 wrong, 1 blank  -> 50
        Conn ann = new Conn();
        Student annMe = (Student) ann.login(names.get(0), names.get(0) + "!S").getPayload();
        ann.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam annPaper = (StudentExam) ann.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, annMe.getUserId())).getPayload();
        answer(ann, annPaper, 0, 3);   // right
        answer(ann, annPaper, 1, 3);   // right
        answer(ann, annPaper, 2, 1);   // wrong
        // question 4 left blank
        ann.ask(RequestType.TAKE_SUBMIT, annPaper.getSubmissionId());

        // Beth answers all 4 right -> 100
        Conn beth = new Conn();
        Student bethMe = (Student) beth.login(names.get(1), names.get(1) + "!S").getPayload();
        beth.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam bethPaper = (StudentExam) beth.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, bethMe.getUserId())).getPayload();
        for (int i = 0; i < 4; i++) answer(beth, bethPaper, i, 3);
        beth.ask(RequestType.TAKE_SUBMIT, bethPaper.getSubmissionId());

        // Cara starts and stays inside
        Conn cara = new Conn();
        Student caraMe = (Student) cara.login(names.get(2), names.get(2) + "!S").getPayload();
        cara.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam caraPaper = (StudentExam) cara.ask(RequestType.TAKE_START,
                new StartExamRequest(execId, caraMe.getUserId())).getPayload();
        check("two handed in, one still inside", caraPaper != null);

        System.out.println("1. AUTOMATIC MARKING");
        @SuppressWarnings("unchecked")
        List<Grade> marks = (List<Grade>) teacher.ask(RequestType.GRADING_LIST, execId).getPayload();
        Grade annGrade = byName(marks, annMe.getFullName());
        Grade bethGrade = byName(marks, bethMe.getFullName());
        System.out.println("   " + annMe.getFullName() + " = " + annGrade.getAutoGrade()
                         + ",  " + bethMe.getFullName() + " = " + bethGrade.getAutoGrade());
        check("2 of 4 right, 25 points each, gives 50", annGrade.getAutoGrade() == 50);
        check("all 4 right gives 100", bethGrade.getAutoGrade() == 100);
        check("the final mark starts equal to the automatic one",
                annGrade.getFinalGrade() == annGrade.getAutoGrade());
        check("nothing is approved yet", !annGrade.isApproved() && !bethGrade.isApproved());

        // This assertion used to read marks.size() == 2 - it asserted the bug.
        // Everybody who started must be listed, or the sittings list ("3 sat it")
        // and the student list disagree and the teacher cannot see who is missing.
        check("everybody who started is listed, not only those who handed in",
                marks.size() == 3);
        Grade caraRow = byName(marks, caraMe.getFullName());
        check("the student still sitting is in the list", caraRow != null);
        check("and she is flagged as not marked", !caraRow.isMarked());
        check("the two who handed in are marked", annGrade.isMarked() && bethGrade.isMarked());
        check("she carries no mark while she is still sitting",
                caraRow.getAutoGrade() == 0 && !caraRow.isApproved());
        check("her row still identifies her paper", caraRow.getSubmissionId() > 0);
        check("her status says so", "IN_PROGRESS".equals(caraRow.getSubmissionStatus()));

        // The two counts the teacher sees must agree with each other.
        @SuppressWarnings("unchecked")
        List<ExamExecution> sittings = (List<ExamExecution>)
                teacher.ask(RequestType.GRADING_SITTINGS, null).getPayload();
        ExamExecution thisSitting = sittings.stream()
                .filter(s -> s.getExecutionId() == execId).findFirst().orElse(null);
        check("the sitting is offered for marking", thisSitting != null);
        check("\"how many sat it\" matches the length of the student list",
                thisSitting.getNumStarted() == marks.size());
        check("one of them is still inside", thisSitting.getNumUnfinished() == 1);

        System.out.println("2. which questions were wrong, and blanks count as wrong");
        MarkedExam annMarked = (MarkedExam) teacher.ask(
                RequestType.GRADING_GET, annGrade.getSubmissionId()).getPayload();
        long wrongCount = annMarked.getGrade().getFeedback().stream()
                .filter(QuestionFeedback::isWrong).count();
        System.out.println("   " + wrongCount + " marked wrong");
        check("2 wrong: one answered badly, one left blank", wrongCount == 2);
        check("the marker sees which option was correct",
                annMarked.getAttempt().getQuestions().get(0).getQuestion()
                        .getAnswers().stream().anyMatch(Answer::isCorrect));

        System.out.println("3. acceptance test 3.11 - a paper still being sat cannot be marked");
        Integer caraSubmission = caraPaper.getSubmissionId();
        Response tooEarly = teacher.ask(RequestType.GRADING_GET, caraSubmission);
        check("refused", !tooEarly.isOk());
        System.out.println("   " + tooEarly.getMessage());
        check("and it cannot be approved either",
                !teacher.ask(RequestType.GRADING_APPROVE, caraSubmission).isOk());

        System.out.println("4. permissions");
        Conn other = new Conn();
        other.login("teacher5", "teacher5!T");
        check("another teacher cannot list the marks",
                !other.ask(RequestType.GRADING_LIST, execId).isOk());
        check("nor open a paper",
                !other.ask(RequestType.GRADING_GET, annGrade.getSubmissionId()).isOk());
        check("nor approve",
                !other.ask(RequestType.GRADING_APPROVE, annGrade.getSubmissionId()).isOk());
        other.close();
        check("a student cannot mark",
                !ann.ask(RequestType.GRADING_LIST, execId).isOk());
        check("REQUIREMENT 55 - a student cannot get the statistics",
                !ann.ask(RequestType.GRADING_STATISTICS, execId).isOk());

        System.out.println("5. acceptance tests 3.4 and 3.6 - changing a mark");
        check("no reason is refused", !teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 60, null)).isOk());
        check("a blank reason is refused", !teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 60, "   ")).isOk());
        Response outOfRange = teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 105, "generous"));
        check("105 is refused", !outOfRange.isOk());
        System.out.println("   " + outOfRange.getMessage());
        check("-5 is refused", !teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), -5, "harsh")).isOk());

        Response changed = teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 60, "Gave credit for question 3"));
        check("a proper change is accepted", changed.isOk());
        Grade afterChange = (Grade) changed.getPayload();
        check("the final mark is 60", afterChange.getFinalGrade() == 60);
        check("THE AUTOMATIC MARK IS STILL 50", afterChange.getAutoGrade() == 50);
        check("the reason was kept", "Gave credit for question 3"
                .equals(afterChange.getManualChangeExplanation()));
        check("it is visibly a hand change", afterChange.wasChangedByHand());

        System.out.println("6. comments (requirement 51)");
        String firstQ = annMarked.getAttempt().getQuestions().get(0).getQuestionId();
        int firstV = annMarked.getAttempt().getQuestions().get(0).getQuestionVersion();
        check("a note on one question saves", teacher.ask(RequestType.GRADING_QUESTION_COMMENT,
                new CommentRequest(annGrade.getSubmissionId(), firstQ, firstV,
                        "Watch your working here.")).isOk());
        check("a note on the whole paper saves", teacher.ask(RequestType.GRADING_GENERAL_COMMENT,
                CommentRequest.general(annGrade.getSubmissionId(), "A good effort.")).isOk());

        System.out.println("7. acceptance test 4.2 - nothing reaches her before approval");
        @SuppressWarnings("unchecked")
        List<Grade> annSeesBefore = (List<Grade>) ann.ask(RequestType.RESULTS_MINE, null).getPayload();
        // Not a count: she may have sat other exams in other test runs, and
        // listing them all is correct. What matters is that THIS one is there
        // and carries no mark yet.
        Grade thisOneBefore = annSeesBefore.stream()
                .filter(g -> g.getSubmissionId() == annGrade.getSubmissionId())
                .findFirst().orElse(null);
        check("this exam is listed", thisOneBefore != null);
        check("but with no mark on it", thisOneBefore != null
                && thisOneBefore.getFinalGrade() == 0 && !thisOneBefore.isApproved());
        Response tooSoon = ann.ask(RequestType.RESULTS_MARKED_EXAM, annGrade.getSubmissionId());
        check("and the paper itself is refused", !tooSoon.isOk());
        System.out.println("   " + tooSoon.getMessage());

        System.out.println("8. approving, and the push that goes with it");
        ann.pushes.clear();
        Response approved = teacher.ask(RequestType.GRADING_APPROVE, annGrade.getSubmissionId());
        check("approved", approved.isOk());
        PushEvent told = pollFor(ann, PushType.GRADE_APPROVED, 8);
        check("SHE WAS TOLD, without asking", told != null);
        if (told != null) System.out.println("   " + told.getMessage());

        System.out.println("9. what she sees now");
        Response hers = ann.ask(RequestType.RESULTS_MARKED_EXAM, annGrade.getSubmissionId());
        check("her paper is released to her", hers.isOk());
        MarkedExam herView = (MarkedExam) hers.getPayload();
        check("the mark is 60", herView.getGrade().getFinalGrade() == 60);
        check("acceptance test 4.3 - she can see which option was correct",
                herView.getAttempt().getQuestions().get(0).getQuestion()
                        .getAnswers().stream().anyMatch(Answer::isCorrect));
        check("acceptance test 4.4 - the comment on question 1 is there",
                herView.getGrade().feedbackFor(firstQ) != null
             && "Watch your working here."
                        .equals(herView.getGrade().feedbackFor(firstQ).getComment()));
        check("the general comment is there",
                "A good effort.".equals(herView.getGrade().getTeacherGeneralComment()));
        check("acceptance test 4.11 - her actual time is shown",
                herView.getAttempt().getActualDuration() != null);
        check("acceptance test 4.10 - the teacher's private notes are NOT included",
                herView.getAttempt().getInstructionsForStudents() == null
             || !herView.getAttempt().getInstructionsForStudents().contains("SECRET"));

        System.out.println("10. acceptance test 4.6 rewritten - another student's paper");
        Response someoneElse = ann.ask(RequestType.RESULTS_MARKED_EXAM,
                bethGrade.getSubmissionId());
        check("refused", !someoneElse.isOk());
        System.out.println("   " + someoneElse.getMessage());
        check("and the wording gives nothing away",
                someoneElse.getMessage().equals("That exam does not exist."));

        System.out.println("11. statistics (requirements 54, and tests 3.7, 3.8, 3.14)");
        ExamStatistics oneApproved = (ExamStatistics) teacher.ask(
                RequestType.GRADING_STATISTICS, execId).getPayload();
        System.out.println("   with 1 approved: avg " + oneApproved.getAverage()
                         + ", median " + oneApproved.getMedian());
        check("only approved marks count", oneApproved.getGradeCount() == 1);
        check("average is 60", Math.abs(oneApproved.getAverage() - 60) < 0.001);

        teacher.ask(RequestType.GRADING_APPROVE, bethGrade.getSubmissionId());
        ExamStatistics both = (ExamStatistics) teacher.ask(
                RequestType.GRADING_STATISTICS, execId).getPayload();
        System.out.println("   with 2 approved: avg " + both.getAverage()
                         + ", median " + both.getMedian());
        check("average of 60 and 100 is 80", Math.abs(both.getAverage() - 80) < 0.001);
        check("median of two is their mean", Math.abs(both.getMedian() - 80) < 0.001);
        int[] d = both.getDeciles();
        System.out.println("   deciles " + Arrays.toString(d));
        check("60 falls in the 51-60 bucket", d[ExamStatistics.bucketFor(60)] == 1);
        check("100 falls in the LAST bucket, not off the end",
                ExamStatistics.bucketFor(100) == 9 && d[9] == 1);
        check("bucket 0 is 0-10", ExamStatistics.bucketLabel(0).equals("0-10"));
        check("bucket 9 is 91-100", ExamStatistics.bucketLabel(9).equals("91-100"));
        check("0 lands in bucket 0", ExamStatistics.bucketFor(0) == 0);
        check("10 lands in bucket 0", ExamStatistics.bucketFor(10) == 0);
        check("11 lands in bucket 1", ExamStatistics.bucketFor(11) == 1);

        System.out.println("12. acceptance test 3.15 - statistics follow a change at once");
        teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 40, "Re-marked question 2"));
        ExamStatistics afterEdit = (ExamStatistics) teacher.ask(
                RequestType.GRADING_STATISTICS, execId).getPayload();
        System.out.println("   avg is now " + afterEdit.getAverage());
        check("average moved to 70 straight away",
                Math.abs(afterEdit.getAverage() - 70) < 0.001);

        System.out.println("13. acceptance test 3.12 - changing after approval tells her again");
        ann.pushes.clear();
        teacher.ask(RequestType.GRADING_CHANGE,
                GradeChange.forOne(annGrade.getSubmissionId(), 65, "Appeal upheld"));
        PushEvent again = pollFor(ann, PushType.GRADE_APPROVED, 8);
        check("she was told about the new mark", again != null);
        if (again != null) System.out.println("   " + again.getMessage());
        MarkedExam updated = (MarkedExam) ann.ask(RequestType.RESULTS_MARKED_EXAM,
                annGrade.getSubmissionId()).getPayload();
        check("she now sees 65", updated.getGrade().getFinalGrade() == 65);

        System.out.println("14. requirement 77 - a factor, clamped to 100");
        Response factored = teacher.ask(RequestType.GRADING_FACTOR,
                GradeChange.factor(execId, 10));
        check("applied", factored.isOk());
        System.out.println("   " + factored.getMessage());
        @SuppressWarnings("unchecked")
        List<Grade> afterFactor = (List<Grade>) teacher.ask(
                RequestType.GRADING_LIST, execId).getPayload();
        Grade annNow = byName(afterFactor, annMe.getFullName());
        Grade bethNow = byName(afterFactor, bethMe.getFullName());
        System.out.println("   " + annNow.getFinalGrade() + " and " + bethNow.getFinalGrade());
        check("65 became 75", annNow.getFinalGrade() == 75);
        check("100 STAYED 100, it did not become 110", bethNow.getFinalGrade() == 100);
        check("the factor is recorded", annNow.getFactor() == 10);

        System.out.println("15. acceptance test 3.10 - approve everything at once");
        cara.ask(RequestType.TAKE_SUBMIT, caraSubmission);
        Response all = teacher.ask(RequestType.GRADING_APPROVE_ALL, execId);
        check("accepted", all.isOk());
        System.out.println("   " + all.getMessage());
        @SuppressWarnings("unchecked")
        List<Grade> everything = (List<Grade>) teacher.ask(
                RequestType.GRADING_LIST, execId).getPayload();
        check("all three papers exist now", everything.size() == 3);
        check("and every one is approved",
                everything.stream().allMatch(Grade::isApproved));

        System.out.println("16. acceptance test 4.12 - a student who has sat nothing");
        Conn fresh = new Conn();
        // Must be a student who has sat NOTHING AT ALL. This used to ask for one
        // merely not enrolled in this course - true enough on an empty database,
        // but once the demo data existed she had results from her own courses and
        // the check failed for a reason that had nothing to do with what it tested.
        String noneUser = studentWhoHasSatNothing(db);
        check("there is a student who has never sat an exam", noneUser != null);
        fresh.login(noneUser, noneUser + "!S");
        Response empty = fresh.ask(RequestType.RESULTS_MINE, null);
        check("she gets an empty list, not an error", empty.isOk());
        check("with a message that explains it",
                empty.getMessage().toLowerCase().contains("not sat any"));
        fresh.close();

        ann.close(); beth.close(); cara.close();
        teacher.close(); coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    // ---------- helpers ----------

    static void answer(Conn who, StudentExam paper, int index, int option) throws Exception {
        ExamQuestion eq = paper.getQuestions().get(index);
        who.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(paper.getSubmissionId(),
                eq.getQuestionId(), eq.getQuestionVersion(), option));
    }

    static Grade byName(List<Grade> grades, String fullName) {
        return grades.stream().filter(g -> fullName.equals(g.getStudentName()))
                .findFirst().orElse(null);
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

    /** A student with no attempt at any exam, in any course. */
    static String studentWhoHasSatNothing(DBController db) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u WHERE u.role='STUDENT' "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "WHERE s.student_id = u.user_id) ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String nonEnrolled(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u WHERE u.role='STUDENT' "
                     + "AND u.user_id NOT IN (SELECT user_id FROM course_student) "
                     + "ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : "student40";
        }
    }

    static String saveExam(List<String> ids) throws Exception {
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, ids)).getPayload();
        draft.setName("M9Test exam " + System.nanoTime());
        draft.setDurationMinutes(90);
        draft.setInstructionsForStudents("Answer all four.");
        draft.setNotesForTeacher("SECRET marking note");
        Response r = teacher.ask(RequestType.EXAM_SAVE, draft);
        return r.isOk() ? ((Exam) r.getPayload()).getExamId() : null;
    }

    static String addQuestion(String text) throws Exception {
        Question q = new Question();
        q.setCourseCode(course); q.setText(text); q.setName("M9Test q " + System.nanoTime());
 q.setTopic("M9");
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
