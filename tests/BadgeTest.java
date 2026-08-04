import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * The two UX changes: saying whose approval something is waiting for, and the
 * unread badges on the menu.
 *
 * <p>Every badge check is a <b>delta</b> - the count before an action, then the
 * count after - never an absolute number. The suites leave papers and exams behind
 * on purpose, so "she has three waiting" is true only on a freshly reset database
 * and a test that asserted it would fail for a reason that has nothing to do with
 * badges.</p>
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." BadgeTest &lt;user&gt; &lt;password&gt;</p>
 */
public class BadgeTest {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static DBController db;
    private static Conn teacher, coordinator;
    private static final Set<String> TAKEN = new LinkedHashSet<>();

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
        db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> { });
        server.setPort(PORT);
        server.listen();

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");      // teaches 01
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C"); // subject 01

        // ==============================================================
        System.out.println("1. the status says WHO it is waiting for");
        // ==============================================================
        System.out.println("   " + ExamStatus.PENDING_APPROVAL.getDisplayName());
        check("a saved exam names the role, not just 'pending'",
                "Waiting for Subject Coordinator approval"
                        .equals(ExamStatus.PENDING_APPROVAL.getDisplayName()));
        check("and the role is available on its own, for building sentences",
                "Subject Coordinator".equals(ExamStatus.PENDING_APPROVAL.getWaitingFor()));
        check("PENDING_APPROVAL is waiting for somebody",
                ExamStatus.PENDING_APPROVAL.isWaitingForApproval());
        check("APPROVED is waiting for nobody",
                !ExamStatus.APPROVED.isWaitingForApproval()
             && ExamStatus.APPROVED.getWaitingFor() == null);
        check("REJECTED is waiting for nobody's approval either",
                !ExamStatus.REJECTED.isWaitingForApproval());

        // The wording has to reach the teacher who just pressed Save, not only the
        // enum. This is the exact reply her screen shows.
        String examId = buildExam(false);
        Exam saved = (Exam) teacher.ask(RequestType.EXAM_GET,
                new ExamRef(examId, 1)).getPayload();
        System.out.println("   her exam reads: " + saved.getStatus().getDisplayName());
        check("the exam she just wrote carries that wording",
                saved.getStatus().getDisplayName().contains("Subject Coordinator"));

        // ==============================================================
        System.out.println("2. an unapproved mark says whose approval it needs");
        // ==============================================================
        Grade waiting = new Grade();
        waiting.setStudentName("Test Pupil");
        waiting.setFinalGrade(80);
        waiting.setApproved(false);
        check("it names the teacher", "the teacher".equals(waiting.getWaitingFor()));
        System.out.println("   " + waiting);
        check("and says so when written out",
                waiting.toString().contains("waiting for the teacher's approval"));
        waiting.setApproved(true);
        check("an approved one is waiting for nobody", waiting.getWaitingFor() == null);

        // ==============================================================
        System.out.println("3. the coordinator's badge");
        // ==============================================================
        int coordBefore = counts(coordinator).getExamsToApprove();
        List<Exam> herList = (List<Exam>) coordinator.ask(
                RequestType.EXAM_PENDING_FOR_COORDINATOR, null).getPayload();
        System.out.println("   badge " + coordBefore + ", list " + herList.size());
        check("THE BADGE EQUALS THE LIST BEHIND IT", coordBefore == herList.size());

        String pending = buildExam(false);                 // one more, unapproved
        int coordAfter = counts(coordinator).getExamsToApprove();
        System.out.println("   after another exam is sent: " + coordAfter);
        check("a new exam for approval adds exactly one", coordAfter == coordBefore + 1);

        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(pending, 1, null));
        int coordApproved = counts(coordinator).getExamsToApprove();
        System.out.println("   after she approves it: " + coordApproved);
        check("APPROVING IT TAKES THE BADGE BACK DOWN", coordApproved == coordBefore);

        // Rejecting clears it from her queue too - it is the author's problem now.
        String toReject = buildExam(false);
        int beforeReject = counts(coordinator).getExamsToApprove();
        coordinator.ask(RequestType.EXAM_REJECT,
                new ExamDecision(toReject, 1, "Two questions repeat last term's paper."));
        check("rejecting also clears it from her badge",
                counts(coordinator).getExamsToApprove() == beforeReject - 1);

        // ==============================================================
        System.out.println("4. the teacher's badge, and the student's");
        // ==============================================================
        String approvedExam = buildExam(true);
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        LocalDateTime now = LocalDateTime.now();
        ExamExecution sitting = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(approvedExam, 1, now.minusMinutes(1), now.plusHours(3),
                        code, 60, 1)).getPayload();
        check("a sitting to work with", sitting != null);

        String username = enrolledFreeStudent("01");
        Conn pupil = new Conn();
        Student her = (Student) pupil.login(username, username + "!S").getPayload();
        check("a student to work with", her != null);

        // She opens her results first, so "unread" starts from a known point.
        pupil.ask(RequestType.RESULTS_MINE, null);
        PendingCounts start = counts(pupil);
        System.out.println("   she starts with: " + start);
        check("nothing unread the moment after she has looked", start.getNewResults() == 0);
        check("but a sitting she can go into", start.getExamsToSit() >= 1);

        int teacherBefore = counts(teacher).getPapersToApprove();

        // She sits it and hands in.
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam paper = (StudentExam) pupil.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), her.getUserId())).getPayload();
        check("she is sitting it", paper != null);
        pupil.ask(RequestType.TAKE_SAVE_ANSWER, new AnswerChoice(paper.getSubmissionId(),
                paper.getQuestions().get(0).getQuestionId(),
                paper.getQuestions().get(0).getQuestionVersion(), 3));
        pupil.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());

        PendingCounts afterSitting = counts(pupil);
        System.out.println("   after she hands in: " + afterSitting);
        check("THE SITTING SHE HAS USED UP NO LONGER COUNTS",
                afterSitting.getExamsToSit() == start.getExamsToSit() - 1);
        check("and nothing is unread yet - it is not approved",
                afterSitting.getNewResults() == 0);

        int teacherAfter = counts(teacher).getPapersToApprove();
        System.out.println("   teacher's papers waiting: " + teacherBefore
                + " -> " + teacherAfter);
        check("HER PAPER IS NOW WAITING FOR THE TEACHER", teacherAfter == teacherBefore + 1);

        // Handing in marks the paper straight away, so this one does have a grade
        // row. The row that does NOT is one the clock closed - closeExpired finishes
        // the attempt and leaves the marking to whenever the teacher opens the
        // sitting. That is the case the LEFT JOIN exists for, and it is worth
        // producing rather than assuming, so a second girl is closed by the clock.
        check("a paper handed in by hand is marked at once",
                gradeRowExists(paper.getSubmissionId()));

        String secondName = enrolledFreeStudent("01");
        Conn second = new Conn();
        Student secondMe = (Student) second.login(secondName, secondName + "!S").getPayload();
        second.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam timedOut = (StudentExam) second.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), secondMe.getUserId()))
                .getPayload();
        check("a second girl is sitting it", timedOut != null);

        int beforeClock = counts(teacher).getPapersToApprove();
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE student_exam SET deadline = NOW() - INTERVAL 1 SECOND "
                           + "WHERE submission_id = " + timedOut.getSubmissionId());
        }
        PushEvent closed = pollFor(second, PushType.EXAM_AUTO_SUBMITTED, 30);
        check("the clock closed her paper", closed != null);
        Thread.sleep(400);
        check("A PAPER THE CLOCK CLOSED HAS NO MARK ROW YET",
                !gradeRowExists(timedOut.getSubmissionId()));
        check("AND IT IS STILL COUNTED - this is what the LEFT JOIN is for",
                counts(teacher).getPapersToApprove() == beforeClock + 1);
        second.close();

        // ==============================================================
        System.out.println("5. approving clears the teacher's badge and lights hers");
        // ==============================================================
        List<Grade> marks = (List<Grade>) teacher.ask(
                RequestType.GRADING_LIST, sitting.getExecutionId()).getPayload();
        Grade mine = null;
        for (Grade g : marks) {
            if (g.getSubmissionId() == paper.getSubmissionId()) {
                mine = g;
            }
        }
        check("her paper is in the marking list", mine != null);
        check("and it is shown as waiting for the teacher",
                mine != null && "the teacher".equals(mine.getWaitingFor()));

        Response published = teacher.ask(RequestType.GRADING_PUBLISH, new PublishRequest(
                paper.getSubmissionId(), mine.getFinalGrade(), null, null, List.of()));
        check("published", published.isOk());

        // Back to where it was, PLUS the timed-out paper from section 4 that nobody
        // has marked yet - which is honest: it really is still waiting for her.
        check("THE TEACHER'S BADGE GOES BACK DOWN",
                counts(teacher).getPapersToApprove() == teacherBefore + 1);

        PendingCounts afterPublish = counts(pupil);
        System.out.println("   her counts after publishing: " + afterPublish);
        check("AND SHE HAS ONE UNREAD RESULT", afterPublish.getNewResults() == 1);

        pupil.ask(RequestType.RESULTS_MINE, null);
        PendingCounts afterReading = counts(pupil);
        System.out.println("   after she opens her results: " + afterReading);
        check("READING THEM CLEARS THE BADGE", afterReading.getNewResults() == 0);

        // ==============================================================
        System.out.println("6. the principal has no badges, and asking is still safe");
        // ==============================================================
        Conn head = new Conn();
        head.login("principal", "principal!P");
        Response headCounts = head.ask(RequestType.PENDING_COUNTS, null);
        check("she may ask", headCounts.isOk());
        PendingCounts nothing = (PendingCounts) headCounts.getPayload();
        System.out.println("   " + nothing);
        check("and everything is nought - she approves nothing", nothing.total() == 0);
        head.close();

        // Not signed in at all: refused like every other request.
        Conn stranger = new Conn();
        check("a connection with nobody signed in is refused",
                !stranger.ask(RequestType.PENDING_COUNTS, null).isOk());
        stranger.close();

        // ==============================================================
        System.out.println("7. the badges move with NOTHING pressed");
        // ==============================================================
        // The counts were right from the first version; what was missing was the
        // announcement. A teacher sitting on her menu when a girl handed in, and a
        // class sitting on theirs when an exam was released, both saw nothing until
        // they clicked something - which is the one thing a badge must not do.
        Conn watchingTeacher = new Conn();
        watchingTeacher.login("teacher2", "teacher2!T");     // teaches 01, not busy above

        String username2 = enrolledFreeStudent("01");
        Conn pupil2 = new Conn();
        Student her2 = (Student) pupil2.login(username2, username2 + "!S").getPayload();

        String exam2 = buildExam(true);
        String code2 = (String) watchingTeacher.ask(
                RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        LocalDateTime now2 = LocalDateTime.now();

        pupil2.pushes.clear();
        ExamExecution live = (ExamExecution) watchingTeacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(exam2, 1, now2.minusMinutes(1), now2.plusHours(3),
                        code2, 60, 1)).getPayload();
        check("released", live != null);

        PushEvent toldOfExam = pollFor(pupil2, PushType.PENDING_COUNTS_CHANGED, 10);
        check("THE CLASS IS TOLD AN EXAM IS OPEN, with nobody pressing anything",
                toldOfExam != null);
        if (toldOfExam != null) {
            System.out.println("   student heard: " + toldOfExam.getMessage());
            check("and the message names the course and says it is open now",
                    toldOfExam.getMessage().contains("open now"));
        }
        check("...and her count really did go up",
                counts(pupil2).getExamsToSit() >= 1);

        // Now the other direction: she hands in, and her teacher hears it.
        watchingTeacher.pushes.clear();
        int watchingBefore = counts(watchingTeacher).getPapersToApprove();
        pupil2.ask(RequestType.TAKE_VALIDATE_CODE, code2);
        StudentExam paper2 = (StudentExam) pupil2.ask(RequestType.TAKE_START,
                new StartExamRequest(live.getExecutionId(), her2.getUserId())).getPayload();
        pupil2.ask(RequestType.TAKE_SUBMIT, paper2.getSubmissionId());

        // She is told when the girl STARTS as well, so the hand-in is looked for by
        // its wording rather than by taking whichever announcement arrives first.
        PushEvent toldOfPaper = pollForMessage(watchingTeacher,
                PushType.EXAM_LIVE_STATUS, "handed in", 10);
        check("THE TEACHER IS TOLD A PAPER LANDED, unprompted", toldOfPaper != null);
        if (toldOfPaper != null) {
            System.out.println("   teacher heard: " + toldOfPaper.getMessage());
        }
        // Exactly once. A second announcement of the same hand-in was added by
        // mistake while this was being written, and this is what caught it.
        check("and only once - no duplicate announcement",
                pollForMessage(watchingTeacher, PushType.EXAM_LIVE_STATUS,
                        "handed in", 3) == null);
        check("...and her count went up by one",
                counts(watchingTeacher).getPapersToApprove() == watchingBefore + 1);

        // ==============================================================
        System.out.println("7b. the author is told, and it waits for her");
        // ==============================================================
        // She IS pushed a message the moment the coordinator decides - but only if
        // she is signed in at that moment, and she usually is not. So the decision
        // also has to wait for her: a count on her menu and a dot beside the exam.
        Conn author = new Conn();
        author.login("teacher3", "teacher3!T");          // teaches 03, uninvolved above

        // Clear the slate: open her release list, which marks everything as seen.
        author.ask(RequestType.EXECUTION_RELEASABLE_EXAMS, null);
        check("nothing new for her once she has looked",
                counts(author).getExamsNewlyApproved() == 0);

        String hers = buildExamFor(author, "03");
        author.pushes.clear();
        Conn physics = new Conn();
        physics.login("coordinator2", "coordinator2!C");  // subject 02 = Mechanics
        Response decided = physics.ask(RequestType.EXAM_APPROVE,
                new ExamDecision(hers, 1, null));
        check("her coordinator approves it", decided.isOk());

        PushEvent told = pollFor(author, PushType.EXAM_APPROVED, 10);
        check("SHE IS TOLD AT ONCE", told != null);
        if (told != null) {
            System.out.println("   " + told.getMessage());
            check("and it names the exam", told.getMessage().contains(hers));
            check("and the course, which is what she actually remembers",
                    told.getMessage().contains("Mechanics"));
            check("and says which way it went", told.getMessage().contains("APPROVED"));
        }

        check("IT ALSO WAITS FOR HER - one new approval on her menu",
                counts(author).getExamsNewlyApproved() == 1);

        // The dot says WHICH one, next to the exam itself.
        List<Exam> readyToRelease = (List<Exam>) author.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        Exam dotted = null, plain = null;
        for (Exam e : readyToRelease) {
            if (e.getExamId().equals(hers)) dotted = e;
            else if (!e.isNewlyApproved() && plain == null) plain = e;
        }
        check("the new one is in her release list", dotted != null);
        check("AND IT CARRIES THE DOT", dotted != null && dotted.isNewlyApproved());
        check("while the ones she has seen before do not",
                plain == null || !plain.isNewlyApproved());

        // Looking is what spends it - the same rule as her results.
        check("looking clears the count",
                counts(author).getExamsNewlyApproved() == 0);
        List<Exam> secondLook = (List<Exam>) author.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        boolean anyDots = false;
        for (Exam e : secondLook) {
            if (e.isNewlyApproved()) anyDots = true;
        }
        check("and the dots with it", !anyDots);

        // A rejection reaches her the same way, and says so.
        String toBeRejected = buildExamFor(author, "03");
        author.pushes.clear();
        physics.ask(RequestType.EXAM_REJECT, new ExamDecision(toBeRejected, 1,
                "Question 2 was on last term's paper."));
        PushEvent refused = pollFor(author, PushType.EXAM_REJECTED, 10);
        check("A REJECTION REACHES HER TOO", refused != null);
        if (refused != null) {
            System.out.println("   " + refused.getMessage());
            check("naming the exam and the course",
                    refused.getMessage().contains(toBeRejected)
                 && refused.getMessage().contains("Mechanics"));
            check("saying it was rejected", refused.getMessage().contains("REJECTED"));
            check("and giving the reason", refused.getMessage().contains("last term"));
        }
        check("a rejected exam is not counted as an approval",
                counts(author).getExamsNewlyApproved() == 0);

        // Somebody else's exam approved in a course she teaches is not her news.
        String colleagues = buildExamFor(teacher, "01");
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(colleagues, 1, null));
        check("another teacher's approval does not land on her menu",
                counts(author).getExamsNewlyApproved() == 0);

        physics.close();
        author.close();

        // ==============================================================
        System.out.println("7c. who the menu says she is");
        // ==============================================================
        Response coContext = coordinator.ask(RequestType.MENU_CONTEXT, null);
        check("a coordinator may ask", coContext.isOk());
        MenuContext co = (MenuContext) coContext.getPayload();
        System.out.println("   coordinates " + co.getCoordinatedSubjectName()
                + ", teaches " + co.getTaughtCourses().size() + " course(s)");
        check("her subject comes back by NAME", co.getCoordinatedSubjectName() != null);
        check("and her courses are the ones she really teaches, not her whole subject",
                co.getTaughtCourses().size()
                        <= ((Teacher) coordinator.who()).getTaughtCourseCodes().size());
        for (Course c : co.getTaughtCourses()) {
            check("   " + c.getName() + " (" + c.getCourseCode() + ") is one of hers",
                    ((Teacher) coordinator.who()).getTaughtCourseCodes()
                            .contains(c.getCourseCode()));
        }
        check("a coordinator studies nothing", co.getEnrolledCourses().isEmpty());

        MenuContext pupilContext = (MenuContext) pupil.ask(
                RequestType.MENU_CONTEXT, null).getPayload();
        check("a student's courses come back named",
                !pupilContext.getEnrolledCourses().isEmpty()
             && pupilContext.getEnrolledCourses().get(0).getName() != null);
        check("and she teaches nothing", pupilContext.getTaughtCourses().isEmpty());
        check("and coordinates nothing", pupilContext.getCoordinatedSubjectName() == null);

        // ==============================================================
        System.out.println("8. a student's own figures");
        // ==============================================================
        Response herFigures = pupil.ask(RequestType.RESULTS_MY_STATISTICS, null);
        check("she may ask for them", herFigures.isOk());
        Report report = (Report) herFigures.getPayload();
        System.out.println("   " + herFigures.getMessage());
        check("a report comes back", report != null);
        if (report != null && report.getOverall() != null
                && report.getOverall().getGradeCount() > 0) {
            for (var line : report.getLines()) {
                System.out.println("      " + line.getLabel() + "  ·  "
                        + String.format("%.1f", line.getStatistics().getAverage())
                        + "  ·  " + line.getDetail());
            }
            check("it is titled as hers", "My results".equals(report.getTitle()));
            check("named for her", report.getSubjectName() != null);
            check("with a line per course she has marks in", !report.getLines().isEmpty());

            // Every figure must be hers alone, so the count across the courses has to
            // equal the number of approved marks she has - no more.
            int fromLines = 0;
            for (var line : report.getLines()) {
                fromLines += line.getStatistics().getGradeCount();
            }
            check("the course lines add up to the overall count",
                    fromLines == report.getOverall().getGradeCount());
            check("and that is the number of approved marks she has",
                    report.getOverall().getGradeCount() == approvedMarksOf(her.getUserId()));

            // Best course first: she is asking how she is doing, not for an index.
            double previous = 101;
            boolean ordered = true;
            for (var line : report.getLines()) {
                if (line.getStatistics().getAverage() > previous + 0.001) {
                    ordered = false;
                }
                previous = line.getStatistics().getAverage();
            }
            check("strongest course first", ordered);
        }

        // A teacher asking for a student's statistics gets nothing - this reply is
        // one girl's own marks, and the route to it is hers.
        check("a teacher cannot ask for it",
                !teacher.ask(RequestType.RESULTS_MY_STATISTICS, null).isOk());

        pupil2.close();
        watchingTeacher.close();
        pupil.close();
        teacher.close();
        coordinator.close();
        server.shutdown();
        db.disconnect();
    }

    private static int approvedMarksOf(String userId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery(
                 "SELECT COUNT(*) FROM grade g JOIN student_exam s "
               + "ON s.submission_id = g.submission_id "
               + "WHERE s.student_id = '" + userId + "' AND g.is_approved = TRUE")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    // -----------------------------------------------------------------

    /** The next push of this type whose message contains the words wanted. */
    private static PushEvent pollForMessage(Conn c, PushType type, String contains,
                                            int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(400, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains(contains.toLowerCase())) {
                return e;
            }
        }
        return null;
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

    private static PendingCounts counts(Conn who) throws Exception {
        Response r = who.ask(RequestType.PENDING_COUNTS, null);
        if (!r.isOk()) {
            throw new IllegalStateException("counts refused: " + r.getMessage());
        }
        return (PendingCounts) r.getPayload();
    }

    private static boolean gradeRowExists(int submissionId) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT 1 FROM grade WHERE submission_id = "
                     + submissionId)) {
            return rs.next();
        }
    }

    /** A fresh exam in course 01, optionally approved by its coordinator. */
    private static String buildExam(boolean approve) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Question q = new Question();
            q.setCourseCode("01");
            q.setText("badge q" + i + " " + System.nanoTime());
            q.setName("BadgeTest q " + System.nanoTime());
            q.setTopic("Badges");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("BadgeTest exam " + System.nanoTime());
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

    /** A fresh exam in one course, written by whoever is passed in. */
    private static String buildExamFor(Conn who, String course) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Question q = new Question();
            q.setCourseCode(course);
            q.setText("badge q" + i + " " + System.nanoTime());
            q.setName("BadgeTest q " + System.nanoTime());
            q.setTopic("Badges");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) who.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) who.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, ids)).getPayload();
        draft.setName("BadgeTest exam " + System.nanoTime());
        draft.setDurationMinutes(60);
        Response saved = who.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (that course may have reached 99 exams - reset the demo data)");
        }
        return ((Exam) saved.getPayload()).getExamId();
    }

    /** Enrolled, not mid-exam, and not one this harness already holds open (NFR 16). */
    private static String enrolledFreeStudent(String course) throws Exception {
        StringBuilder exclude = new StringBuilder();
        for (String used : TAKEN) {
            exclude.append(" AND u.username <> '").append(used).append("'");
        }
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code = '" + course + "'" + exclude
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
            }, reason -> System.out.println("   [client dropped] " + reason));
            client.openConnection();
        }

        private User signedIn;

        Response login(String u, String p) throws Exception {
            Response r = ask(RequestType.LOGIN, new Credentials(u, p));
            if (r.isOk() && r.getPayload() instanceof User me) {
                signedIn = me;
            }
            return r;
        }

        User who() {
            return signedIn;
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
