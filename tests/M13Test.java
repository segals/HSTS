import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.ReportType;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.control.strategy.*;
import hsts.server.dao.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 13: the statistical reports (SUC-11 / SUC-12 / מתווה 12), built with
 * the Factory and Strategy patterns from the submitted class diagram.
 *
 * <p>Two things are being checked, and they are different in kind.</p>
 *
 * <p><b>The figures.</b> That the three comparisons מתווה 12 asks for produce the
 * right averages, medians and decile spreads - checked against the marks the same
 * report hands back, so the report has to agree with itself.</p>
 *
 * <p><b>The patterns.</b> Requirement 64 says a new report should take minimal
 * work, and the Factory and Strategy are how that claim is met. So the factory is
 * tested directly, not only through the screen: every report type has a strategy,
 * each strategy answers for its own type, and the controller reaches all of them
 * without knowing what they are.</p>
 *
 * <p>There are <b>no acceptance tests</b> for SUC-11 or SUC-12 in the submitted
 * Assignment 1, which covers SUC-3, 7, 9 and 10 only.</p>
 */
public class M13Test {

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

        // ==============================================================
        System.out.println("1. THE FACTORY - requirement 64, tested directly");
        ReportFactory factory = new ReportFactory(new ExamDAO(), new GradeDAO(),
                new UserDAO(), new CourseDAO());

        check("every report type has a strategy behind it", factory.isComplete());
        check("the factory knows exactly as many as the enum declares",
                factory.all().size() == ReportType.values().length);

        for (ReportType type : ReportType.values()) {
            ReportStrategy s = factory.createStrategy(type);
            check(type + " has a strategy", s != null);
            // A crossed wiring - BY_COURSE returning the student strategy - would
            // pass a "not null" check and produce silently wrong reports.
            check(type + " is answered by a strategy that claims that type",
                    s != null && s.getType() == type);
            check(type + " names itself for the screen",
                    s != null && s.getName() != null && !s.getName().isBlank());
        }
        check("asking for null gets null, not an exception",
                factory.createStrategy(null) == null);

        System.out.println("   " + ReportType.values().length + " report types, "
                         + factory.all().size() + " strategies");

        // ==============================================================
        System.out.println("2. each strategy offers its OWN list of subjects");
        for (ReportType type : ReportType.values()) {
            List<ReportSubject> subjects = factory.createStrategy(type).listSubjects();
            System.out.println("   " + type.getSubjectNoun() + ": " + subjects.size());
            check(type.getSubjectNoun() + " list is not empty", !subjects.isEmpty());
            check(type.getSubjectNoun() + " entries carry a key and a name",
                    subjects.stream().allMatch(s -> s.getKey() != null && !s.getKey().isBlank()
                                                 && s.getName() != null && !s.getName().isBlank()));
        }

        // ==============================================================
        System.out.println("3. the three comparisons מתווה 12 asks for");
        Conn head = new Conn(); head.login("principal", "principal!P");

        List<ReportType> allowed = (List<ReportType>) head.ask(RequestType.REPORT_TYPES, null)
                .getPayload();
        check("the principal is offered all three (requirement 63)",
                allowed.size() == 3 && allowed.containsAll(List.of(ReportType.values())));

        // ---- by teacher ----
        List<ReportSubject> teachers = (List<ReportSubject>) head.ask(
                RequestType.REPORT_SUBJECTS, ReportType.BY_TEACHER).getPayload();
        ReportSubject noa = teachers.stream().filter(s -> s.getName().equals("Noa Levi"))
                .findFirst().orElse(null);
        check("Noa Levi can be reported on", noa != null);

