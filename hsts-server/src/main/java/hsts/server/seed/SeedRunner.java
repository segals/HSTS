package hsts.server.seed;

import hsts.common.entity.Principal;
import hsts.common.entity.Student;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.util.IsraeliId;
import hsts.server.dao.DBController;
import hsts.server.dao.UserDAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fills an empty database with the test data the demo needs.
 *
 * <p>מתווה item 17 requires prepared data in the database. Without it, half the
 * demo scenarios have nothing to show.</p>
 *
 * <h2>Why this is Java and not a .sql file</h2>
 *
 * <p>Two reasons a hand-written script cannot satisfy.</p>
 *
 * <p><b>Israeli ID check digits.</b> Every user ID has to pass the check-digit
 * test, because a student types hers before an exam and the system verifies it.
 * {@code 123456789} is not a valid ID. Generating them guarantees all ~53 are.</p>
 *
 * <p><b>Dates relative to now.</b> Later milestones need an exam that is open
 * <em>at the moment of the demo</em>. Hard-coded timestamps go stale the first
 * day the demo slips. Everything time-based is computed from the moment the
 * seeder runs, so re-running it makes the data fresh again.</p>
 *
 * <p>The random number generator is given a fixed seed, so two runs produce the
 * same data. Reproducibility matters when a bug has to be shown twice.</p>
 */
public final class SeedRunner {

    /** Fixed seed: the "random" enrolments are the same on every machine, every run. */
    private static final long RANDOM_SEED = 42L;

    private static final String[][] SUBJECTS = {
        {"01", "Mathematics"},
        {"02", "Physics"},
        {"03", "Literature"},
        {"04", "Biology"},
    };

    /** courseCode, name, subjectCode */
    private static final String[][] COURSES = {
        {"01", "Plane Geometry",   "01"},
        {"02", "Algebra",          "01"},
        {"03", "Mechanics",        "02"},
        {"04", "Electricity",      "02"},
        {"05", "Modern Poetry",    "03"},
        {"06", "Classical Prose",  "03"},
        {"07", "Genetics",         "04"},
        {"08", "Human Anatomy",    "04"},
    };

    private static final String[] FIRST_NAMES = {
        "Noa", "Maya", "Tamar", "Yael", "Shira", "Adi", "Roni", "Lior",
        "Talia", "Avigail", "Hila", "Michal", "Dana", "Sivan", "Gali", "Ayelet",
        "Efrat", "Orly", "Reut", "Nofar",
    };

    private static final String[] LAST_NAMES = {
        "Levi", "Cohen", "Mizrahi", "Peretz", "Bitan", "Avraham", "Friedman",
        "Katz", "Shapira", "Barak", "Golan", "Aviv", "Regev", "Sharon",
    };

    private SeedRunner() {
    }

    /**
     * Seeds only if the database has no users yet, so restarting the server never
     * duplicates anything and never overwrites real data.
     *
     * @return a short description of what happened, for the server console.
     */
    public static String seedIfEmpty() throws SQLException {
        DBController db = DBController.getInstance();
        if (db.countRows("users") > 0) {
            return "Test data already present (" + db.countRows("users") + " users) - not re-seeded.";
        }
        return seedAll();
    }

    /**
     * Every table, ordered children-before-parents.
     *
     * <p>Kept in one place so a reset cannot miss a table as the schema grows -
     * a forgotten table would leave orphaned rows that break the next seed with a
     * foreign-key error nobody expects.</p>
     */
    private static final String[] ALL_TABLES = {
        "bot_conversation", "knowledge_source", "bot",
        "exam_statistics", "question_feedback", "grade",
        "student_answer", "attempt_grant", "student_exam", "exam_execution",
        "exam_question", "exam",
        "answer", "question",
        "code_attempt", "course_teacher", "course_student",
        "users", "course", "subject",
    };

