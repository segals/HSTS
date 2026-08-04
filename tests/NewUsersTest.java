import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

/**
 * The two accounts added for the demo: one teacher of every course, one girl who
 * studies every course.
 *
 * <p>Checked through real logins and the same requests the screens send, not by
 * reading {@code course_teacher} directly - a row in that table proves nothing
 * about what her own client receives.</p>
 *
 * <p>It also re-checks that adding them changed nothing else: the marked papers the
 * demo data was built to show, and requirements 12 and 13 (every course has a
 * coordinator, and one or more teachers and students).</p>
 *
 * <p>Usage: java -cp "G1_Server.jar;G1_Client.jar;." NewUsersTest &lt;user&gt; &lt;password&gt;</p>
 */
public class NewUsersTest {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static DBController db;

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

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();

        Map<String, String> subjectOfCourse = new LinkedHashMap<>();
        Map<String, String> subjectNames = new LinkedHashMap<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT c.course_code, c.subject_code, s.name
                 FROM course c JOIN subject s ON s.subject_code = c.subject_code
                 ORDER BY c.course_code""")) {
            while (rs.next()) {
                subjectOfCourse.put(rs.getString(1), rs.getString(2));
                subjectNames.put(rs.getString(2), rs.getString(3));
            }
        }
        int courseCount = subjectOfCourse.size();
        int subjectCount = subjectNames.size();

        // -------------------------------------------------------------
        System.out.println("1. teacher9 - a teacher of every course");
        // -------------------------------------------------------------
        Conn orit = new Conn();
        Response in = orit.login("teacher9", "teacher9!T");
        check("teacher9 signs in", in.isOk());
        User her = (User) in.getPayload();
        check("she arrives as a Teacher", her instanceof Teacher);
        System.out.println("   " + her.getFullName() + ", id " + her.getUserId());
        check("named Orit Nahum", "Orit Nahum".equals(her.getFullName()));

        // The course picker on the question-bank screen sends exactly this.
        List<Course> mine = (List<Course>) orit.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        Set<String> herSubjects = new TreeSet<>();
        System.out.println("   her course list:");
        for (Course c : mine) {
            System.out.println("      " + c.getCourseCode() + " " + c.getName()
                    + "  (" + c.getSubjectName() + ")");
            herSubjects.add(c.getSubjectCode());
        }
        check("her own screen lists all " + courseCount + " courses", mine.size() == courseCount);
        check("covering all " + subjectCount + " subjects: " + herSubjects,
                herSubjects.size() == subjectCount);

        // She teaches every course, so the whole bank is hers to edit (requirement 14
        // limits a teacher to courses she teaches - here that is everything).
        int visibleQuestions = 0;
        for (Course c : mine) {
            List<Question> bank = (List<Question>) orit.ask(
                    RequestType.QUESTION_LIST_BY_COURSE, c.getCourseCode()).getPayload();
            visibleQuestions += bank.size();
        }
        // Compared against the CURRENT, undeleted questions, not against every row in
        // the table: editing a question keeps the old version and deleting one is a
        // soft delete, so the table holds history the bank screen must not show.
        int bank = currentQuestions();
        System.out.println("   questions she may edit: " + visibleQuestions
                + " (current bank: " + bank + ", rows in the table including old"
                + " versions: " + db.countRows("question") + ")");
        check("that is the whole current bank", visibleQuestions == bank);

        // SUC-6: an approved exam may be given to a class by a teacher OF THAT COURSE.
        // Since she teaches all of them, every approved exam in the school is hers to
        // release - which is the widest this list can legitimately get, and worth
        // seeing once.
        List<Exam> releasable = (List<Exam>) orit.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        int approvedInDb = countApproved();
        Set<String> releasableCourses = new TreeSet<>();
        for (Exam e : releasable) {
            releasableCourses.add(e.getCourseCode());
        }
        System.out.println("   approved exams she may release: " + releasable.size()
                + " of " + approvedInDb + ", in courses " + releasableCourses);
        // Every approved VERSION, not only the current one - the list is deliberately
        // per version (the screen prints "020101 . v2 . Plane Geometry"), because an
        // exam that was approved and later edited still exists and can still be given
        // to a class. Nothing is thrown away.
        check("she may release every approved exam version in the school",
                releasable.size() == approvedInDb);

        // She has written nothing and released nothing, so her reports are empty. That
        // is the honest answer, not a fault - and worth asserting, because an empty
        // list and a crash look the same on a screen.
        Response reports = orit.ask(RequestType.TEACHER_REPORT_EXAMS, null);
        check("her report list answers cleanly even though she has written nothing",
                reports.isOk() && reports.getPayload() instanceof List);
        System.out.println("   exams in her own report list: "
                + ((List<?>) reports.getPayload()).size());

        // A bot may be created on any of her courses (several per course are allowed
        // since milestone 14, so nothing is "taken").
        List<Course> botCourses = (List<Course>) orit.ask(
                RequestType.BOT_COURSES_FREE, null).getPayload();
        System.out.println("   courses she could put a bot on: " + botCourses.size());
        check("she can start a bot on any course she teaches",
                botCourses.size() == courseCount);
        orit.close();

        // -------------------------------------------------------------
        System.out.println("2. student41 - a girl who studies every course");
        // -------------------------------------------------------------
        Conn liat = new Conn();
        Response inS = liat.login("student41", "student41!S");
        check("student41 signs in", inS.isOk());
        Student girl = (Student) inS.getPayload();
        System.out.println("   " + girl.getFullName() + ", id " + girl.getUserId());
        check("named Liat Barnea", "Liat Barnea".equals(girl.getFullName()));

        Set<String> studied = new TreeSet<>(girl.getEnrolledCourseCodes());
        Set<String> studiedSubjects = new TreeSet<>();
        for (String code : studied) {
            studiedSubjects.add(subjectOfCourse.get(code));
        }
        System.out.println("   she studies " + studied + " -> subjects " + studiedSubjects);
        check("enrolled in all " + courseCount + " courses", studied.size() == courseCount);
        check("which is all " + subjectCount + " subjects",
                studiedSubjects.size() == subjectCount);

        // She was seated by the demo seeder like anybody else, so she has a history to
        // read - which is the point: the "one student across her exams" report of
        // מתווה 12 needs a girl with results in more than one course.
        List<Grade> hers = (List<Grade>) liat.ask(RequestType.RESULTS_MINE, null).getPayload();
        Set<String> herCourses = new TreeSet<>();
        System.out.println("   her published results:");
        for (Grade g : hers) {
            System.out.println("      " + g.getExamId() + "  " + g.getCourseName()
                    + "  " + g.getFinalGrade());
            herCourses.add(g.getCourseName());
        }
        check("she has results to read", !hers.isEmpty());
        check("in more than one course, so the per-student report is not a single dot",
                herCourses.size() >= 2);

        // One row per course of hers that has a bot at all - the active one if there is
        // one, otherwise a switched-off one so that she is TOLD it is off rather than
        // being left wondering. She is on every course, so this is every course in the
        // school that has a bot.
        Response botReply = liat.ask(RequestType.BOT_AVAILABLE, null);
        List<Bot> bots = (List<Bot>) botReply.getPayload();
        System.out.println("   study bots on her screen: " + botReply.getMessage());
        Bot switchedOff = null;
        for (Bot b : bots) {
            System.out.println("      " + b.getName() + "  (" + b.getCourseName() + ")  "
                    + (b.isActive() ? "on" : "OFF"));
            if (!b.isActive()) switchedOff = b;
        }
        check("one row per course of hers that has a bot (" + coursesWithABot() + ")",
                bots.size() == coursesWithABot());
        long onNow = bots.stream().filter(Bot::isActive).count();
        check("and the ones she can actually use are the active ones ("
                + coursesWithAnActiveBot() + ")", onNow == coursesWithAnActiveBot());

        // Requirement 71 is enforced on the ASKING, not by hiding the row. Checked here
        // only when the data happens to contain a switched-off bot on one of her
        // courses, and said out loud when it does not, so a check cannot quietly vanish.
        if (switchedOff != null) {
            // Asked by course, which is how the screen asks. A switched-off row only
            // appears when that course has no active bot, so this reaches the off one.
            Response refused = liat.ask(RequestType.BOT_ASK,
                    new BotQuestion(switchedOff.getCourseCode(), "Are you on?"));
            System.out.println("   asking the switched-off one: " + refused.getMessage());
            check("asking a switched-off bot is refused (requirement 71)",
                    !refused.isOk()
                    && refused.getMessage().toLowerCase().contains("not switched on"));
        } else {
            System.out.println("   (no switched-off bot on her courses right now,"
                    + " so requirement 71's refusal is covered by M14Test only)");
        }

        // The live sitting must still accept her. Deliberately only the CODE is checked
        // and the exam is not started: starting it would use up her one attempt and the
        // demo would find her paper already sat.
        int papersBefore = herPapers(girl.getUserId());
        Response live = liat.ask(RequestType.TAKE_VALIDATE_CODE, "NOW1");
        System.out.println("   NOW1: " + (live.isOk() ? "accepted" : "refused - " + live.getMessage()));
        check("the live sitting accepts her code", live.isOk());
        check("and checking the code did not use up her attempt",
                herPapers(girl.getUserId()) == papersBefore);
        liat.close();

        // -------------------------------------------------------------
        System.out.println("2b. a coordinator who teaches no courses at all");
        // -------------------------------------------------------------
        // Deliberately in the data: nothing in the documents says a coordinator must
        // teach - the client story lists a course's teachers and its coordinator as
        // two separate facts - and the system behaves differently for her. A rule
        // with no example of it in the data is a rule nobody can check.
        Conn idle = new Conn();
        Response idleIn = idle.login("coordinator3", "coordinator3!C");
        check("coordinator3 signs in", idleIn.isOk());
        SubjectCoordinator she = (SubjectCoordinator) idleIn.getPayload();
        System.out.println("   " + she.getFullName() + " coordinates subject "
                + she.getCoordinatedSubjectCode() + ", teaches " + she.getTaughtCourseCodes());
        check("SHE TEACHES NOTHING", she.getTaughtCourseCodes().isEmpty());
        check("but she still coordinates a subject",
                she.getCoordinatedSubjectCode() != null);

        // Releasing is done by the teacher OF THE COURSE, so there is nothing she
        // could ever release - which is why her menu has no entry for it.
        List<Exam> nothingToRelease = (List<Exam>) idle.ask(
                RequestType.EXECUTION_RELEASABLE_EXAMS, null).getPayload();
        check("and nothing at all to release", nothingToRelease.isEmpty());

        // Her approval queue is untouched: that is scoped to her SUBJECT and has
        // nothing to do with teaching.
        Response herQueue = idle.ask(RequestType.EXAM_PENDING_FOR_COORDINATOR, null);
        check("her approval queue still works", herQueue.isOk());

        hsts.common.protocol.MenuContext herContext =
                (hsts.common.protocol.MenuContext) idle.ask(
                        RequestType.MENU_CONTEXT, null).getPayload();
        check("her menu is told she teaches nothing",
                herContext.getTaughtCourses().isEmpty());
        check("and her subject comes back by name",
                herContext.getCoordinatedSubjectName() != null);
        System.out.println("   menu context: coordinates "
                + herContext.getCoordinatedSubjectName()
                + ", teaches " + herContext.getTaughtCourses().size());
        // She may not build an exam either - requirement 20, "רק עבור קורסים שהיא
        // מלמדת" - nor create a study bot - requirement 65, the same words. The menu
        // hides all three; the server refuses all three, which is the check that
        // counts. Her question bank stays: requirement 19 gives her the subject's.
        Response subjectCourses = idle.ask(RequestType.COURSE_LIST_MINE, null);
        check("her question bank still lists her subject's courses",
                subjectCourses.isOk()
                     && !((List<Course>) subjectCourses.getPayload()).isEmpty());

        String aCourseOfHerSubject =
                ((List<Course>) subjectCourses.getPayload()).get(0).getCourseCode();

        Response cannotBuild = idle.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(aCourseOfHerSubject, List.of("00105")));
        check("SHE CANNOT BUILD AN EXAM, even in her own subject", !cannotBuild.isOk());
        System.out.println("   " + cannotBuild.getMessage());

        Response cannotBot = idle.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(aCourseOfHerSubject, "Should not exist"));
        check("AND SHE CANNOT CREATE A STUDY BOT", !cannotBot.isOk());
        System.out.println("   " + cannotBot.getMessage());

        Response herMarking = idle.ask(RequestType.GRADING_SITTINGS, null);
        check("her marking list answers cleanly and is empty",
                herMarking.isOk() && ((List<?>) herMarking.getPayload()).isEmpty());

        idle.close();

        // -------------------------------------------------------------
        System.out.println("2c. every demo exam has a name");
        // -------------------------------------------------------------
        int unnamed = 0;
        String example = null;
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT exam_id, name FROM exam WHERE is_current = TRUE")) {
            while (rs.next()) {
                if (rs.getString(2) == null || rs.getString(2).isBlank()) {
                    unnamed++;
                } else if (example == null) {
                    example = rs.getString(2) + "  ·  " + rs.getString(1);
                }
            }
        }
        System.out.println("   for example: " + example);
        check("no exam is left without a name", unnamed == 0);

        // -------------------------------------------------------------
        System.out.println("3. nothing else moved");
        // -------------------------------------------------------------
        check("55 users", db.countRows("users") == 55);
        System.out.println("   marked papers in GEO1: " + geo1Papers());
        check("the GEO1 sitting still has its 18 marked papers", geo1Papers() == 18);

        // Requirement 13: every course has one or more teachers and students.
        int coursesWithoutTeacher = countCoursesMissing("course_teacher");
        int coursesWithoutStudent = countCoursesMissing("course_student");
        check("every course still has a teacher", coursesWithoutTeacher == 0);
        check("every course still has students", coursesWithoutStudent == 0);

        // Requirement 12: every course has a subject coordinator - and the submitted
        // class diagram makes that association 1 to 1, so each coordinator has exactly
        // one subject and each subject exactly one coordinator.
        int subjectsWithoutCoordinator = 0, subjectsWithTwo = 0;
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT s.subject_code, COUNT(u.user_id) c
                 FROM subject s
                 LEFT JOIN users u ON u.coordinated_subject = s.subject_code
                 GROUP BY s.subject_code""")) {
            while (rs.next()) {
                if (rs.getInt(2) == 0) subjectsWithoutCoordinator++;
                if (rs.getInt(2) > 1)  subjectsWithTwo++;
            }
        }
        check("every subject has a coordinator (requirement 12)", subjectsWithoutCoordinator == 0);
        check("and exactly one, as the class diagram's 1-to-1 says", subjectsWithTwo == 0);

        // The other half of that 1 to 1: nobody coordinates two subjects. The database
        // cannot express it - coordinated_subject is one column, not a table - so this
        // asserts the shape rather than the data.
        int coordinatorColumns = 0;
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'users'
                   AND column_name = 'coordinated_subject'""")) {
            if (rs.next()) coordinatorColumns = rs.getInt(1);
        }
        check("a coordinator's subject is a single column, so she cannot have two",
                coordinatorColumns == 1);

        server.shutdown();
        db.disconnect();
    }

    private static int countApproved() throws Exception {
        return one("SELECT COUNT(*) FROM exam WHERE status = 'APPROVED'");
    }

    private static int currentQuestions() throws Exception {
        return one("SELECT COUNT(*) FROM question WHERE is_current = TRUE AND is_deleted = FALSE");
    }

    /** Courses that have at least one bot, active or not. */
    private static int coursesWithABot() throws Exception {
        return one("SELECT COUNT(DISTINCT course_code) FROM bot");
    }

    private static int coursesWithAnActiveBot() throws Exception {
        return one("SELECT COUNT(DISTINCT course_code) FROM bot WHERE status = 'ACTIVE'");
    }

    private static int one(String sql) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int herPapers(String userId) throws Exception {
        try (java.sql.PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM student_exam WHERE student_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int geo1Papers() throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT COUNT(*) FROM grade g
                 JOIN student_exam s   ON s.submission_id = g.submission_id
                 JOIN exam_execution e ON e.execution_id  = s.execution_id
                 WHERE e.execution_code = 'GEO1'""")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int countCoursesMissing(String table) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM course c WHERE NOT EXISTS ("
               + "SELECT 1 FROM " + table + " x WHERE x.course_code = c.course_code)")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(200);
        final HSTSClient client;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
            }, r -> {});
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
