package hsts.server.seed;

import hsts.server.dao.DBController;
import hsts.server.dao.GradeDAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills a freshly-seeded database with questions, exams, sittings and marks.
 *
 * <p>{@link SeedRunner} creates people and courses and deliberately stops there -
 * an empty question bank is the honest starting point for a school. But מתווה
 * item 17 asks for prepared data, and several screens have nothing to show
 * without it: a histogram of no marks is a blank box, and a principal browsing an
 * empty question bank proves nothing.</p>
 *
 * <h2>The marks are real, not written by hand</h2>
 *
 * <p>Every attempt below is seated with actual answers, and the mark is then
 * produced by the real {@link GradeDAO#autoGrade} the teacher's screen uses. Marks
 * inserted directly would drift from the papers they claim to describe, and the
 * first person to open one at the demo would see a mark that does not match the
 * ticks on the page.</p>
 *
 * <h2>What the data is arranged to demonstrate</h2>
 *
 * <p><b>Requirement 59</b> - a teacher gets reports on every exam <em>she wrote</em>,
 * even when another teacher ran it. Noa Levi writes two Plane Geometry exams;
 * she releases one herself and <b>Maya Cohen releases the other</b>. Both must
 * appear in Noa's reports and neither in Maya's. That is hard to show by clicking
 * around and easy to show with data built for it.</p>
 *
 * <p><b>The histogram.</b> The Plane Geometry mid-term is 20 questions worth 5
 * points, so marks land on multiples of 5 and spread across nine of the ten
 * deciles. A class where everyone scores 70 makes a histogram look broken.</p>
 *
 * <p><b>The approval screen.</b> One exam is left waiting for its coordinator and
 * one is rejected with a reason, so that screen is not empty either.</p>
 *
 * <p>Dates are relative to the moment this runs, and one sitting is deliberately
 * <b>open right now</b>, so the take-exam demo works on the day.</p>
 */
public final class DemoContentSeeder {

    private DemoContentSeeder() {
    }

    // -----------------------------------------------------------------
    //  The question bank
    // -----------------------------------------------------------------

    /**
     * course | topic | difficulty | text | option1 | option2 | option3 | option4 | correct
     *
     * <p>Pipe-separated so a question fits on one line and the bank can be read
     * and corrected as a block. Real content, because a bank full of "question 7"
     * tells a reader nothing about whether the screens work.</p>
     */
    private static final String[] BANK = {
        // ---- 01 Plane Geometry (the demo course - a full bank) ----
        "01|Angles|EASY|The angles of a triangle add up to how many degrees?|90|180|270|360|2",
        "01|Angles|EASY|Two angles that add up to 90 degrees are called:|supplementary|complementary|vertical|adjacent|2",
        "01|Angles|EASY|An angle of exactly 180 degrees is called:|acute|right|straight|reflex|3",
        "01|Angles|MEDIUM|Two parallel lines are cut by a transversal. Alternate interior angles are:|equal|supplementary|complementary|unrelated|1",
        "01|Angles|MEDIUM|An exterior angle of a triangle equals:|the nearest interior angle|the sum of the two opposite interior angles|180 degrees|half the triangle|2",
        "01|Angles|HARD|A regular polygon has an interior angle of 156 degrees. How many sides has it?|12|13|15|18|3",
        "01|Triangles|EASY|A triangle with all three sides equal is called:|scalene|isosceles|equilateral|obtuse|3",
        "01|Triangles|EASY|In a right triangle, the side opposite the right angle is the:|base|altitude|hypotenuse|median|3",
        "01|Triangles|MEDIUM|A right triangle has legs of 6 and 8. Its hypotenuse is:|10|12|14|48|1",
        "01|Triangles|MEDIUM|The area of a triangle with base 12 and height 5 is:|17|30|60|120|2",
        "01|Triangles|MEDIUM|Two triangles with equal corresponding angles are:|congruent|similar|equal in area|identical|2",
        "01|Triangles|HARD|A triangle has sides 5, 12 and 13. It is:|acute|right|obtuse|impossible|2",
        "01|Circles|EASY|The distance across a circle through its centre is the:|radius|chord|diameter|arc|3",
        "01|Circles|EASY|The circumference of a circle of radius r is:|pi times r|2 pi r|pi r squared|4 pi r|2",
        "01|Circles|MEDIUM|The area of a circle of radius 5 is closest to:|31.4|78.5|15.7|157|2",
        "01|Circles|MEDIUM|An angle inscribed in a semicircle is always:|45 degrees|60 degrees|90 degrees|180 degrees|3",
        "01|Circles|HARD|A chord 8 long sits 3 from the centre. The radius is:|4|5|6|7|2",
        "01|Quadrilaterals|EASY|A quadrilateral with four equal sides and four right angles is a:|rhombus|rectangle|square|trapezium|3",
        "01|Quadrilaterals|EASY|The angles of a quadrilateral add up to:|180|270|360|540|3",
        "01|Quadrilaterals|MEDIUM|In a parallelogram, opposite angles are:|equal|supplementary|complementary|unrelated|1",
        "01|Quadrilaterals|MEDIUM|The area of a trapezium with parallel sides 6 and 10 and height 4 is:|32|40|64|16|1",
        "01|Quadrilaterals|HARD|A rhombus has diagonals 6 and 8. Its area is:|24|48|14|28|1",
        "01|Area|MEDIUM|Doubling the radius of a circle multiplies its area by:|2|3|4|8|3",
        "01|Area|HARD|A square and a circle have the same perimeter. Which has the larger area?|the square|the circle|they are equal|it depends on the size|2",

        // ---- 02 Algebra ----
        "02|Linear equations|EASY|Solve 3x + 6 = 18.|x = 3|x = 4|x = 6|x = 8|2",
        "02|Linear equations|EASY|Solve 5x = 45.|x = 5|x = 8|x = 9|x = 40|3",
        "02|Linear equations|MEDIUM|Solve 2(x - 3) = 10.|x = 5|x = 8|x = 11|x = 13|2",
        "02|Quadratics|MEDIUM|The solutions of x squared minus 9 = 0 are:|3 only|-3 only|3 and -3|no real solution|3",
        "02|Quadratics|MEDIUM|Factorise x squared + 5x + 6.|(x+1)(x+6)|(x+2)(x+3)|(x+5)(x+1)|(x-2)(x-3)|2",
        "02|Quadratics|HARD|The discriminant of x squared + 2x + 5 is:|-16|4|16|24|1",
        "02|Sequences|MEDIUM|The next term of 3, 7, 11, 15 is:|18|19|20|21|2",
        "02|Sequences|HARD|The sum of the first 10 positive integers is:|45|50|55|100|3",

        // ---- 03 Mechanics ----
        "03|Motion|EASY|Speed is distance divided by:|mass|time|force|acceleration|2",
        "03|Motion|EASY|The SI unit of force is the:|joule|watt|newton|pascal|3",
        "03|Motion|MEDIUM|A car travels 150 km in 2 hours. Its average speed is:|50 km/h|75 km/h|100 km/h|300 km/h|2",
        "03|Forces|MEDIUM|Newton's second law states that force equals:|mass times velocity|mass times acceleration|mass divided by acceleration|energy times time|2",
        "03|Forces|MEDIUM|An object at rest stays at rest unless acted on by:|gravity|friction|an external force|its own weight|3",
        "03|Energy|MEDIUM|Kinetic energy depends on mass and:|the square of the speed|the speed|the distance|the time|1",
        "03|Energy|HARD|A 2 kg object moves at 3 m/s. Its kinetic energy is:|3 J|6 J|9 J|18 J|3",
        "03|Energy|HARD|Work is done only when a force causes:|heat|displacement|pressure|sound|2",

        // ---- 04 Electricity ----
        "04|Circuits|EASY|The unit of electric current is the:|volt|ohm|ampere|watt|3",
        "04|Circuits|EASY|Ohm's law states that voltage equals current times:|power|resistance|charge|time|2",
        "04|Circuits|MEDIUM|Two 4-ohm resistors in series give a total resistance of:|2 ohms|4 ohms|8 ohms|16 ohms|3",
        "04|Circuits|MEDIUM|Two 4-ohm resistors in parallel give a total resistance of:|2 ohms|4 ohms|8 ohms|16 ohms|1",
        "04|Charge|MEDIUM|Like charges:|attract|repel|do nothing|combine|2",
        "04|Charge|EASY|The unit of electric charge is the:|coulomb|farad|henry|tesla|1",
        "04|Power|MEDIUM|Electrical power equals voltage times:|resistance|current|charge|time|2",
        "04|Power|HARD|A 12 V supply drives 2 A. The power used is:|6 W|14 W|24 W|48 W|3",

        // ---- 05 Modern Poetry ----
        "05|Form|EASY|A poem of fourteen lines is called a:|ballad|sonnet|haiku|ode|2",
        "05|Form|EASY|Free verse is poetry without a regular:|subject|metre and rhyme|length|speaker|2",
        "05|Devices|EASY|Repetition of a consonant sound at the start of words is:|assonance|alliteration|onomatopoeia|metaphor|2",
        "05|Devices|MEDIUM|A comparison using 'like' or 'as' is a:|metaphor|simile|symbol|personification|2",
        "05|Devices|MEDIUM|Giving human qualities to something not human is:|hyperbole|irony|personification|allusion|3",
        "05|Devices|MEDIUM|An exaggeration used for effect is:|hyperbole|understatement|paradox|metonymy|1",
        "05|Reading|HARD|The 'speaker' of a poem is best described as:|always the poet|a voice the poet creates|the reader|the editor|2",
        "05|Reading|HARD|Enjambment is when a sentence:|ends with the line|runs past the end of the line|repeats|rhymes|2",

        // ---- 06 Classical Prose ----
        "06|Narrative|EASY|A story told by a character using 'I' is written in:|first person|second person|third person|omniscient|1",
        "06|Narrative|EASY|The sequence of events in a story is the:|theme|plot|setting|tone|2",
        "06|Narrative|MEDIUM|A narrator who knows every character's thoughts is:|limited|unreliable|omniscient|absent|3",
        "06|Narrative|MEDIUM|The turning point of a plot is the:|exposition|climax|resolution|prologue|2",
        "06|Character|MEDIUM|A character who does not change is described as:|round|flat|dynamic|tragic|2",
        "06|Character|MEDIUM|A character who opposes the protagonist is the:|narrator|antagonist|foil|chorus|2",
        "06|Theme|HARD|A recurring image carrying wider meaning is a:|motif|moral|genre|register|1",
        "06|Theme|HARD|Dramatic irony occurs when the reader knows something that:|the writer does not|a character does not|nobody does|is untrue|2",

        // ---- 07 Genetics ----
        "07|Inheritance|EASY|The units of inheritance are called:|cells|genes|tissues|enzymes|2",
        "07|Inheritance|EASY|A gene has alternative forms called:|alleles|codons|chromatids|plasmids|1",
        "07|Inheritance|MEDIUM|An organism with two identical alleles is:|heterozygous|homozygous|haploid|diploid|2",
        "07|Inheritance|MEDIUM|Crossing two heterozygotes gives what ratio of phenotypes?|1:1|2:1|3:1|9:3:3:1|3",
        "07|DNA|EASY|In DNA, adenine pairs with:|cytosine|guanine|thymine|uracil|3",
        "07|DNA|MEDIUM|The shape of a DNA molecule is a:|single helix|double helix|straight chain|sphere|2",
        "07|DNA|MEDIUM|Copying DNA into RNA is called:|replication|transcription|translation|mutation|2",
        "07|Variation|HARD|A change in the DNA sequence is a:|mutation|migration|selection|adaptation|1",

        // ---- 08 Human Anatomy ----
        "08|Skeleton|EASY|How many bones are in the adult human body?|186|206|226|256|2",
        "08|Skeleton|EASY|The thigh bone is called the:|tibia|fibula|femur|humerus|3",
        "08|Skeleton|MEDIUM|The bones of the spine are called:|ribs|vertebrae|sternum|clavicle|2",
        "08|Circulation|EASY|How many chambers has the human heart?|two|three|four|five|3",
        "08|Circulation|MEDIUM|Blood vessels carrying blood away from the heart are:|veins|arteries|capillaries|venules|2",
        "08|Circulation|MEDIUM|The liquid part of blood is called:|plasma|platelets|serum only|lymph|1",
        "08|Systems|MEDIUM|Gas exchange in the lungs happens in the:|bronchi|trachea|alveoli|diaphragm|3",
        "08|Systems|HARD|The largest organ of the human body is the:|liver|brain|skin|lung|3",
    };

    // -----------------------------------------------------------------

    /** How many of the paper's questions each student gets right. */
    private static final int[] MIDTERM_CORRECT = {
        3, 5, 8, 10, 11, 12, 12, 13, 13, 14, 14, 15, 15, 16, 16, 17, 18, 20,
    };
    private static final int[] ENDOFYEAR_CORRECT = {
        7, 9, 11, 12, 13, 14, 14, 15, 16, 17, 18, 19,
    };
    private static final int[] QUIZ_CORRECT = {
        2, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 9, 9, 10, 10,
    };
    private static final int[] SHORT_CORRECT = {
        1, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5,
    };

    private static Connection conn() {
        return DBController.getInstance().getConnection();
    }

    /**
     * Seeds the whole demo dataset. Assumes {@link SeedRunner} has already run and
     * that the content tables are empty.
     *
     * @return a one-line summary for the server console
     */
    public static String seed() throws SQLException {
        askedCounter = 0;      // a fresh run stamps its conversations afresh
        Connection conn = conn();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Map<String, String> staff = loadUserIds();
            Map<String, List<String>> bankByCourse = seedBank(staff);

            LocalDateTime now = LocalDateTime.now();
            int exams = 0, sittings = 0, papers = 0;

            // ---- Plane Geometry: the exam Noa wrote and Noa ran ----
            String midterm = createExam("01", "01", "Plane Geometry mid-term",
                    staff.get("teacher1"), 90,
                    "Answer all twenty questions. Each is worth 5 points.",
                    "Question 17 (the chord) has caught people out before - watch for it.",
                    bankByCourse.get("01").subList(0, 20), 5, staff.get("coordinator1"));
            exams++;
            int midtermSitting = release(midterm, "GEO1", now.minusDays(21),
                    now.minusDays(21).plusHours(2), 90, staff.get("teacher1"));
            sittings++;
            papers += seat(midtermSitting, midterm, studentsOf("01", 18),
                    MIDTERM_CORRECT, 20, now.minusDays(21), staff.get("teacher1"), true);

            // ---- the same author, a DIFFERENT teacher running it (requirement 59) ----
            String endOfYear = createExam("01", "01", "Plane Geometry end of year",
                    staff.get("teacher1"), 90,
                    "Answer all twenty questions. Each is worth 5 points.",
                    null,
                    bankByCourse.get("01").subList(4, 24), 5, staff.get("coordinator1"));
            exams++;
            int endSitting = release(endOfYear, "GEO2", now.minusDays(7),
                    now.minusDays(7).plusHours(2), 90, staff.get("teacher2"));
            sittings++;
            papers += seat(endSitting, endOfYear, studentsOf("01", 12),
                    ENDOFYEAR_CORRECT, 20, now.minusDays(7), staff.get("teacher2"), true);

            // ---- an exam Maya wrote herself, so the two reports differ ----
            String quiz = createExam("01", "01", "Circles and triangles quiz",
                    staff.get("teacher2"), 45,
                    "Ten questions, 10 points each.", null,
                    bankByCourse.get("01").subList(6, 16), 10, staff.get("coordinator1"));
            exams++;
            int quizSitting = release(quiz, "GEO3", now.minusDays(3),
                    now.minusDays(3).plusHours(1), 45, staff.get("teacher2"));
            sittings++;
            papers += seat(quizSitting, quiz, studentsOf("01", 15),
                    QUIZ_CORRECT, 10, now.minusDays(3), staff.get("teacher2"), true);

            // ---- one exam per other subject, so the principal has breadth ----
            String[][] others = {
                {"02", "01", "coordinator1", "coordinator1", "Algebra class test"},
                {"03", "02", "teacher3",     "coordinator2", "Mechanics class test"},
                {"07", "04", "teacher7",     "coordinator4", "Genetics class test"},
            };
            int codeSuffix = 1;
            for (String[] row : others) {
                String course = row[0], subject = row[1];
                String examId = createExam(course, subject, row[4], staff.get(row[2]), 40,
                        "Five questions, 20 points each.", null,
                        bankByCourse.get(course).subList(0, 5), 20, staff.get(row[3]));
                exams++;
                int sitting = release(examId, "EX0" + codeSuffix++, now.minusDays(10),
                        now.minusDays(10).plusHours(1), 40, staff.get(row[2]));
                sittings++;
                papers += seat(sitting, examId, studentsOf(course, 11),
                        SHORT_CORRECT, 5, now.minusDays(10), staff.get(row[2]), true);
            }

            // ---- a sitting that is OPEN RIGHT NOW, for the take-exam demo ----
            String live = createExam("01", "01", "Plane Geometry practice paper",
                    staff.get("teacher1"), 60,
                    "Answer all ten questions. Each is worth 10 points.",
                    "This is the sitting used for the live demonstration.",
                    bankByCourse.get("01").subList(10, 20), 10, staff.get("coordinator1"));
            exams++;
            release(live, "NOW1", now.minusMinutes(30), now.plusHours(6), 60,
                    staff.get("teacher1"));
            sittings++;

            // ---- one waiting for approval, one rejected, so that screen is not empty ----
            createPending("01", "01", "Angles and quadrilaterals test",
                    staff.get("teacher2"), 60,
                    bankByCourse.get("01").subList(2, 12), 10);
            createRejected("02", "01", "Algebra revision test",
                    staff.get("teacher2"), 60,
                    bankByCourse.get("02").subList(0, 5), 20, staff.get("coordinator1"),
                    "Three of these questions were on last term's paper. Please replace them.");
            exams += 2;

            int bots = seedBots(staff, bankByCourse);

            conn.commit();
            return "Demo content seeded: " + BANK.length + " questions, " + exams
                 + " exams, " + sittings + " sittings, " + papers + " marked papers, "
                 + bots + " study bots.";

        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    // -----------------------------------------------------------------
    //  Study bots
    // -----------------------------------------------------------------

    /**
     * Bots arranged to show the two things that are hard to see otherwise.
     *
     * <p><b>A teacher with more than one course.</b> Yael Peretz teaches Mechanics
     * and Electricity, and has a bot on each - so her screen proves the list is per
     * <em>teacher</em>, not per course.</p>
     *
     * <p><b>A course with more than one bot, only one of them on.</b> Mechanics has a
     * general bot that is active and a revision bot that is not. Switching the second
     * on will switch the first off, and say so.</p>
     *
     * <p>The Mechanics bot also has conversations against it - including one question
     * asked twice - so the usage screen has something to rank and something to open.</p>
     */
    private static int seedBots(Map<String, String> staff,
                               Map<String, List<String>> bankByCourse) throws SQLException {

        String yael = staff.get("teacher4");        // teaches 03 Mechanics and 04 Electricity
        String tamar = staff.get("teacher3");       // also teaches 03, for requirement 67

        int mechanics = insertBot("03", "Mechanics helper", yael, "ACTIVE");
        insertSource(mechanics, "QUESTION_BANK", "Mechanics question bank",
                questionBankText("03"), yael);
        insertSource(mechanics, "FREE_TEXT", "Newton's laws in plain words", """
                First law: a thing stays still, or keeps moving the same way, until a \
                force acts on it. Second law: force equals mass times acceleration, so \
                a heavier thing needs more force to speed up the same amount. Third \
                law: every force has an equal and opposite force back.

                Speed is distance divided by time. Acceleration is the change in speed \
                divided by the time it took.""", yael);
        // Requirement 67: a colleague on the same course adds to the same bot.
        insertSource(mechanics, "FREE_TEXT", "Tamar's note on energy", """
                Kinetic energy is one half times mass times speed squared, so doubling \
                the speed makes four times the energy. Work is done only when a force \
                actually moves something - pushing a wall that does not move is no \
                work at all.""", tamar);

        // A second bot on the SAME course, deliberately left switched off.
        int revision = insertBot("03", "Mechanics revision bot", tamar, "INACTIVE");
        insertSource(revision, "FREE_TEXT", "Exam-style practice", """
                For revision, work through past questions on motion, forces and \
                energy. Draw the forces on a diagram before doing any arithmetic, and \
                always write down the units.""", tamar);

        int electricity = insertBot("04", "Electricity helper", yael, "ACTIVE");
        insertSource(electricity, "QUESTION_BANK", "Electricity question bank",
                questionBankText("04"), yael);
        insertSource(electricity, "FREE_TEXT", "Ohm's law and circuits", """
                Ohm's law: voltage equals current times resistance. In series, add the \
                resistances. In parallel, add the reciprocals and take the reciprocal \
                of the total. Power equals voltage times current.""", yael);

        // Conversations, so the usage screen is not empty. One is asked twice, so the
        // "most-asked" ranking has something real to put at the top.
        List<String> students = studentsOf("03", 3);
        if (students.size() >= 3) {
            ask(mechanics, students.get(0),
                "What is Newton's second law?",
                "Newton's second law says force equals mass times acceleration. So if "
              + "you push two things with the same force, the heavier one speeds up "
              + "less. Written as a formula it is F = m a.");
            ask(mechanics, students.get(1),
                "What is Newton's second law?",
                "Newton's second law says force equals mass times acceleration. It "
              + "means the same force produces less acceleration on a heavier object. "
              + "F = m a.");
            ask(mechanics, students.get(2),
                "Why does a heavier object need more force to speed up?",
                "Because acceleration is force divided by mass. If the mass is bigger "
              + "and you want the same acceleration, the force has to be bigger too. "
              + "That follows straight from F = m a.");
            ask(mechanics, students.get(0),
                "How do I work out kinetic energy for a 2 kg ball at 3 m/s?",
                "Kinetic energy is one half times mass times speed squared. So it is "
              + "0.5 times 2 times 3 times 3, which is 9 joules.");
            ask(electricity, students.get(0),
                "Two 4 ohm resistors in parallel - what is the total?",
                "In parallel you add the reciprocals: 1/4 plus 1/4 is 1/2. Then take "
              + "the reciprocal of that, which gives 2 ohms. Two equal resistors in "
              + "parallel always give half of one of them.");
        }
        return 3;
    }

    /**
     * The course's questions as text, the same shape {@code BotController} builds.
     *
     * <p>Written here rather than borrowed from the controller because the seeder
     * runs before any user is signed in, and the controller's version is behind a
     * permission check that has no user to check.</p>
     */
    private static String questionBankText(String courseCode) throws SQLException {
        StringBuilder out = new StringBuilder();
        String sql = """
            SELECT q.question_id, q.text, q.topic,
                   a.answer_no, a.text AS answer_text, a.is_correct
            FROM question q
            JOIN answer a ON a.question_id = q.question_id
                        AND a.question_version = q.version
            WHERE q.course_code = ? AND q.is_current = TRUE AND q.is_deleted = FALSE
            ORDER BY q.question_id, a.answer_no""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                String current = null;
                while (rs.next()) {
                    String id = rs.getString("question_id");
                    if (!id.equals(current)) {
                        if (current != null) {
                            out.append('\n');
                        }
                        out.append("Q: ").append(rs.getString("text")).append('\n')
                           .append("   topic: ").append(rs.getString("topic")).append('\n');
                        current = id;
                    }
                    out.append("   ").append(rs.getInt("answer_no")).append(". ")
                       .append(rs.getString("answer_text"))
                       .append(rs.getBoolean("is_correct") ? "   [correct]" : "")
                       .append('\n');
                }
            }
        }
        return out.toString().trim();
    }

    private static int insertBot(String courseCode, String name, String createdBy,
                                 String status) throws SQLException {
        String sql = """
            INSERT INTO bot (course_code, name, status, created_by, created_at)
            VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, courseCode);
            ps.setString(2, name);
            ps.setString(3, status);
            ps.setString(4, createdBy);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now().minusDays(14)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static void insertSource(int botId, String type, String title,
                                     String content, String addedBy) throws SQLException {
        String sql = """
            INSERT INTO knowledge_source (bot_id, type, title, content, added_by, added_at)
            VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, botId);
            ps.setString(2, type);
            ps.setString(3, title);
            ps.setString(4, content);
            ps.setString(5, addedBy);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now().minusDays(13)));
            ps.executeUpdate();
        }
    }

    private static int askedCounter;

    private static void ask(int botId, String studentId, String question, String answer)
            throws SQLException {
        String sql = """
            INSERT INTO bot_conversation (bot_id, student_id, question_text,
                                          answer_text, asked_at)
            VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, botId);
            ps.setString(2, studentId);
            ps.setString(3, question);
            ps.setString(4, answer);
            // Spread over recent days so the "recent" list has a believable order.
            ps.setTimestamp(5, Timestamp.valueOf(
                    LocalDateTime.now().minusHours(6L * ++askedCounter).withNano(0)));
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    //  People and the bank
    // -----------------------------------------------------------------

    private static Map<String, String> loadUserIds() throws SQLException {
        Map<String, String> byUsername = new HashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT username, user_id FROM users");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byUsername.put(rs.getString(1), rs.getString(2));
            }
        }
        return byUsername;
    }

    /** Enrolled students of a course, in a stable order, at most {@code howMany}. */
    private static List<String> studentsOf(String courseCode, int howMany) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT cs.user_id FROM course_student cs JOIN users u ON u.user_id = cs.user_id "
              + "WHERE cs.course_code = ? ORDER BY u.full_name LIMIT ?")) {
            ps.setString(1, courseCode);
            ps.setInt(2, howMany);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    /** Writes the bank and returns the question ids of each course, in order. */
    private static Map<String, List<String>> seedBank(Map<String, String> staff)
            throws SQLException {

        Map<String, List<String>> byCourse = new LinkedHashMap<>();
        Map<String, Integer> counter = new HashMap<>();

        String qSql = """
            INSERT INTO question (question_id, version, course_code, text, instructions,
                                  topic, difficulty, is_current, is_deleted, author_id, created_at)
            VALUES (?, 1, ?, ?, NULL, ?, ?, TRUE, FALSE, ?, ?)""";
        String aSql = """
            INSERT INTO answer (question_id, question_version, answer_no, text, is_correct)
            VALUES (?, 1, ?, ?, ?)""";

        try (PreparedStatement qs = conn().prepareStatement(qSql);
             PreparedStatement as = conn().prepareStatement(aSql)) {

            for (String line : BANK) {
                String[] f = line.split("\\|", -1);
                String course = f[0], topic = f[1], difficulty = f[2], text = f[3];
                int correct = Integer.parseInt(f[8]);

                int next = counter.merge(course, 1, Integer::sum);
                String questionId = String.format("%03d%s", next, course);

                // The author must teach the course, or the question would be one no
                // screen lets its owner edit.
                String author = firstTeacherOf(course, staff);

                qs.setString(1, questionId);
                qs.setString(2, course);
                qs.setString(3, text);
                qs.setString(4, topic);
                qs.setString(5, difficulty);
                qs.setString(6, author);
                qs.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now().minusDays(40)));
                qs.executeUpdate();

                for (int i = 1; i <= 4; i++) {
                    as.setString(1, questionId);
                    as.setInt(2, i);
                    as.setString(3, f[3 + i]);
                    as.setBoolean(4, i == correct);
                    as.executeUpdate();
                }
                byCourse.computeIfAbsent(course, c -> new ArrayList<>()).add(questionId);
            }
        }
        return byCourse;
    }

    private static final Map<String, String> COURSE_OWNER = Map.of(
            "01", "teacher1", "02", "coordinator1", "03", "teacher3", "04", "teacher4",
            "05", "teacher5", "06", "teacher6",     "07", "teacher7", "08", "teacher8");

    private static String firstTeacherOf(String course, Map<String, String> staff) {
        return staff.get(COURSE_OWNER.get(course));
    }

    // -----------------------------------------------------------------
    //  Exams
    // -----------------------------------------------------------------

    private static String nextExamId(String course, String subject) throws SQLException {
        int next = 1;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT MAX(CAST(LEFT(exam_id, 2) AS UNSIGNED)) FROM exam WHERE course_code = ?")) {
            ps.setString(1, course);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    next = rs.getInt(1) + 1;
                }
            }
        }
        return String.format("%02d%s%s", next, course, subject);
    }

    private static String writeExam(String course, String subject, String name, String author,
                                    int minutes, String forStudents, String forTeacher,
                                    List<String> questionIds, int points,
                                    String status, String approvedBy, String rejection)
            throws SQLException {

        String examId = nextExamId(course, subject);
        String sql = """
            INSERT INTO exam (exam_id, version, name, course_code, subject_code,
                              duration_minutes,
                              instructions_for_students, notes_for_teacher, author_id, status,
                              rejection_reason, approved_by, approved_at, is_current, created_at)
            VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            LocalDateTime created = LocalDateTime.now().minusDays(30);
            ps.setString(1, examId);
            ps.setString(2, name);
            ps.setString(3, course);
            ps.setString(4, subject);
            ps.setInt(5, minutes);
            ps.setString(6, forStudents);
            ps.setString(7, forTeacher);
            ps.setString(8, author);
            ps.setString(9, status);
            ps.setString(10, rejection);
            ps.setString(11, approvedBy);
            ps.setTimestamp(12, approvedBy == null ? null
                    : Timestamp.valueOf(created.plusDays(1)));
            ps.setTimestamp(13, Timestamp.valueOf(created));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn().prepareStatement("""
                INSERT INTO exam_question (exam_id, exam_version, question_id, question_version,
                                           points, q_order)
                VALUES (?, 1, ?, 1, ?, ?)""")) {
            int order = 1;
            for (String questionId : questionIds) {
                ps.setString(1, examId);
                ps.setString(2, questionId);
                ps.setInt(3, points);
                ps.setInt(4, order++);
                ps.executeUpdate();
            }
        }
        return examId;
    }

    private static String createExam(String course, String subject, String name, String author,
                                     int minutes, String forStudents, String forTeacher,
                                     List<String> questionIds, int points, String coordinator)
            throws SQLException {
        return writeExam(course, subject, name, author, minutes, forStudents, forTeacher,
                questionIds, points, "APPROVED", coordinator, null);
    }

    private static void createPending(String course, String subject, String name, String author,
                                      int minutes, List<String> questionIds, int points)
            throws SQLException {
        writeExam(course, subject, name, author, minutes,
                "Answer all questions.", null, questionIds, points,
                "PENDING_APPROVAL", null, null);
    }

    private static void createRejected(String course, String subject, String name, String author,
                                       int minutes, List<String> questionIds, int points,
                                       String coordinator, String why) throws SQLException {
        writeExam(course, subject, name, author, minutes,
                "Answer all questions.", null, questionIds, points,
                "REJECTED", coordinator, why);
    }

    // -----------------------------------------------------------------
    //  Sittings and papers
    // -----------------------------------------------------------------

    private static int release(String examId, String code, LocalDateTime open,
                               LocalDateTime close, int minutes, String releasedBy)
            throws SQLException {
        String sql = """
            INSERT INTO exam_execution (exam_id, exam_version, execution_code, open_time,
                                        close_time, allocated_duration, original_duration,
                                        max_attempts, released_by, created_at)
            VALUES (?, 1, ?, ?, ?, ?, ?, 1, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql,
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, examId);
            ps.setString(2, code);
            ps.setTimestamp(3, Timestamp.valueOf(open.withNano(0)));
            ps.setTimestamp(4, Timestamp.valueOf(close.withNano(0)));
            ps.setInt(5, minutes);
            ps.setInt(6, minutes);
            ps.setString(7, releasedBy);
            ps.setTimestamp(8, Timestamp.valueOf(open.minusDays(1).withNano(0)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /**
     * Seats each student, answers for her, marks the paper and optionally publishes.
     *
     * <p>{@code correctCounts[i]} is how many of the paper's questions student
     * {@code i} gets right. Her answers are then written so that exactly that many
     * are correct, and the mark is produced by the real automatic marking rather
     * than inserted - so the number on the screen always matches the ticks on the
     * page.</p>
     *
     * @return how many papers were marked
     */
    private static int seat(int executionId, String examId, List<String> students,
                            int[] correctCounts, int questionCount, LocalDateTime sat,
                            String teacherId, boolean approve) throws SQLException {

        List<String> paper = questionsOf(examId);
        Map<String, Integer> correctOption = correctOptions(paper);
        GradeDAO gradeDAO = new GradeDAO();
        int marked = 0;

        for (int i = 0; i < students.size() && i < correctCounts.length; i++) {
            String studentId = students.get(i);

            // Rotated by the sitting, so the same girl is not bottom of every class.
            // Without this, marks are handed out by position in an alphabetical
            // list and one student is last in every exam she sits - which makes the
            // "compare one student's exams" report of מתווה 12 a flat line and
            // teaches a reader nothing about whether it works.
            int slot = (i + executionId * 3) % correctCounts.length;
            int rightAnswers = Math.min(correctCounts[slot], questionCount);

            // A believable spread of finishing times, and never past the deadline.
            int minutesTaken = 20 + (i * 7) % 40;
            LocalDateTime start = sat.plusMinutes(i % 5).withNano(0);

            int submissionId;
            try (PreparedStatement ps = conn().prepareStatement("""
                    INSERT INTO student_exam (execution_id, student_id, attempt_no, start_time,
                                              deadline, end_time, actual_duration, status)
                    VALUES (?, ?, 1, ?, ?, ?, ?, 'FINISHED')""",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, executionId);
                ps.setString(2, studentId);
                ps.setTimestamp(3, Timestamp.valueOf(start));
                ps.setTimestamp(4, Timestamp.valueOf(start.plusMinutes(90)));
                ps.setTimestamp(5, Timestamp.valueOf(start.plusMinutes(minutesTaken)));
                ps.setInt(6, minutesTaken);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    submissionId = keys.getInt(1);
                }
            }

            try (PreparedStatement ps = conn().prepareStatement("""
                    INSERT INTO student_answer (submission_id, question_id, question_version,
                                                selected_answer_no)
                    VALUES (?, ?, 1, ?)""")) {
                for (int q = 0; q < paper.size(); q++) {
                    String questionId = paper.get(q);
                    int right = correctOption.get(questionId);
                    // The first `rightAnswers` questions are answered correctly and
                    // the rest wrong, which is enough to hit the intended mark. One
                    // student in five leaves her last question blank, so blank-counts-
                    // as-wrong is visible in the data too.
                    boolean answerRight = q < rightAnswers;
                    if (!answerRight && i % 5 == 0 && q == paper.size() - 1) {
                        continue;                       // left blank
                    }
                    ps.setInt(1, submissionId);
                    ps.setString(2, questionId);
                    ps.setInt(3, answerRight ? right : wrongOption(right));
                    ps.executeUpdate();
                }
            }

            gradeDAO.autoGrade(submissionId);
            if (approve) {
                gradeDAO.approve(submissionId, teacherId);
            }
            marked++;
        }
        return marked;
    }

    private static int wrongOption(int correct) {
        return correct == 1 ? 2 : 1;
    }

    private static List<String> questionsOf(String examId) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT question_id FROM exam_question WHERE exam_id = ? AND exam_version = 1 "
              + "ORDER BY q_order")) {
            ps.setString(1, examId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    private static Map<String, Integer> correctOptions(List<String> questionIds)
            throws SQLException {
        Map<String, Integer> map = new HashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT answer_no FROM answer WHERE question_id = ? AND question_version = 1 "
              + "AND is_correct = TRUE")) {
            for (String questionId : questionIds) {
                ps.setString(1, questionId);
                try (ResultSet rs = ps.executeQuery()) {
                    map.put(questionId, rs.next() ? rs.getInt(1) : 1);
                }
            }
        }
        return map;
    }
}