    /**
     * Wipes every row and seeds again from scratch.
     *
     * <p>Two reasons this exists rather than being a hand-typed SQL command.</p>
     *
     * <p><b>Testing leaves debris.</b> Running the automated suites repeatedly
     * fills the bank with things like "Algebra easy question 0" and half-built
     * exams. Harmless, but it makes the screens hard to judge and hard to
     * demonstrate.</p>
     *
     * <p><b>The demo needs fresh dates.</b> Later milestones need an exam that is
     * open <em>at the moment of the demo</em>. Because the seeder computes every
     * date relative to when it runs, re-running this shortly before the demo puts
     * the data back into the right shape - which is exactly what the plan
     * anticipated.</p>
     *
     * <p>{@code TRUNCATE} rather than {@code DELETE}: it also resets the
     * auto-increment counters, so a fresh database produces the same ids every
     * time and a bug found once can be reproduced.</p>
     */
    public static String resetAndSeed() throws SQLException {
        Connection conn = DBController.getInstance().getConnection();

        try (Statement st = conn.createStatement()) {
            // The tables reference each other in a cycle of constraints that no
            // single ordering satisfies for TRUNCATE, so the checks are lifted
            // for the duration of the wipe and restored immediately after.
            st.executeUpdate("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : ALL_TABLES) {
                    st.executeUpdate("TRUNCATE TABLE " + table);
                }
            } finally {
                st.executeUpdate("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
        // People and courses first, then the content that depends on them. Split in
        // two because seedAll() is also the "empty database" path on server start,
        // where an untouched question bank is the honest starting point.
        String people = seedAll();
        String content = DemoContentSeeder.seed();
        return "Test data reset. " + people + " " + content;
    }

    private static String seedAll() throws SQLException {
        // A fresh run hands out names afresh. Without this, a reset inside the same
        // JVM would start from an already-full set and fall through to the suffix.
        NAMES_USED.clear();

        Connection conn = DBController.getInstance().getConnection();
        UserDAO userDAO = new UserDAO();

        // A single transaction: if anything fails, the database is left untouched
        // rather than half-populated, which is far harder to diagnose.
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            seedSubjects(conn);
            seedCourses(conn);

            int idCounter = 1;

            // ---- principal ----
            Principal principal = new Principal(nextId(idCounter++), "principal", "Dalia Ben-Ami");
            userDAO.insertWithPassword(principal, passwordFor("principal", 'P'));

            // ---- 4 subject coordinators, one per subject ----
            List<User> coordinators = new ArrayList<>();
            for (int i = 0; i < SUBJECTS.length; i++) {
                String username = "coordinator" + (i + 1);
                SubjectCoordinator c = new SubjectCoordinator(
                        nextId(idCounter++), username, personName(i + 100), SUBJECTS[i][0]);
                userDAO.insertWithPassword(c, passwordFor(username, 'C'));
                coordinators.add(c);
            }

            // ---- 8 teachers ----
            List<User> teachers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String username = "teacher" + (i + 1);
                Teacher t = new Teacher(nextId(idCounter++), username, personName(i + 200));
                userDAO.insertWithPassword(t, passwordFor(username, 'T'));
                teachers.add(t);
            }

            // ---- who teaches what ----
            // Courses 01, 03 and 07 deliberately get TWO teachers. מתווה 13.3
            // needs a course where a second teacher can add sources to an
            // existing bot, and that is impossible with one teacher per course.
            link(conn, "course_teacher", "01", teachers.get(0).getUserId());
            link(conn, "course_teacher", "01", teachers.get(1).getUserId());
            link(conn, "course_teacher", "02", teachers.get(1).getUserId());
            link(conn, "course_teacher", "03", teachers.get(2).getUserId());
            link(conn, "course_teacher", "03", teachers.get(3).getUserId());
            link(conn, "course_teacher", "04", teachers.get(3).getUserId());
            link(conn, "course_teacher", "05", teachers.get(4).getUserId());
            link(conn, "course_teacher", "06", teachers.get(5).getUserId());
            link(conn, "course_teacher", "07", teachers.get(6).getUserId());
            link(conn, "course_teacher", "07", teachers.get(7).getUserId());
            link(conn, "course_teacher", "08", teachers.get(7).getUserId());

            // Coordinators teach too - they are teachers with an extra duty.
            link(conn, "course_teacher", "02", coordinators.get(0).getUserId());
            link(conn, "course_teacher", "04", coordinators.get(1).getUserId());
            link(conn, "course_teacher", "06", coordinators.get(2).getUserId());
            link(conn, "course_teacher", "08", coordinators.get(3).getUserId());

            // ---- 40 students, each enrolled in 3 to 5 courses ----
            Random random = new Random(RANDOM_SEED);
            int studentCount = 40;
            for (int i = 0; i < studentCount; i++) {
                String username = "student" + (i + 1);
                Student s = new Student(nextId(idCounter++), username, personName(i));
                userDAO.insertWithPassword(s, passwordFor(username, 'S'));

                int howMany = 3 + random.nextInt(3);          // 3, 4 or 5
                List<String> pool = new ArrayList<>();
                for (String[] course : COURSES) {
                    pool.add(course[0]);
                }
                java.util.Collections.shuffle(pool, random);
                for (int c = 0; c < howMany; c++) {
                    link(conn, "course_student", pool.get(c), s.getUserId());
                }
            }

            conn.commit();

            return "Seeded " + (1 + coordinators.size() + teachers.size() + studentCount)
                 + " users, " + SUBJECTS.length + " subjects, " + COURSES.length + " courses.";

        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private static void seedSubjects(Connection conn) throws SQLException {
        String sql = "INSERT INTO subject (subject_code, name) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] subject : SUBJECTS) {
                ps.setString(1, subject[0]);
                ps.setString(2, subject[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedCourses(Connection conn) throws SQLException {
        String sql = "INSERT INTO course (course_code, name, subject_code) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] course : COURSES) {
                ps.setString(1, course[0]);
                ps.setString(2, course[1]);
                ps.setString(3, course[2]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void link(Connection conn, String table, String courseCode, String userId)
            throws SQLException {
        String sql = "INSERT IGNORE INTO " + table + " (course_code, user_id) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, courseCode);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Builds the next valid Israeli ID.
     *
     * <p>An 8-digit prefix plus the computed check digit, so every generated ID
     * passes {@link IsraeliId#isValid} - which the exam screen will enforce.</p>
     */
    private static String nextId(int sequence) {
        String prefix = String.format("1000%04d", sequence);   // 8 digits
        return IsraeliId.complete(prefix);
    }

    /** The convention documented in docs/test_accounts.md. */
    private static String passwordFor(String username, char roleInitial) {
        return username + "!" + roleInitial;
    }

    /** Every name handed out so far, so that nobody is issued one twice. */
    private static final java.util.Set<String> NAMES_USED = new java.util.LinkedHashSet<>();

    /**
     * A name for one person, unique across everybody the seeder creates.
     *
     * <p>The arithmetic used to be {@code first[i % 20] + last[(i/20 + i) % 14]}, and it
     * collided: twelve names ended up shared between a member of staff and a student,
     * so "Noa Levi" was both a teacher and a girl in her class. Harmless to the code
     * and thoroughly confusing on screen - somebody reading a class list could not
     * tell whether he was seeing pupils or colleagues.</p>
     *
     * <p>Names now come from a set, so the first free combination is taken and nobody
     * shares. Still not random: the same run produces the same people, which is what
     * {@link #RANDOM_SEED} is for elsewhere.</p>
     */
    private static String personName(int index) {
        for (int attempt = 0; attempt < FIRST_NAMES.length * LAST_NAMES.length; attempt++) {
            int spun = index + attempt;
            String first = FIRST_NAMES[Math.floorMod(spun, FIRST_NAMES.length)];
            String last  = LAST_NAMES[Math.floorMod(spun / FIRST_NAMES.length + spun,
                                                    LAST_NAMES.length)];
            String name = first + " " + last;
            if (NAMES_USED.add(name)) {
                return name;
            }
        }
        // 20 x 14 = 280 combinations against ~53 people, so this is unreachable -
        // but a silent duplicate would be worse than an obvious suffix.
        return FIRST_NAMES[Math.floorMod(index, FIRST_NAMES.length)] + " #" + index;
    }
}
