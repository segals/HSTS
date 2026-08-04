import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.CodeAttemptDAO;
import hsts.server.dao.DBController;
import hsts.server.dao.SubmissionDAO;
import hsts.server.push.ExamClockService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 15: the six derived requirements, left until last on purpose.
 *
 * <table>
 *   <tr><td>19</td><td>a coordinator edits her whole subject's questions</td></tr>
 *   <tr><td>39</td><td>three wrong codes, then ten minutes out</td></tr>
 *   <tr><td>43</td><td>a popup at nine tenths of the time</td></tr>
 *   <tr><td>61</td><td>the teacher opens an extra attempt</td></tr>
 *   <tr><td>76</td><td>signed out for being idle</td></tr>
 *   <tr><td>77</td><td>a factor after approval</td></tr>
 * </table>
 *
 * <p>77 was built with milestone 9 and is re-checked here rather than assumed -
 * "it was done earlier" is not evidence.</p>
 */
public class M15Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher, coordinator;

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

        teacher = new Conn();     teacher.login("teacher1", "teacher1!T");   // teaches 01
        coordinator = new Conn(); coordinator.login("coordinator1", "coordinator1!C");

        // ==============================================================
        System.out.println("1. REQUIREMENT 19 - a coordinator edits her subject's questions");
        // coordinator1 runs subject 01 (Mathematics) and TEACHES course 02 (Algebra).
        // Course 01 (Plane Geometry) is in her subject but she does not teach it -
        // which is exactly the case the requirement is about.
        List<Course> hers = (List<Course>) coordinator.ask(
                RequestType.COURSE_LIST_MINE, null).getPayload();
        List<String> codes = new ArrayList<>();
        hers.forEach(c -> codes.add(c.getCourseCode()));
        System.out.println("   she is offered: " + codes);
        check("her own taught course is offered", codes.contains("02"));
        check("AND a course she does not teach but coordinates", codes.contains("01"));
        check("but not a course from another subject", !codes.contains("03"));

        check("she can read that course's bank",
                coordinator.ask(RequestType.QUESTION_LIST_BY_COURSE, "01").isOk());

        Question q = new Question();
        q.setCourseCode("01");
        q.setText("Coordinator-added question " + System.nanoTime());
        q.setName("M15Test q " + System.nanoTime());
        q.setTopic("Angles");
        q.setDifficulty(DifficultyLevel.MEDIUM);
        List<Answer> options = new ArrayList<>();
        for (int i = 1; i <= 4; i++) options.add(new Answer(i, "option " + i, i == 2));
        q.setAnswers(options);

        Response added = coordinator.ask(RequestType.QUESTION_ADD, q);
        check("SHE CAN ADD A QUESTION TO A COURSE SHE DOES NOT TEACH", added.isOk());
        System.out.println("   " + added.getMessage());
        Question stored = (Question) added.getPayload();

        stored.setText("Edited by the coordinator " + System.nanoTime());
        check("and edit one", coordinator.ask(RequestType.QUESTION_EDIT, stored).isOk());

        System.out.println("   ...but not another subject's");
        Question outside = new Question();
        outside.setCourseCode("03");            // Mechanics, subject 02
        outside.setText("Should be refused " + System.nanoTime());
        outside.setName("M15Test q " + System.nanoTime());
        outside.setTopic("Motion");
        outside.setDifficulty(DifficultyLevel.EASY);
        List<Answer> more = new ArrayList<>();
        for (int i = 1; i <= 4; i++) more.add(new Answer(i, "option " + i, i == 1));
        outside.setAnswers(more);
        Response refused = coordinator.ask(RequestType.QUESTION_ADD, outside);
        check("refused", !refused.isOk());
        System.out.println("   " + refused.getMessage());
        check("and told it is about her subject",
                refused.getMessage().toLowerCase().contains("your subject"));

        check("a plain teacher still cannot touch another course",
                !teacher.ask(RequestType.QUESTION_LIST_BY_COURSE, "03").isOk());

        // ==============================================================
        System.out.println("2. REQUIREMENT 39 - three wrong codes, then ten minutes out");
        // Enrolled in course 01 on purpose. The checks below need a code that is
        // genuinely correct FOR HER, and picking any free student made two of them
        // conditional - so the suite reported 58 checks one run and 59 the next,
        // which is useless for spotting a regression.
        String studentUser = enrolledFreeStudent(db, "01");
        Conn pupil = new Conn();
        pupil.login(studentUser, studentUser + "!S");
        new CodeAttemptDAO().clear(idOf(db, studentUser));   // a clean slate to start

        Response first = pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ1");
        check("wrong code refused", !first.isOk());
        System.out.println("   " + first.getMessage());
        check("and she is told how many tries are left",
                first.getMessage().contains("2 tries"));

        Response second = pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ2");
        check("the second is counted", second.getMessage().contains("1 try"));

        Response third = pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ3");
        check("the third locks her out", !third.isOk());
        System.out.println("   " + third.getMessage());
        check("and says so", third.getMessage().contains("10 minutes"));

        Response afterLock = pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ4");
        check("she is now blocked", !afterLock.isOk());
        System.out.println("   " + afterLock.getMessage());
        check("with a countdown, not a repeat of the same message",
                afterLock.getMessage().contains("Try again in"));

        // The lock beats a CORRECT code too, or it is no lock at all.
        String liveCode = openCodeFor(db, "01");
        check("the demo data has a sitting of her course open now", liveCode != null);
        Response evenCorrect = pupil.ask(RequestType.TAKE_VALIDATE_CODE, liveCode);
        check("EVEN THE RIGHT CODE IS REFUSED WHILE SHE IS LOCKED", !evenCorrect.isOk());
        check("still the lockout message",
                evenCorrect.getMessage().contains("Try again in"));

        check("the lock is stored, not held in memory",
                new CodeAttemptDAO().remainingLock(idOf(db, studentUser)) != null);
        Duration left = new CodeAttemptDAO().remainingLock(idOf(db, studentUser));
        check("and it is about ten minutes",
                left.toMinutes() >= 8 && left.toMinutes() <= 10);

        System.out.println("   a badly-formed code is a typing slip, not a guess");
        new CodeAttemptDAO().clear(idOf(db, studentUser));
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "AB");
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "AB");
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "AB");
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "AB");
        check("four malformed codes do NOT lock her out",
                new CodeAttemptDAO().remainingLock(idOf(db, studentUser)) == null);

        System.out.println("   and a correct code wipes the slate");
        new CodeAttemptDAO().clear(idOf(db, studentUser));
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ5");
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, "QQQ6");
        check("two failures recorded", new CodeAttemptDAO().triesLeft(idOf(db, studentUser)) == 1);
        pupil.ask(RequestType.TAKE_VALIDATE_CODE, liveCode);
        check("a correct code resets the count to three",
                new CodeAttemptDAO().triesLeft(idOf(db, studentUser))
                        == CodeAttemptDAO.STRIKES);
        new CodeAttemptDAO().clear(idOf(db, studentUser));
        pupil.close();

        // ==============================================================
        System.out.println("3. REQUIREMENT 43 - the popup at nine tenths of the time");
        // Build a sitting, seat a student, then move her deadline so that she is
        // past the 90% mark. The clock does the rest.
        String examId = buildExam(db);
        String code = (String) teacher.ask(RequestType.EXECUTION_SUGGEST_CODE, null).getPayload();
        LocalDateTime now = LocalDateTime.now();
        ExamExecution sitting = (ExamExecution) teacher.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(examId, 1, now.minusMinutes(2), now.plusHours(3),
                        code, 100, 2)).getPayload();
        check("a sitting to test with", sitting != null);

        String warnUser = enrolledFreeStudent(db, "01");
        Conn warned = new Conn();
        Student warnedMe = (Student) warned.login(warnUser, warnUser + "!S").getPayload();
        warned.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam paper = (StudentExam) warned.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), warnedMe.getUserId()))
                .getPayload();
        check("she is sitting it", paper != null);

        // BOTH ends move. The warning is measured across her own start-to-deadline
        // window, so pushing only the deadline to NOW()+7 would leave her with a
        // SEVEN-minute exam and seven minutes remaining - a hundred per cent left,
        // not ten. Winding the start back keeps the window at 100 minutes with 7 to
        // go, which is what the last tenth actually means.
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("UPDATE student_exam SET "
                           + "start_time = NOW() - INTERVAL 93 MINUTE, "
                           + "deadline   = NOW() + INTERVAL 7 MINUTE "
                           + "WHERE submission_id = " + paper.getSubmissionId());
        }
        warned.pushes.clear();
        PushEvent popup = pollFor(warned, PushType.EXAM_TIME_WARNING, 15);
        check("THE WARNING ARRIVES, unprompted", popup != null);
        System.out.println("   " + popup.getMessage());
        // SECONDS now, not minutes. The customer asked for wording that names both -
        // "you have 6 minutes and 42 seconds left" - and whole minutes cannot
        // produce that. It also read badly at the end: with fifty seconds to go the
        // old popup said "less than a minute left", which is true and useless.
        check("it carries the SECONDS left", popup.getPayload() instanceof Long);
        long secondsLeft = (Long) popup.getPayload();
        System.out.println("   " + secondsLeft + " seconds");
        check("about seven minutes' worth", secondsLeft >= 360 && secondsLeft <= 480);
        check("the message leads with the 90%", popup.getMessage().startsWith("90%"));
        check("and names minutes AND seconds",
                popup.getMessage().contains("minutes") && popup.getMessage().contains("seconds"));
        check("with no \"less than a minute\" wording",
                !popup.getMessage().toLowerCase().contains("less than a minute"));

        // The sentence itself, at three sizes, without waiting for a clock.
        check("6m42 reads properly",
                "90% of the exam time has gone. You have 6 minutes and 42 seconds left."
                        .equals(ExamClockService.describeWarning(402)));
        check("one minute is singular",
                ExamClockService.describeWarning(61)
                        .contains("1 minute and 1 second"));
        check("under a minute names only seconds",
                ExamClockService.describeWarning(45)
                        .equals("90% of the exam time has gone. You have 45 seconds left."));

        System.out.println("   ...and it is sent ONCE, not every second");
        PushEvent again = pollFor(warned, PushType.EXAM_TIME_WARNING, 4);
        check("no second warning", again == null);
        check("but the clock is still ticking",
                pollFor(warned, PushType.EXAM_TIME_TICK, 5) != null);

        // ==============================================================
        System.out.println("4. REQUIREMENT 61 - the teacher opens an extra attempt");
        warned.ask(RequestType.TAKE_SUBMIT, paper.getSubmissionId());
        warned.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam second2 = (StudentExam) warned.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), warnedMe.getUserId()))
                .getPayload();
        check("the sitting allows two attempts, so she may sit again", second2 != null);
        warned.ask(RequestType.TAKE_SUBMIT, second2.getSubmissionId());

        Response exhausted = warned.ask(RequestType.TAKE_VALIDATE_CODE, code);
        check("a third is refused", !exhausted.isOk());
        System.out.println("   " + exhausted.getMessage());

        Conn stranger = new Conn();
        stranger.login("teacher5", "teacher5!T");
        check("a teacher who did not release it cannot grant one",
                !stranger.ask(RequestType.LIVE_GRANT_ATTEMPT, new AttemptGrantRequest(
                        sitting.getExecutionId(), warnedMe.getUserId(), "not mine")).isOk());
        check("nor can the student grant herself one",
                !warned.ask(RequestType.LIVE_GRANT_ATTEMPT, new AttemptGrantRequest(
                        sitting.getExecutionId(), warnedMe.getUserId(), "please")).isOk());
        stranger.close();

        warned.pushes.clear();
        Response granted = teacher.ask(RequestType.LIVE_GRANT_ATTEMPT,
                new AttemptGrantRequest(sitting.getExecutionId(), warnedMe.getUserId(),
                        "her machine crashed"));
        check("THE TEACHER WHO RELEASED IT CAN", granted.isOk());
        System.out.println("   " + granted.getMessage());
        check("she is now allowed three in all", ((Integer) granted.getPayload()) == 3);

        PushEvent told = pollFor(warned, PushType.EXTRA_ATTEMPT_GRANTED, 5);
        check("and the student is TOLD, not left to discover it", told != null);
        System.out.println("   " + told.getMessage());

        Response nowAllowed = warned.ask(RequestType.TAKE_VALIDATE_CODE, code);
        check("she can sit it again", nowAllowed.isOk());
        StudentExam third3 = (StudentExam) warned.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), warnedMe.getUserId()))
                .getPayload();
        check("and the attempt is her third", third3 != null && third3.getAttemptNo() == 3);
        warned.ask(RequestType.TAKE_SUBMIT, third3.getSubmissionId());

        check("a fourth is refused again",
                !warned.ask(RequestType.TAKE_VALIDATE_CODE, code).isOk());
        check("the grant is recorded against her",
                new SubmissionDAO().countGrantedAttempts(
                        sitting.getExecutionId(), warnedMe.getUserId()) == 1);
        check("granting to somebody who does not exist is refused",
                !teacher.ask(RequestType.LIVE_GRANT_ATTEMPT, new AttemptGrantRequest(
                        sitting.getExecutionId(), "000000000", null)).isOk());

        // ==============================================================
        System.out.println("5. GRANTING TO EVERYONE WHO SAT IT");
        // The customer asked for one button for the whole room - a power cut is not
        // a per-student problem.
        List<StudentExam> whoSat = (List<StudentExam>) teacher.ask(
                RequestType.LIVE_STATUS, sitting.getExecutionId()).getPayload();
        int satCount = (int) whoSat.stream()
                .map(StudentExam::getStudentId).distinct().count();
        System.out.println("   " + satCount + " student(s) sat this sitting");
        check("somebody sat it", satCount > 0);

        Map<String, Integer> grantsBefore = new LinkedHashMap<>();
        for (StudentExam a : whoSat) {
            grantsBefore.put(a.getStudentId(), new SubmissionDAO()
                    .countGrantedAttempts(sitting.getExecutionId(), a.getStudentId()));
        }

        check("a teacher who did not release it cannot grant to everyone",
                !new Conn() {{ login("teacher5", "teacher5!T"); }}
                        .ask(RequestType.LIVE_GRANT_ATTEMPT,
                             AttemptGrantRequest.forEveryone(sitting.getExecutionId(),
                                     "not mine")).isOk());

        Response all = teacher.ask(RequestType.LIVE_GRANT_ATTEMPT,
                AttemptGrantRequest.forEveryone(sitting.getExecutionId(),
                        "the power went off"));
        check("ONE BUTTON GRANTS TO EVERYONE WHO SAT", all.isOk());
        System.out.println("   " + all.getMessage());
        check("and it reports how many", ((Integer) all.getPayload()) == satCount);

        boolean everyoneGained = true;
        for (var entry : grantsBefore.entrySet()) {
            int now2 = new SubmissionDAO().countGrantedAttempts(
                    sitting.getExecutionId(), entry.getKey());
            if (now2 != entry.getValue() + 1) {
                everyoneGained = false;
            }
        }
        check("every one of them gained exactly one attempt", everyoneGained);

        check("granting to everyone on a sitting nobody sat is refused",
                !teacher.ask(RequestType.LIVE_GRANT_ATTEMPT,
                        AttemptGrantRequest.forEveryone(emptySitting(db, teacher), null))
                        .isOk());

        // ==============================================================
        System.out.println("6. SIGNING IN - five wrong credentials, then ten minutes");
        String victim = freeStudent(db);
        new hsts.server.dao.LoginAttemptDAO().clear(victim);

        Conn guessing = new Conn();
        Response try1 = guessing.login(victim, "definitely-wrong");
        check("a wrong password is refused", !try1.isOk());
        System.out.println("   " + try1.getMessage());
        // Deliberately NOT a countdown. The count is kept against the typed username,
        // so a real account with prior failures would show a different number from a
        // made-up one - and comparing the two would tell an attacker which usernames
        // exist. The login suite caught exactly that; see LoginController.
        check("the refusal gives nothing away",
                try1.getMessage().equals("Incorrect username or password."));

        for (int i = 2; i <= 4; i++) {
            guessing.login(victim, "still-wrong-" + i);
        }
        Response try5 = guessing.login(victim, "wrong-again");
        check("the fifth locks the account", !try5.isOk());
        System.out.println("   " + try5.getMessage());
        check("and says so", try5.getMessage().contains("10 minutes"));

        Response locked = guessing.login(victim, victim + "!S");
        check("EVEN THE RIGHT PASSWORD IS REFUSED WHILE LOCKED", !locked.isOk());
        System.out.println("   " + locked.getMessage());
        check("with a countdown", locked.getMessage().contains("locked for another"));

        check("the lock is stored, not held in memory",
                new hsts.server.dao.LoginAttemptDAO().remainingLock(victim) != null);

        System.out.println("   it applies to EVERY kind of user, not only students");
        for (String who : new String[] { "teacher3", "coordinator2", "principal" }) {
            new hsts.server.dao.LoginAttemptDAO().clear(who);
            Conn c = new Conn();
            for (int i = 0; i < 5; i++) {
                c.login(who, "wrong" + i);
            }
            check(who + " is locked out after five", !c.login(who, "anything").isOk()
                    && new hsts.server.dao.LoginAttemptDAO().remainingLock(who) != null);
            new hsts.server.dao.LoginAttemptDAO().clear(who);
            c.close();
        }

        System.out.println("   a wrong USERNAME is counted too");
        String madeUp = "nobody-called-this";
        new hsts.server.dao.LoginAttemptDAO().clear(madeUp);
        Conn prober = new Conn();
        for (int i = 0; i < 5; i++) {
            prober.login(madeUp, "guess" + i);
        }
        check("so a list of names cannot be worked through five at a time",
                new hsts.server.dao.LoginAttemptDAO().remainingLock(madeUp) != null);
        Response hidden = prober.login(madeUp, "guess-again");
        check("and the wording never reveals whether the name exists",
                !hidden.getMessage().toLowerCase().contains("no such")
             && !hidden.getMessage().toLowerCase().contains("does not exist"));

        // The refusal for a REAL account and a made-up one must be identical, or the
        // difference is the oracle. Both fresh, so both are on their first failure.
        new hsts.server.dao.LoginAttemptDAO().clear(victim);
        new hsts.server.dao.LoginAttemptDAO().clear("also-not-a-user");
        Conn compare = new Conn();
        String realMessage = compare.login(victim, "nope").getMessage();
        String fakeMessage = compare.login("also-not-a-user", "nope").getMessage();
        System.out.println("   real: " + realMessage);
        System.out.println("   fake: " + fakeMessage);
        check("A REAL AND A FAKE USERNAME GIVE THE SAME REFUSAL",
                realMessage.equals(fakeMessage));
        compare.close();
        new hsts.server.dao.LoginAttemptDAO().clear("also-not-a-user");
        new hsts.server.dao.LoginAttemptDAO().clear(madeUp);
        prober.close();

        System.out.println("   and a good sign-in wipes the slate");
        String recovering = freeStudent(db);
        new hsts.server.dao.LoginAttemptDAO().clear(recovering);
        Conn ok = new Conn();
        ok.login(recovering, "wrong-once");
        ok.login(recovering, "wrong-twice");
        check("two failures counted",
                new hsts.server.dao.LoginAttemptDAO().triesLeft(recovering) == 3);
        Response inAtLast = ok.login(recovering, recovering + "!S");
        check("she gets in", inAtLast.isOk());
        check("and her count is back to five",
                new hsts.server.dao.LoginAttemptDAO().triesLeft(recovering)
                        == hsts.server.dao.LoginAttemptDAO.STRIKES);
        ok.close();
        guessing.close();
        new hsts.server.dao.LoginAttemptDAO().clear(victim);

        // ==============================================================
        System.out.println("7. a coordinator does NOT gain release rights from her subject");
        // She may edit her whole subject's QUESTIONS (requirement 19). Releasing is
        // a different thing: SUC-6 and requirement 37 both give it to "המורה", and
        // she is a teacher only of the courses she actually teaches.
        List<Exam> releasable = (List<Exam>) coordinator.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        boolean onlyHerCourses = releasable.stream()
                .allMatch(e -> "02".equals(e.getCourseCode()));
        System.out.println("   she may release exams of: " + releasable.stream()
                .map(Exam::getCourseCode).distinct().toList());
        check("only the course she TEACHES, not her whole subject", onlyHerCourses);

        String geometryExam = approvedExamOf(db, "01");
        check("there is an approved Plane Geometry exam", geometryExam != null);
        LocalDateTime soon = LocalDateTime.now().plusHours(1);
        Response cannotRelease = coordinator.ask(RequestType.EXECUTION_RELEASE,
                new ExamReleaseRequest(geometryExam, 1, soon, soon.plusHours(2),
                        "CRD1", 60, 1));
        check("AND SHE CANNOT RELEASE ONE SHE DOES NOT TEACH", !cannotRelease.isOk());
        System.out.println("   " + cannotRelease.getMessage());

        // ==============================================================
        System.out.println("8. REQUIREMENT 77 - a factor, re-checked rather than assumed");
        int execId = sitting.getExecutionId();
        teacher.ask(RequestType.GRADING_LIST, execId);
        teacher.ask(RequestType.GRADING_APPROVE_ALL, execId);

        List<Grade> before = (List<Grade>) teacher.ask(RequestType.GRADING_LIST, execId)
                .getPayload();
        check("there are approved marks to factor", !before.isEmpty());
        Map<Integer, Integer> was = new LinkedHashMap<>();
        before.forEach(g -> was.put(g.getSubmissionId(), g.getFinalGrade()));
        System.out.println("   before: " + was.values());

        Response factored = teacher.ask(RequestType.GRADING_FACTOR,
                GradeChange.factor(execId, 10));
        check("a factor is accepted after approval", factored.isOk());
        System.out.println("   " + factored.getMessage());

        List<Grade> after = (List<Grade>) teacher.ask(RequestType.GRADING_LIST, execId)
                .getPayload();
        boolean allMoved = true;
        boolean noneOver = true;
        for (Grade g : after) {
            Integer old = was.get(g.getSubmissionId());
            if (old == null) {
                continue;
            }
            int expected = Math.min(100, old + 10);
            if (g.getFinalGrade() != expected) {
                allMoved = false;
            }
            if (g.getFinalGrade() > 100) {
                noneOver = false;
            }
        }
        System.out.println("   after:  " + after.stream()
                .map(Grade::getFinalGrade).toList());
        check("every mark moved by the factor", allMoved);
        check("and nothing went over 100", noneOver);
        check("the factor is recorded on the grade",
                after.stream().allMatch(g -> g.getFactor() != 0));
        check("a factor of zero is refused",
                !teacher.ask(RequestType.GRADING_FACTOR, GradeChange.factor(execId, 0)).isOk());
        check("an absurd factor is refused",
                !teacher.ask(RequestType.GRADING_FACTOR, GradeChange.factor(execId, 500)).isOk());

        // ==============================================================
        System.out.println("9. REQUIREMENT 76 - signed out for being idle");
        // LAST, deliberately. A sweep signs out every idle session, and this
        // suite's own teacher and coordinator connections are idle by then -
        // which is correct behaviour and would break anything that followed.
        var inactivity = server.getInactivityService();
        check("the service is running", inactivity != null);
        System.out.println("   timeout is " + inactivity.getTimeout().toMinutes() + " minutes");

        // Sweep as though it were far in the future, so everybody looks idle.
        long farFuture = System.currentTimeMillis()
                       + inactivity.getTimeout().toMillis() + 60_000;

        // Both students are set up BEFORE any sweep. Seating her afterwards would
        // fail, because the sweep would already have closed the connection she
        // needs to start with.
        Conn sitting2 = new Conn();
        // Not one this suite is already signed in as: NFR 16 forbids the same user
        // being connected twice, so reusing warnUser here would be refused and the
        // login would hand back null - which has caught this project before.
        String examinee = enrolledFreeStudent(db, "01", warnUser);
        Response seated = sitting2.login(examinee, examinee + "!S");
        check("a second, different student can sign in", seated.isOk());
        Student examineeMe = (Student) seated.getPayload();
        sitting2.ask(RequestType.TAKE_VALIDATE_CODE, code);
        StudentExam live = (StudentExam) sitting2.ask(RequestType.TAKE_START,
                new StartExamRequest(sitting.getExecutionId(), examineeMe.getUserId()))
                .getPayload();
        check("one student is inside an exam", live != null);

        Conn idle = new Conn();
        String idleUser = freeStudent(db);
        idle.login(idleUser, idleUser + "!S");
        idle.pushes.clear();

        List<String> out = inactivity.sweep(farFuture);
        System.out.println("   signed out: " + out);
        check("the idle student was signed out", out.contains(idleUser));
        PushEvent why = pollFor(idle, PushType.SESSION_TIMED_OUT, 5);
        check("AND SHE WAS TOLD WHY, before the connection closed", why != null);
        System.out.println("   " + why.getMessage());
        check("the message names inactivity",
                why.getMessage().toLowerCase().contains("without activity"));

        System.out.println("   ...but a student INSIDE AN EXAM is never signed out");
        check("SHE IS EXEMPT - reading a question is not idling", !out.contains(examinee));

        List<String> out2 = inactivity.sweep(farFuture);
        check("and still exempt on a second sweep", !out2.contains(examinee));
        System.out.println("   signed out this time: " + out2);

        sitting2.ask(RequestType.TAKE_SUBMIT, live.getSubmissionId());
        List<String> out3 = inactivity.sweep(farFuture);
        check("once she hands in she is eligible like anyone else",
                out3.contains(examinee));

        idle.close();

        warned.close(); teacher.close(); coordinator.close(); sitting2.close();
        server.stopListening(); server.close();
    }

    // -----------------------------------------------------------------

    static String buildExam(DBController db) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Question q = new Question();
            q.setCourseCode("01");
            q.setText("M15 q" + i + " " + System.nanoTime());
            q.setName("M15Test q " + System.nanoTime());
            q.setTopic("M15");
            q.setDifficulty(DifficultyLevel.MEDIUM);
            List<Answer> a = new ArrayList<>();
            for (int n = 1; n <= 4; n++) a.add(new Answer(n, "option " + n, n == 3));
            q.setAnswers(a);
            ids.add(((Question) teacher.ask(RequestType.QUESTION_ADD, q).getPayload())
                    .getQuestionId());
        }
        Exam draft = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual("01", ids)).getPayload();
        draft.setName("M15Test exam " + System.nanoTime());
        draft.setDurationMinutes(100);
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        if (!saved.isOk()) {
            throw new IllegalStateException("Could not build an exam: " + saved.getMessage()
                    + " (course 01 may have reached 99 exams - reset the demo data)");
        }
        String examId = ((Exam) saved.getPayload()).getExamId();
        coordinator.ask(RequestType.EXAM_APPROVE, new ExamDecision(examId, 1, null));
        return examId;
    }

    /** A student not signed in, not mid-exam, not locked out. */
    static String freeStudent(DBController db) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u WHERE u.role='STUDENT' "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id=u.user_id AND s.status='IN_PROGRESS') "
                     + "ORDER BY RAND() LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Enrolled in the course, not mid-exam anywhere. */
    static String enrolledFreeStudent(DBController db, String course) throws Exception {
        return enrolledFreeStudent(db, course, "");
    }

    /** As above, but never the account named - see NFR 16. */
    static String enrolledFreeStudent(DBController db, String course, String notThis)
            throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id=u.user_id "
                     + "WHERE cs.course_code='" + course + "' "
                     + "AND u.username <> '" + notThis + "' "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.student_id=u.user_id AND s.status='IN_PROGRESS') "
                     + "ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** A sitting of this teacher's that nobody has sat, for the empty-case check. */
    static int emptySitting(DBController db, Conn who) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT x.execution_id FROM exam_execution x "
                     + "WHERE x.released_by = (SELECT user_id FROM users "
                     + "  WHERE username='teacher1') "
                     + "AND NOT EXISTS (SELECT 1 FROM student_exam s "
                     + "  WHERE s.execution_id = x.execution_id) LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /** An approved exam of one course, for the coordinator-release check. */
    static String approvedExamOf(DBController db, String course) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT exam_id FROM exam WHERE course_code='"
                     + course + "' AND status='APPROVED' AND is_current=TRUE LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String idOf(DBController db, String username) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT user_id FROM users WHERE username='"
                     + username + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static boolean isEnrolled(DBController db, String userId, String course) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT 1 FROM course_student WHERE user_id='"
                     + userId + "' AND course_code='" + course + "'")) {
            return rs.next();
        }
    }

    /** The code of a sitting of that course that is open right now, if any. */
    static String openCodeFor(DBController db, String course) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT x.execution_code FROM exam_execution x "
                     + "JOIN exam e ON e.exam_id=x.exam_id AND e.version=x.exam_version "
                     + "WHERE e.course_code='" + course + "' "
                     + "AND NOW() BETWEEN x.open_time AND x.close_time LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static PushEvent pollFor(Conn c, PushType type, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < until) {
            PushEvent e = c.pushes.poll(500, TimeUnit.MILLISECONDS);
            if (e != null && e.getType() == type) {
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
            }, r -> {});
            client.openConnection();
        }

        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }

        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            Response r = inbox.poll(15, TimeUnit.SECONDS);
            if (r == null) {
                throw new IllegalStateException("no reply to " + t);
            }
            return r;
        }

        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }
}
