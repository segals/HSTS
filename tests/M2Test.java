import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.protocol.*;
import hsts.common.util.IsraeliId;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import hsts.server.dao.UserDAO;
import hsts.server.seed.SeedRunner;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 2 verification: schema, seeding, login, roles, single session.
 * Usage: java -cp "G1_Server.jar;G1_Client.jar;." M2Test <mysqlUser> <mysqlPassword>
 */
public class M2Test {

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

    public static void main(String[] args) throws Exception {
        String user = args[0], password = args.length > 1 ? args[1] : "";

        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", user, password);

        System.out.println("1. schema creation");
        db.initialiseSchema();
        String[] tables = {"subject","course","users","course_teacher","course_student",
                "question","answer","exam","exam_question","exam_execution","student_exam",
                "student_answer","grade","question_feedback","exam_statistics","bot",
                "knowledge_source","bot_conversation","code_attempt"};
        Set<String> present = new HashSet<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) present.add(rs.getString(1));
        }
        for (String t : tables) check("table exists: " + t, present.contains(t));
        check("milestone 1 scaffolding dropped",
                !present.contains("m1_skeleton") && !present.contains("m1_skeleton_user"));

        System.out.println("2. seeding");
        System.out.println("   " + SeedRunner.seedIfEmpty());
        check("4 subjects",  db.countRows("subject") == 4);
        check("8 courses",   db.countRows("course")  == 8);
        check("55 users (1 principal + 4 coordinators + 8 teachers + 40 students"
                + " + 1 teacher of every course + 1 student of every course)",
                db.countRows("users") == 55);
        check("enrolments exist", db.countRows("course_student") >= 120);

        System.out.println("3. re-seeding is a no-op");
        String again = SeedRunner.seedIfEmpty();
        System.out.println("   " + again);
        check("did not duplicate", db.countRows("users") == 55);

        System.out.println("4. every seeded ID is a valid Israeli ID");
        UserDAO dao = new UserDAO();
        List<User> all = dao.findAll();
        int bad = 0;
        String firstBad = null;
        for (User u : all) {
            if (!IsraeliId.isValid(u.getUserId())) { bad++; if (firstBad == null) firstBad = u.getUserId(); }
        }
        check("all " + all.size() + " IDs pass the check digit"
                + (bad > 0 ? " (first bad: " + firstBad + ")" : ""), bad == 0);
        check("123456789 correctly rejected as invalid", !IsraeliId.isValid("123456789"));
        check("123456782 correctly accepted as valid",   IsraeliId.isValid("123456782"));

        System.out.println("5. courses with TWO teachers exist (needed for מתווה 13.3)");
        int multi = 0;
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT course_code, COUNT(*) c FROM course_teacher GROUP BY course_code HAVING c >= 2")) {
            while (rs.next()) multi++;
        }
        System.out.println("   courses with 2+ teachers: " + multi);
        check("at least one course has two teachers", multi >= 1);

        System.out.println("6. start the server");
        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();
        check("listening", server.isListening());

        System.out.println("7. login as each role, and check the Java type");
        check("teacher1 -> Teacher",              login("teacher1","teacher1!T")     instanceof Teacher);
        check("coordinator1 -> SubjectCoordinator",
                login("coordinator1","coordinator1!C") instanceof SubjectCoordinator);
        check("student1 -> Student",              login("student1","student1!S")     instanceof Student);
        check("principal -> Principal",           login("principal","principal!P")   instanceof Principal);

        System.out.println("8. the User sent to the client carries no secrets");
        User t = login("teacher1","teacher1!T");
        boolean leak = false;
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (n.contains("password") || n.contains("hash") || n.contains("salt")) leak = true;
            }
        }
        check("no password/hash/salt field anywhere on User", !leak);

        System.out.println("9. associations really came from the database");
        Teacher teacher = (Teacher) login("teacher1","teacher1!T");
        System.out.println("   teacher1 teaches: " + teacher.getTaughtCourseCodes());
        check("teacher1 has courses", !teacher.getTaughtCourseCodes().isEmpty());
        SubjectCoordinator co = (SubjectCoordinator) login("coordinator1","coordinator1!C");
        System.out.println("   coordinator1 coordinates subject: " + co.getCoordinatedSubjectCode());
        check("coordinator has a subject", co.getCoordinatedSubjectCode() != null);
        Student stu = (Student) login("student1","student1!S");
        System.out.println("   student1 enrolled in: " + stu.getEnrolledCourseCodes());
        check("student enrolled in 3-5 courses",
                stu.getEnrolledCourseCodes().size() >= 3 && stu.getEnrolledCourseCodes().size() <= 5);

        System.out.println("9b. one teacher of every course, one student of every course");
        // Asked for, so that every course-picker in the system has something in all
        // four subjects to show. Checked through a real login, because what matters
        // is what her own client receives - reading course_teacher directly would
        // pass even if the association never reached her.
        Set<String> everyCourse = new HashSet<>();
        Map<String, String> subjectOfCourse = new HashMap<>();
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT course_code, subject_code FROM course")) {
            while (rs.next()) {
                everyCourse.add(rs.getString(1));
                subjectOfCourse.put(rs.getString(1), rs.getString(2));
            }
        }

        Teacher orit = (Teacher) login("teacher9","teacher9!T");
        check("teacher9 logs in and is a Teacher", orit != null);
        System.out.println("   teacher9 (" + (orit == null ? "-" : orit.getFullName())
                + ") teaches: " + (orit == null ? "-" : orit.getTaughtCourseCodes()));
        check("teacher9 teaches all " + everyCourse.size() + " courses",
                orit != null && new HashSet<>(orit.getTaughtCourseCodes()).equals(everyCourse));

        Student allSubjects = (Student) login("student41","student41!S");
        check("student41 logs in and is a Student", allSubjects != null);
        System.out.println("   student41 (" + (allSubjects == null ? "-" : allSubjects.getFullName())
                + ") studies: " + (allSubjects == null ? "-" : allSubjects.getEnrolledCourseCodes()));
        check("student41 studies all " + everyCourse.size() + " courses",
                allSubjects != null
                     && new HashSet<>(allSubjects.getEnrolledCourseCodes()).equals(everyCourse));

        // The point of her is the SUBJECTS, not the courses - a girl in eight courses
        // that all belonged to one subject would not demonstrate anything.
        Set<String> herSubjects = new HashSet<>();
        if (allSubjects != null) {
            for (String code : allSubjects.getEnrolledCourseCodes()) {
                herSubjects.add(subjectOfCourse.get(code));
            }
        }
        System.out.println("   ...covering subjects: " + herSubjects);
        check("that covers all 4 subjects", herSubjects.size() == 4);

        // Adding a nineteenth girl to Plane Geometry must not have changed the marks
        // the demo data was built to show: a sitting is capped by how many marks the
        // seeder has to hand out, not by how many girls are enrolled. Counted on the
        // GEO1 sitting rather than on the whole grade table, because the later suites
        // create sittings of their own and a total would drift with every run.
        //
        // Deliberately NOT skipped silently if the demo content is absent: a check
        // that quietly disappears is a check that stops protecting anything.
        int geo1Papers = -1;
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("""
                 SELECT COUNT(*) FROM grade g
                 JOIN student_exam s   ON s.submission_id = g.submission_id
                 JOIN exam_execution e ON e.execution_id  = s.execution_id
                 WHERE e.execution_code = 'GEO1'""")) {
            if (rs.next()) geo1Papers = rs.getInt(1);
        }
        System.out.println("   marked papers in the GEO1 sitting: " + geo1Papers);
        check("the GEO1 sitting still has its 18 marked papers", geo1Papers == 18);

        System.out.println("10. wrong password and unknown user");
        // Both counters cleared first. Signing in wrongly is now counted towards a
        // five-strike lock, and "nobody" never signs in successfully - so across
        // repeated runs it accumulated to five, became locked, and started replying
        // with the lockout message instead of the generic one. The comparison below
        // then failed for a reason that had nothing to do with probing usernames.
        //
        // This is the product behaving correctly: a name nobody owns cannot clear
        // its own count. The suite has to control the state it starts from.
        hsts.server.dao.LoginAttemptDAO locks = new hsts.server.dao.LoginAttemptDAO();
        locks.clear("teacher1");
        locks.clear("nobody");

        Conn c1 = new Conn();
        check("wrong password refused",  !c1.login("teacher1","WRONG").isOk());
        check("unknown user refused",    !c1.login("nobody","whatever").isOk());

        // Both are now on their second failure, so any difference in the wording is
        // a real difference and not an artefact of one being further along.
        String realName = c1.login("teacher1", "WRONG").getMessage();
        String fakeName = c1.login("nobody", "x").getMessage();
        check("same message for both, so usernames cannot be probed",
                realName.equals(fakeName));
        if (!realName.equals(fakeName)) {
            System.out.println("   real: " + realName);
            System.out.println("   fake: " + fakeName);
        }
        c1.close();
        locks.clear("teacher1");
        locks.clear("nobody");

        System.out.println("11. requirement 4 - the same user cannot log in twice");
        Conn a = new Conn();
        Response first = a.login("teacher2","teacher2!T");
        check("first login accepted", first.isOk());
        Conn b = new Conn();
        Response second = b.login("teacher2","teacher2!T");
        check("second login on another connection REFUSED", !second.isOk());
        System.out.println("   message: " + second.getMessage());
        check("a different user can still log in", b.login("teacher3","teacher3!T").isOk());

        System.out.println("12. logging out frees the account");
        check("logout ok", a.logout().isOk());
        Conn c2 = new Conn();
        check("teacher2 can log in again after logout", c2.login("teacher2","teacher2!T").isOk());

        System.out.println("13. a dropped connection frees the account too");
        Conn d = new Conn();
        check("teacher4 logged in", d.login("teacher4","teacher4!T").isOk());
        d.close();                       // no logout - simulates a closed window
        Thread.sleep(600);               // let the server notice
        Conn e = new Conn();
        check("teacher4 can log in again after the drop", e.login("teacher4","teacher4!T").isOk());

        b.close(); c2.close(); e.close();
        server.shutdown();
        db.disconnect();

        System.out.println();
        System.out.println("==== passed " + passed + ", failed " + failed + " ====");
        System.exit(failed > 0 ? 1 : 0);
    }

    /** One client connection. */
    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(20);
        final HSTSClient client;
        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT,
                    m -> { if (m instanceof Response r) inbox.add(r); }, r -> {});
            client.openConnection();
        }
        Response login(String u, String p) throws Exception {
            client.sendToServer(new Request(RequestType.LOGIN, new Credentials(u, p), "login"));
            return inbox.poll(10, TimeUnit.SECONDS);
        }
        Response logout() throws Exception {
            client.sendToServer(new Request(RequestType.LOGOUT, null, "logout"));
            return inbox.poll(10, TimeUnit.SECONDS);
        }
        void close() throws Exception { client.closeConnection(); }
    }

    /** Logs in on a throwaway connection and returns the User. */
    static User login(String u, String p) throws Exception {
        Conn c = new Conn();
        Response r = c.login(u, p);
        User user = (r != null && r.isOk() && r.getPayload() instanceof User x) ? x : null;
        c.logout();
        c.close();
        Thread.sleep(120);
        return user;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