        Report byTeacher = (Report) head.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_TEACHER, noa.getKey())).getPayload();
        System.out.println("   " + byTeacher.getTitle() + " -> "
                         + byTeacher.getLines().size() + " exams");
        check("it compares more than one exam", byTeacher.getLines().size() >= 2);
        check("it is the by-teacher report", byTeacher.getType() == ReportType.BY_TEACHER);
        check("it names her", "Noa Levi".equals(byTeacher.getSubjectName()));
        checkFigures("by-teacher", byTeacher);

        // ---- by course ----
        List<ReportSubject> courses = (List<ReportSubject>) head.ask(
                RequestType.REPORT_SUBJECTS, ReportType.BY_COURSE).getPayload();
        ReportSubject geometry = courses.stream()
                .filter(s -> s.getName().contains("Geometry")).findFirst().orElse(null);
        check("Plane Geometry can be reported on", geometry != null);

        Report byCourse = (Report) head.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_COURSE, geometry.getKey())).getPayload();
        System.out.println("   " + byCourse.getTitle() + " -> "
                         + byCourse.getLines().size() + " exams");
        check("it compares the course's exams", byCourse.getLines().size() >= 2);
        checkFigures("by-course", byCourse);
        check("a course report spans more than one author, unlike a teacher report",
                byCourse.getLines().size() >= byTeacher.getLines().size());

        // ---- by student ----
        List<ReportSubject> students = (List<ReportSubject>) head.ask(
                RequestType.REPORT_SUBJECTS, ReportType.BY_STUDENT).getPayload();
        check("students who have sat something are offered", !students.isEmpty());

        // One who has sat more than one exam, so there is something to compare.
        Report byStudent = null;
        for (ReportSubject s : students) {
            Report r = (Report) head.ask(RequestType.REPORT_GENERATE,
                    new ReportRequest(ReportType.BY_STUDENT, s.getKey())).getPayload();
            if (r.getLines().size() >= 2) {
                byStudent = r;
                break;
            }
        }
        check("a student who sat more than one exam was found", byStudent != null);
        System.out.println("   " + byStudent.getTitle() + " -> "
                         + byStudent.getLines().size() + " exams");
        checkFigures("by-student", byStudent);

        System.out.println("4. the student report highlights HER mark against the class");
        check("every row carries her own mark",
                byStudent.getLines().stream().allMatch(ReportLine::hasHighlight));
        check("the other two reports do not - there is no single student in them",
                byTeacher.getLines().stream().noneMatch(ReportLine::hasHighlight)
             && byCourse.getLines().stream().noneMatch(ReportLine::hasHighlight));

        for (ReportLine line : byStudent.getLines()) {
            System.out.printf("   %-46s her %d, class avg %.1f, gap %+.1f%n",
                    line.getLabel(), line.getHighlight(),
                    line.getStatistics().getAverage(), line.getDifferenceFromAverage());
        }
        check("every row measures her against the class she sat with",
                byStudent.getLines().stream()
                        .allMatch(l -> l.getDifferenceFromAverage() != null));
        check("and the gap really is her mark minus the class average",
                byStudent.getLines().stream().allMatch(l ->
                        Math.abs(l.getDifferenceFromAverage()
                               - (l.getHighlight() - l.getStatistics().getAverage())) < 0.0001));
        check("the overall figures are over HER marks, not the classes'",
                byStudent.getOverall().getGradeCount() == byStudent.getLines().size());

        // ==============================================================
        System.out.println("5. requirement 59 - a teacher gets ONE report, about herself");
        Conn noaConn = new Conn(); noaConn.login("teacher1", "teacher1!T");
        List<ReportType> hers = (List<ReportType>) noaConn.ask(RequestType.REPORT_TYPES, null)
                .getPayload();
        check("she is offered exactly one report", hers.size() == 1);
        check("and it is the by-teacher one", hers.get(0) == ReportType.BY_TEACHER);

        check("the course comparison is refused",
                !noaConn.ask(RequestType.REPORT_GENERATE,
                        new ReportRequest(ReportType.BY_COURSE, geometry.getKey())).isOk());
        Response refused = noaConn.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_STUDENT, students.get(0).getKey()));
        check("so is the student comparison", !refused.isOk());
        System.out.println("   " + refused.getMessage());

        System.out.println("   ...and she cannot ask for a COLLEAGUE'S report");
        ReportSubject maya = teachers.stream().filter(s -> s.getName().equals("Maya Cohen"))
                .findFirst().orElse(null);
        check("Maya is in the principal's list", maya != null);
        Report hijack = (Report) noaConn.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_TEACHER, maya.getKey())).getPayload();
        // The request is not refused - it is silently made about her instead, which
        // is the safer behaviour: there is nothing to probe for.
        check("asking for Maya's key still returns NOA's report",
                "Noa Levi".equals(hijack.getSubjectName()));
        check("with her own exams in it",
                hijack.getLines().size() == byTeacher.getLines().size());
        System.out.println("   asked for Maya, got: " + hijack.getSubjectName());

        check("her subject list offers only herself",
                ((List<ReportSubject>) noaConn.ask(RequestType.REPORT_SUBJECTS,
                        ReportType.BY_TEACHER).getPayload()).size() == 1);

        // ==============================================================
        System.out.println("6. requirement 55 - a student reaches none of it");
        Conn pupil = new Conn();
        String studentUser = anyStudentWhoSat(db);
        pupil.login(studentUser, studentUser + "!S");
        check("she cannot list report types",
                !pupil.ask(RequestType.REPORT_TYPES, null).isOk());
        check("nor list subjects",
                !pupil.ask(RequestType.REPORT_SUBJECTS, ReportType.BY_STUDENT).isOk());
        Response herOwn = pupil.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_STUDENT, students.get(0).getKey()));
        check("nor generate one - not even about herself", !herOwn.isOk());
        System.out.println("   " + herOwn.getMessage());

        // ==============================================================
        System.out.println("7. bad input");
        check("no report chosen is refused",
                !head.ask(RequestType.REPORT_GENERATE, null).isOk());
        check("a null type is refused",
                !head.ask(RequestType.REPORT_GENERATE,
                        new ReportRequest(null, "x")).isOk());
        check("an empty subject key is refused",
                !head.ask(RequestType.REPORT_GENERATE,
                        new ReportRequest(ReportType.BY_COURSE, "  ")).isOk());

        Response nobody = head.ask(RequestType.REPORT_GENERATE,
                new ReportRequest(ReportType.BY_TEACHER, "000000000"));
        check("a teacher who does not exist is not an error, just an empty report",
                nobody.isOk());
        check("and the report says it is empty",
                ((Report) nobody.getPayload()).isEmpty());
        System.out.println("   " + nobody.getMessage());

        head.close(); noaConn.close(); pupil.close();
        server.stopListening(); server.close();
    }

    // -----------------------------------------------------------------

    /**
     * A report must agree with itself: the figures it publishes have to describe
     * the rows it published beside them.
     */
    static void checkFigures(String which, Report report) {
        check(which + ": not empty", !report.isEmpty());
        check(which + ": every row has figures",
                report.getLines().stream().allMatch(l -> l.getStatistics() != null));
        check(which + ": every row has marks in it",
                report.getLines().stream().allMatch(l -> l.getStatistics().getGradeCount() > 0));

        ExamStatistics overall = report.getOverall();
        check(which + ": overall figures exist", overall != null);
        check(which + ": ten decile buckets",
                overall.getDeciles().length == ExamStatistics.DECILE_COUNT);
        check(which + ": every mark lands in exactly one bucket",
                Arrays.stream(overall.getDeciles()).sum() == overall.getGradeCount());
        check(which + ": average is inside 0-100",
                overall.getAverage() >= 0 && overall.getAverage() <= 100);
        check(which + ": median is inside 0-100",
                overall.getMedian() >= 0 && overall.getMedian() <= 100);

        // One check over every row, not one check per row. A loop that calls
        // check() per line makes the suite's total drift as the database grows -
        // it read 104 one run and 114 the next - and a total that moves on its own
        // is no use for spotting a regression.
        check(which + ": every row's buckets add up to its own count",
                report.getLines().stream().allMatch(l ->
                        Arrays.stream(l.getStatistics().getDeciles()).sum()
                                == l.getStatistics().getGradeCount()));
        check(which + ": a description saying what is compared",
                report.getDescription() != null && !report.getDescription().isBlank());
        check(which + ": a timestamp", report.getGeneratedAt() != null);
    }

    static String anyStudentWhoSat(DBController db) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN student_exam s ON s.student_id = u.user_id "
                     + "WHERE u.role='STUDENT' ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(400);
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
