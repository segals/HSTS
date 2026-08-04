import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * Milestones 11 and 12: the teacher's results and histogram (SUC-11 / מתווה 10)
 * and the principal's read-only browse (SUC-12 / מתווה 11).
 *
 * <p><b>There are no acceptance tests for either.</b> The submitted Assignment 1
 * covers SUC-3, 7, 9 and 10 only. These checks come from מתווה scenarios 10 and 11
 * and from requirements 54, 55, 59 and 62 - which are quoted beside each one so a
 * reader can tell what is required from what is merely implemented.</p>
 *
 * <p>Runs against the seeded demo data, which is arranged so requirement 59 can be
 * proved: Noa Levi wrote two geometry exams and Maya Cohen released one of them.</p>
 */
public class M11Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;

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

    @SuppressWarnings("unchecked")
    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();

        Conn noa = new Conn();    noa.login("teacher1", "teacher1!T");      // Noa Levi
        Conn maya = new Conn();   maya.login("teacher2", "teacher2!T");     // Maya Cohen
        Conn other = new Conn();  other.login("teacher5", "teacher5!T");    // teaches Poetry
        Conn head = new Conn();   head.login("principal", "principal!P");
        Conn pupil = new Conn();

        // ==============================================================
        System.out.println("1. REQUIREMENT 59 - every exam she WROTE, whoever ran it");
        List<Exam> noaWrote = (List<Exam>) noa.ask(RequestType.TEACHER_REPORT_EXAMS, null)
                .getPayload();
        System.out.println("   Noa wrote: " + ids(noaWrote));
        check("Noa has exams of her own", !noaWrote.isEmpty());
        check("every one of them names her as the author",
                noaWrote.stream().allMatch(e -> "Noa Levi".equals(e.getAuthorName())));

        List<Exam> mayaWrote = (List<Exam>) maya.ask(RequestType.TEACHER_REPORT_EXAMS, null)
                .getPayload();
        System.out.println("   Maya wrote: " + ids(mayaWrote));
        check("Maya's list is different from Noa's",
                !ids(noaWrote).equals(ids(mayaWrote)));
        check("Maya is not given an exam Noa wrote",
                mayaWrote.stream().noneMatch(e -> ids(noaWrote).contains(e.getExamId())));

        // The exam Noa wrote and Maya released - the whole point of requirement 59.
        String sharedExam = null;
        for (Exam e : noaWrote) {
            List<ExamExecution> xs = (List<ExamExecution>) noa.ask(
                    RequestType.TEACHER_REPORT_SITTINGS, e.getExamId()).getPayload();
            for (ExamExecution x : xs) {
                if (!"Noa Levi".equals(x.getReleasedByName())) {
                    sharedExam = e.getExamId();
                    System.out.println("   exam " + e.getExamId() + " was written by Noa"
                                     + " and released by " + x.getReleasedByName());
                }
            }
        }
        check("an exam Noa wrote was handed out by ANOTHER teacher", sharedExam != null);
        check("and Noa can still see its results",
                sharedExam != null && noa.ask(RequestType.TEACHER_REPORT_RESULTS,
                        ResultsQuery.wholeExam(sharedExam, 1)).isOk());

        // ==============================================================
        System.out.println("2. a teacher who neither wrote nor ran it is refused");
        Response refused = other.ask(RequestType.TEACHER_REPORT_RESULTS,
                ResultsQuery.wholeExam(noaWrote.get(0).getExamId(), 1));
        check("refused", !refused.isOk());
        System.out.println("   " + refused.getMessage());
        check("and told why", refused.getMessage().toLowerCase().contains("did not write"));
        check("she cannot list its sittings either",
                !other.ask(RequestType.TEACHER_REPORT_SITTINGS,
                        noaWrote.get(0).getExamId()).isOk());

        // ==============================================================
        System.out.println("3. requirement 55 - a student may not reach any of this");
        List<String> studentNames = enrolledStudents(db, "01", 1);
        pupil.login(studentNames.get(0), studentNames.get(0) + "!S");
        check("a student cannot list exams to report on",
                !pupil.ask(RequestType.TEACHER_REPORT_EXAMS, null).isOk());
        check("nor ask for results",
                !pupil.ask(RequestType.TEACHER_REPORT_RESULTS,
                        ResultsQuery.wholeExam(noaWrote.get(0).getExamId(), 1)).isOk());
        check("nor use the principal's browse to get round it",
                !pupil.ask(RequestType.PRINCIPAL_RESULTS,
                        ResultsQuery.wholeExam(noaWrote.get(0).getExamId(), 1)).isOk());
        check("nor read the whole question bank through it",
                !pupil.ask(RequestType.PRINCIPAL_QUESTIONS, null).isOk());

        // ==============================================================
        System.out.println("4. מתווה 10 - the marks in a table AND as a histogram");
        // EVERY sitting of every exam, not just the newest of each. An exam can be
        // handed out many times (requirement 36), and this looked at xs.get(0) -
        // which is the most recent. The moment somebody released the same exam again
        // and nobody had sat it yet, a full class of eighteen sitting behind it
        // became invisible and this failed with an NPE two lines later. Found when a
        // teacher released one by hand during a demo.
        String midterm = null;
        ExamExecution fullSitting = null;
        for (Exam e : noaWrote) {
            List<ExamExecution> xs = (List<ExamExecution>) noa.ask(
                    RequestType.TEACHER_REPORT_SITTINGS, e.getExamId()).getPayload();
            if (xs == null) {
                continue;
            }
            for (ExamExecution x : xs) {
                if (x.getNumStarted() >= 15) {
                    midterm = e.getExamId();
                    fullSitting = x;
                }
            }
        }
        check("found the sitting with a full class in it", midterm != null);
        if (midterm == null) {
            throw new IllegalStateException(
                    "No sitting with 15+ students - reset the demo data before running this.");
        }

        ResultsReport report = (ResultsReport) noa.ask(RequestType.TEACHER_REPORT_RESULTS,
                ResultsQuery.sitting(midterm, 1, fullSitting.getExecutionId()))
                .getPayload();

        System.out.println("   " + report.getTitle() + "   |   " + report.getSubtitle());
        check("the table has a row per student", report.getGrades().size() >= 15);
        check("the marks and the statistics arrive TOGETHER, in one reply",
                report.getStatistics() != null && !report.getGrades().isEmpty());

        ExamStatistics stats = report.getStatistics();
        System.out.printf("   avg %.2f   median %.2f   over %d approved%n",
                stats.getAverage(), stats.getMedian(), stats.getGradeCount());
        check("requirement 54 - an average", stats.getAverage() > 0);
        check("requirement 54 - a median", stats.getMedian() > 0);
        check("requirement 54 - ten decile buckets",
                stats.getDeciles().length == ExamStatistics.DECILE_COUNT);

        int inBuckets = Arrays.stream(stats.getDeciles()).sum();
        System.out.println("   deciles " + Arrays.toString(stats.getDeciles()));
        check("every approved mark lands in exactly one bucket",
                inBuckets == stats.getGradeCount());
        check("the marks are spread, not all in one bucket",
                Arrays.stream(stats.getDeciles()).filter(n -> n > 0).count() >= 5);

        // The statistics must describe the marks that came with them.
        List<Integer> approved = new ArrayList<>();
        for (Grade g : report.getGrades()) {
            if (g.isApproved()) {
                approved.add(g.getFinalGrade());
            }
        }
        double sum = 0;
        for (int m : approved) {
            sum += m;
        }
        check("the average really is the average of the table's approved marks",
                Math.abs(stats.getAverage() - sum / approved.size()) < 0.001);
        check("the count matches the table", stats.getGradeCount() == approved.size());

        // ==============================================================
        System.out.println("5. one sitting versus every sitting together");
        ResultsReport whole = (ResultsReport) noa.ask(RequestType.TEACHER_REPORT_RESULTS,
                ResultsQuery.wholeExam(midterm, 1)).getPayload();
        check("the whole-exam view says so in words",
                whole.getSubtitle().contains("sitting"));
        check("it covers at least as many marks as one sitting",
                whole.getGrades().size() >= report.getGrades().size());
        check("its statistics are computed the same way",
                whole.getStatistics().getDeciles().length == ExamStatistics.DECILE_COUNT
             && Arrays.stream(whole.getStatistics().getDeciles()).sum()
                        == whole.getStatistics().getGradeCount());

        // ==============================================================
        System.out.println("6. a sitting that belongs to a different exam is refused");
        final String midtermId = midterm;
        String otherExam = noaWrote.stream().map(Exam::getExamId)
                .filter(x -> !x.equals(midtermId)).findFirst().orElse(null);
        if (otherExam != null) {
            Response mismatched = noa.ask(RequestType.TEACHER_REPORT_RESULTS,
                    ResultsQuery.sitting(otherExam, 1, fullSitting.getExecutionId()));
            check("refused", !mismatched.isOk());
            System.out.println("   " + mismatched.getMessage());
        }
        check("an exam that does not exist is refused",
                !noa.ask(RequestType.TEACHER_REPORT_RESULTS,
                        ResultsQuery.wholeExam("999999", 1)).isOk());
        check("an empty query is refused",
                !noa.ask(RequestType.TEACHER_REPORT_RESULTS, null).isOk());

        // ==============================================================
        System.out.println("7. MILESTONE 12 - requirement 62, the principal reads everything");
        List<Question> bank = (List<Question>) head.ask(
                RequestType.PRINCIPAL_QUESTIONS, null).getPayload();
        System.out.println("   " + bank.size() + " questions in the bank");
        check("she sees the whole question bank", bank.size() >= 50);
        check("across more than one course",
                bank.stream().map(Question::getCourseCode).distinct().count() > 1);
        // This check used to read `anyMatch(has answers) || first has none`, which
        // is true either way and so asserted nothing. It hid a real defect: the
        // list carries no answers, and the detail pane drew that empty list, so
        // the principal saw a question with no options under it. The list is
        // meant to be light; the fix was to fetch the full question on selection.
        check("the LIST is light - no answers shipped for all 80",
                bank.stream().allMatch(q -> q.getAnswers().isEmpty()));

        Question one = (Question) head.ask(RequestType.PRINCIPAL_QUESTION_GET,
                new QuestionRef(bank.get(0).getQuestionId(), bank.get(0).getVersion()))
                .getPayload();
        check("but one question comes back complete", one != null && one.getAnswers().size() == 4);
        check("with exactly one of them marked correct",
                one.getAnswers().stream().filter(Answer::isCorrect).count() == 1);
        check("a question that does not exist is refused",
                !head.ask(RequestType.PRINCIPAL_QUESTION_GET,
                        new QuestionRef("99999", 1)).isOk());
        check("and a teacher cannot use that request either",
                !noa.ask(RequestType.PRINCIPAL_QUESTION_GET,
                        new QuestionRef(bank.get(0).getQuestionId(), 1)).isOk());

        List<Exam> allExams = (List<Exam>) head.ask(RequestType.PRINCIPAL_EXAMS, null)
                .getPayload();
        System.out.println("   " + allExams.size() + " exams");
        check("she sees every exam", allExams.size() >= 5);
        check("including ones written by different teachers",
                allExams.stream().map(Exam::getAuthorName).distinct().count() > 1);
        check("and ones that are not approved",
                allExams.stream().anyMatch(e -> e.getStatus() != hsts.common.enums.ExamStatus.APPROVED));

        Exam full = (Exam) head.ask(RequestType.PRINCIPAL_EXAM_GET,
                new ExamRef(midterm, 1)).getPayload();
        check("she can open one exam in full", full != null && !full.getQuestions().isEmpty());
        check("with the correct answers visible", full.getQuestions().get(0).getQuestion()
                .getAnswers().stream().anyMatch(Answer::isCorrect));
        check("requirement 62 - including the author's private note",
                full.getNotesForTeacher() != null && !full.getNotesForTeacher().isBlank());
        System.out.println("   private note: " + full.getNotesForTeacher());

        ResultsReport headReport = (ResultsReport) head.ask(RequestType.PRINCIPAL_RESULTS,
                ResultsQuery.sitting(midterm, 1, fullSitting.getExecutionId()))
                .getPayload();
        check("she sees the results too", !headReport.getGrades().isEmpty());
        check("with the same statistics the teacher gets",
                Math.abs(headReport.getStatistics().getAverage() - stats.getAverage()) < 0.001);
        check("and the title says who wrote the exam",
                headReport.getTitle().contains("written by"));

        // ==============================================================
        System.out.println("8. read-only means there is no way in at all");
        check("a teacher cannot use the principal's browse",
                !noa.ask(RequestType.PRINCIPAL_QUESTIONS, null).isOk());
        check("nor her exam list",
                !noa.ask(RequestType.PRINCIPAL_EXAMS, null).isOk());
        System.out.println("   " + noa.ask(RequestType.PRINCIPAL_EXAMS, null).getMessage());

        // The principal has no write path anywhere in the system.
        check("the principal cannot add a question",
                !head.ask(RequestType.QUESTION_ADD, new Question()).isOk());
        check("nor save an exam", !head.ask(RequestType.EXAM_SAVE, new Exam()).isOk());
        check("nor approve one", !head.ask(RequestType.EXAM_APPROVE,
                new ExamDecision(midterm, 1, null)).isOk());
        check("nor release one", !head.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(midterm, 1, java.time.LocalDateTime.now(),
                        java.time.LocalDateTime.now().plusHours(1), "HEAD", 60, 1)).isOk());
        check("nor mark anything", !head.ask(RequestType.GRADING_SITTINGS, null).isOk());
        check("nor publish a mark", !head.ask(RequestType.GRADING_PUBLISH,
                new PublishRequest(1, 100, "because", null, List.of())).isOk());

        // ==============================================================
        System.out.println("9. an exam nobody has sat");
        String unsat = null;
        for (Exam e : allExams) {
            List<ExamExecution> xs = (List<ExamExecution>) head.ask(
                    RequestType.PRINCIPAL_SITTINGS, e.getExamId()).getPayload();
            if (xs.isEmpty()) {
                unsat = e.getExamId();
                break;
            }
        }
        check("there is one in the demo data", unsat != null);
        if (unsat != null) {
            Response empty = head.ask(RequestType.PRINCIPAL_RESULTS,
                    ResultsQuery.wholeExam(unsat, 1));
            check("asking for its results is not an error", empty.isOk());
            ResultsReport none = (ResultsReport) empty.getPayload();
            check("it just has nothing in it", none.getGrades().isEmpty());
            check("and no statistics to speak of", none.getStatistics().getGradeCount() == 0);
            System.out.println("   " + empty.getMessage());
        }

        noa.close(); maya.close(); other.close(); head.close(); pupil.close();
        server.stopListening(); server.close();
    }

    // -----------------------------------------------------------------

    static List<String> ids(List<Exam> exams) {
        List<String> out = new ArrayList<>();
        for (Exam e : exams) {
            out.add(e.getExamId());
        }
        return out;
    }

    static List<String> enrolledStudents(DBController db, String course, int howMany)
            throws Exception {
        List<String> names = new ArrayList<>();
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code='" + course + "' ORDER BY u.username LIMIT "
                     + howMany)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
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
}
