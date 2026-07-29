package hsts.server.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the HSTS database schema.
 *
 * <p>Everything is {@code CREATE TABLE IF NOT EXISTS}, so starting the server on
 * a fresh laptop needs no manual SQL at all - which matters, because the system
 * has to be installed on two machines and demonstrated live.</p>
 *
 * <h2>How versioning works</h2>
 *
 * <p>Questions and exams are never overwritten. Editing inserts a <em>new row</em>
 * with {@code version + 1} and moves the {@code is_current} flag onto it; the old
 * row stays exactly as it was. That is what מתווה scenario 2 item 2 and scenario 3
 * item 5 require.</p>
 *
 * <p>The piece that makes an old exam keep working is in {@code exam_question}:
 * it stores the question id <em>and</em> the question version. An exam built in
 * March points at (question 10123, version 1), so when that question is later
 * edited into version 2, the March exam still shows what the students actually
 * saw. {@code exam_execution} does the same one level up, pinning the exam version.</p>
 *
 * <p>The 5-digit question number and 6-digit exam number are unchanged and remain
 * the only identifiers shown on screen. The version lives beside them, invisible
 * to the user, because those formats are fixed by the system description and have
 * no spare digit.</p>
 */
public final class SchemaManager {

    private SchemaManager() {
    }

    /** Creates every table, in dependency order. Safe to run repeatedly. */
    public static void createSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {

            // ---------- externally managed reference data ----------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS subject (
                  subject_code CHAR(2)      PRIMARY KEY,
                  name         VARCHAR(100) NOT NULL
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS course (
                  course_code  CHAR(2)      PRIMARY KEY,
                  name         VARCHAR(100) NOT NULL,
                  subject_code CHAR(2)      NOT NULL,
                  CONSTRAINT fk_course_subject FOREIGN KEY (subject_code)
                    REFERENCES subject(subject_code)
                ) ENGINE=InnoDB""");

            // ---------- users ----------
            // Passwords are stored only as a salted SHA-256 hash. The plaintext
            // is never written anywhere; see PasswordHasher and docs/test_accounts.md.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                  user_id             CHAR(9)      PRIMARY KEY,
                  username            VARCHAR(50)  NOT NULL UNIQUE,
                  password_hash       CHAR(64)     NOT NULL,
                  password_salt       CHAR(32)     NOT NULL,
                  full_name           VARCHAR(100) NOT NULL,
                  role                ENUM('TEACHER','COORDINATOR','STUDENT','PRINCIPAL') NOT NULL,
                  coordinated_subject CHAR(2)      NULL,
                  CONSTRAINT fk_users_subject FOREIGN KEY (coordinated_subject)
                    REFERENCES subject(subject_code)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS course_teacher (
                  course_code CHAR(2) NOT NULL,
                  user_id     CHAR(9) NOT NULL,
                  PRIMARY KEY (course_code, user_id),
                  CONSTRAINT fk_ct_course FOREIGN KEY (course_code) REFERENCES course(course_code),
                  CONSTRAINT fk_ct_user   FOREIGN KEY (user_id)     REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS course_student (
                  course_code CHAR(2) NOT NULL,
                  user_id     CHAR(9) NOT NULL,
                  PRIMARY KEY (course_code, user_id),
                  CONSTRAINT fk_cs_course FOREIGN KEY (course_code) REFERENCES course(course_code),
                  CONSTRAINT fk_cs_user   FOREIGN KEY (user_id)     REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            // ---------- question bank (versioned) ----------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS question (
                  question_id  CHAR(5)     NOT NULL,
                  version      INT         NOT NULL DEFAULT 1,
                  course_code  CHAR(2)     NOT NULL,
                  text         TEXT        NOT NULL,
                  instructions TEXT        NULL,
                  topic        VARCHAR(60) NOT NULL,
                  difficulty   ENUM('EASY','MEDIUM','HARD') NOT NULL,
                  image        LONGBLOB    NULL,
                  is_current   BOOLEAN     NOT NULL DEFAULT TRUE,
                  is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
                  author_id    CHAR(9)     NOT NULL,
                  created_at   DATETIME    NOT NULL,
                  PRIMARY KEY (question_id, version),
                  KEY ix_question_course  (course_code, is_current, is_deleted),
                  KEY ix_question_topic   (course_code, topic, difficulty),
                  CONSTRAINT fk_q_course FOREIGN KEY (course_code) REFERENCES course(course_code),
                  CONSTRAINT fk_q_author FOREIGN KEY (author_id)   REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS answer (
                  question_id      CHAR(5) NOT NULL,
                  question_version INT     NOT NULL,
                  answer_no        TINYINT NOT NULL,
                  text             TEXT    NOT NULL,
                  is_correct       BOOLEAN NOT NULL DEFAULT FALSE,
                  PRIMARY KEY (question_id, question_version, answer_no),
                  CONSTRAINT ck_answer_no CHECK (answer_no BETWEEN 1 AND 4),
                  CONSTRAINT fk_a_question FOREIGN KEY (question_id, question_version)
                    REFERENCES question(question_id, version) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            // ---------- exams (versioned) ----------
            // NOTE: there is no IN_DRAWER status. "In the drawer" is not stored -
            // it is answered by asking whether the exam has an open execution.
            // See docs/01_implementation_plan.md section 0, decision 1.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS exam (
                  exam_id      CHAR(6)  NOT NULL,
                  version      INT      NOT NULL DEFAULT 1,
                  course_code  CHAR(2)  NOT NULL,
                  subject_code CHAR(2)  NOT NULL,
                  duration_minutes          INT  NOT NULL,
                  instructions_for_students TEXT NULL,
                  notes_for_teacher         TEXT NULL,
                  author_id    CHAR(9)  NOT NULL,
                  status       ENUM('PENDING_APPROVAL','APPROVED','REJECTED')
                               NOT NULL DEFAULT 'PENDING_APPROVAL',
                  rejection_reason TEXT NULL,
                  approved_by  CHAR(9)  NULL,
                  approved_at  DATETIME NULL,
                  is_current   BOOLEAN  NOT NULL DEFAULT TRUE,
                  created_at   DATETIME NOT NULL,
                  PRIMARY KEY (exam_id, version),
                  CONSTRAINT ck_exam_duration CHECK (duration_minutes > 0),
                  CONSTRAINT fk_e_course FOREIGN KEY (course_code) REFERENCES course(course_code),
                  CONSTRAINT fk_e_author FOREIGN KEY (author_id)   REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS exam_question (
                  exam_id          CHAR(6) NOT NULL,
                  exam_version     INT     NOT NULL,
                  question_id      CHAR(5) NOT NULL,
                  question_version INT     NOT NULL,
                  points           INT     NOT NULL,
                  q_order          INT     NOT NULL,
                  PRIMARY KEY (exam_id, exam_version, q_order),
                  UNIQUE KEY uq_exam_question (exam_id, exam_version, question_id),
                  CONSTRAINT ck_points CHECK (points > 0),
                  CONSTRAINT fk_eq_exam FOREIGN KEY (exam_id, exam_version)
                    REFERENCES exam(exam_id, version) ON DELETE CASCADE,
                  CONSTRAINT fk_eq_question FOREIGN KEY (question_id, question_version)
                    REFERENCES question(question_id, version)
                ) ENGINE=InnoDB""");

            // ---------- taking an exam out of the drawer ----------
            // close_time is a deadline to START, not to finish. A student who
            // begins at 11:55 with 90 minutes allotted finishes at 13:25.
            // Her personal deadline is stored on student_exam.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS exam_execution (
                  execution_id       INT AUTO_INCREMENT PRIMARY KEY,
                  exam_id            CHAR(6)  NOT NULL,
                  exam_version       INT      NOT NULL,
                  execution_code     CHAR(4)  NOT NULL UNIQUE,
                  open_time          DATETIME NOT NULL,
                  close_time         DATETIME NOT NULL,
                  allocated_duration INT      NOT NULL,
                  original_duration  INT      NOT NULL,
                  max_attempts       INT      NOT NULL DEFAULT 1,
                  released_by        CHAR(9)  NOT NULL,
                  created_at         DATETIME NOT NULL,
                  CONSTRAINT ck_window CHECK (close_time > open_time),
                  CONSTRAINT fk_ex_exam FOREIGN KEY (exam_id, exam_version)
                    REFERENCES exam(exam_id, version),
                  CONSTRAINT fk_ex_teacher FOREIGN KEY (released_by) REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS student_exam (
                  submission_id   INT AUTO_INCREMENT PRIMARY KEY,
                  execution_id    INT      NOT NULL,
                  student_id      CHAR(9)  NOT NULL,
                  attempt_no      INT      NOT NULL DEFAULT 1,
                  start_time      DATETIME NOT NULL,
                  deadline        DATETIME NOT NULL,
                  end_time        DATETIME NULL,
                  actual_duration INT      NULL,
                  status          ENUM('IN_PROGRESS','FINISHED','TIMED_OUT')
                                  NOT NULL DEFAULT 'IN_PROGRESS',
                  UNIQUE KEY uq_attempt (execution_id, student_id, attempt_no),
                  CONSTRAINT fk_se_exec    FOREIGN KEY (execution_id)
                    REFERENCES exam_execution(execution_id),
                  CONSTRAINT fk_se_student FOREIGN KEY (student_id) REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS student_answer (
                  submission_id      INT     NOT NULL,
                  question_id        CHAR(5) NOT NULL,
                  question_version   INT     NOT NULL,
                  selected_answer_no TINYINT NULL,
                  PRIMARY KEY (submission_id, question_id, question_version),
                  CONSTRAINT fk_sa_sub FOREIGN KEY (submission_id)
                    REFERENCES student_exam(submission_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            // ---------- grading ----------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS grade (
                  submission_id             INT      PRIMARY KEY,
                  auto_grade                INT      NOT NULL,
                  final_grade               INT      NOT NULL,
                  factor                    INT      NOT NULL DEFAULT 0,
                  is_approved               BOOLEAN  NOT NULL DEFAULT FALSE,
                  approved_at               DATETIME NULL,
                  manual_change_explanation TEXT     NULL,
                  teacher_general_comment   TEXT     NULL,
                  graded_by                 CHAR(9)  NULL,
                  CONSTRAINT ck_grade_range CHECK (final_grade BETWEEN 0 AND 100),
                  CONSTRAINT fk_g_sub FOREIGN KEY (submission_id)
                    REFERENCES student_exam(submission_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS question_feedback (
                  submission_id    INT     NOT NULL,
                  question_id      CHAR(5) NOT NULL,
                  question_version INT     NOT NULL,
                  comment          TEXT    NULL,
                  is_wrong         BOOLEAN NOT NULL DEFAULT FALSE,
                  PRIMARY KEY (submission_id, question_id, question_version),
                  CONSTRAINT fk_qf_sub FOREIGN KEY (submission_id)
                    REFERENCES student_exam(submission_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS exam_statistics (
                  execution_id INT PRIMARY KEY,
                  average DOUBLE NOT NULL,
                  median  DOUBLE NOT NULL,
                  d1 INT NOT NULL, d2 INT NOT NULL, d3 INT NOT NULL, d4  INT NOT NULL,
                  d5 INT NOT NULL, d6 INT NOT NULL, d7 INT NOT NULL, d8  INT NOT NULL,
                  d9 INT NOT NULL, d10 INT NOT NULL,
                  computed_at DATETIME NOT NULL,
                  CONSTRAINT fk_st_exec FOREIGN KEY (execution_id)
                    REFERENCES exam_execution(execution_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            // ---------- study bot ----------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bot (
                  bot_id      INT AUTO_INCREMENT PRIMARY KEY,
                  course_code CHAR(2)      NOT NULL,
                  name        VARCHAR(100) NOT NULL,
                  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'INACTIVE',
                  created_by  CHAR(9)      NOT NULL,
                  created_at  DATETIME     NOT NULL,
                  INDEX idx_bot_course (course_code),
                  CONSTRAINT fk_bot_course FOREIGN KEY (course_code) REFERENCES course(course_code)
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS knowledge_source (
                  source_id INT AUTO_INCREMENT PRIMARY KEY,
                  bot_id    INT          NOT NULL,
                  type      ENUM('QUESTION_BANK','PDF','WORD','FREE_TEXT') NOT NULL,
                  title     VARCHAR(200) NOT NULL,
                  content   MEDIUMTEXT   NOT NULL,
                  added_by  CHAR(9)      NOT NULL,
                  added_at  DATETIME     NOT NULL,
                  CONSTRAINT fk_ks_bot FOREIGN KEY (bot_id) REFERENCES bot(bot_id) ON DELETE CASCADE
                ) ENGINE=InnoDB""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bot_conversation (
                  conv_id       INT AUTO_INCREMENT PRIMARY KEY,
                  bot_id        INT        NOT NULL,
                  student_id    CHAR(9)    NOT NULL,
                  question_text TEXT       NOT NULL,
                  answer_text   MEDIUMTEXT NOT NULL,
                  asked_at      DATETIME   NOT NULL,
                  CONSTRAINT fk_bc_bot     FOREIGN KEY (bot_id)     REFERENCES bot(bot_id),
                  CONSTRAINT fk_bc_student FOREIGN KEY (student_id) REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            // ---------- 3-strikes lockout (requirement 39) ----------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS code_attempt (
                  student_id   CHAR(9)  PRIMARY KEY,
                  fail_count   INT      NOT NULL DEFAULT 0,
                  locked_until DATETIME NULL,
                  CONSTRAINT fk_ca_student FOREIGN KEY (student_id) REFERENCES users(user_id)
                ) ENGINE=InnoDB""");

            // ---------- clean up milestone 1 scaffolding ----------
            st.executeUpdate("DROP TABLE IF EXISTS m1_skeleton");
            st.executeUpdate("DROP TABLE IF EXISTS m1_skeleton_user");

            migrate(conn, st);
        }
    }

    /**
     * Changes to tables that already exist on a running database.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} does nothing to a table that is already
     * there, so a changed definition would only reach a database created from
     * scratch. Anything altered after the first release needs a step here, and each
     * one must be safe to run on every start-up.</p>
     */
    private static void migrate(Connection conn, Statement st) throws SQLException {

        // A course may now have several bots, with at most one of them active.
        // It began as one bot per course, enforced by UNIQUE on course_code - which
        // is exactly the constraint that has to go. The "only one active" half is a
        // rule about a subset of rows, which MySQL cannot express as an index, so it
        // lives in BotController where it can also explain itself to the teacher.
        //
        // The replacement index is created FIRST. The foreign key fk_bot_course needs
        // an index on the column, and MySQL refuses to drop the last one that serves
        // it: "Cannot drop index 'course_code': needed in a foreign key constraint".
        // With idx_bot_course already in place the key uses that instead, and the
        // unique index becomes droppable.
        if (indexExists(conn, "bot", "course_code")) {
            if (!indexExists(conn, "bot", "idx_bot_course")) {
                st.executeUpdate("CREATE INDEX idx_bot_course ON bot (course_code)");
            }
            st.executeUpdate("ALTER TABLE bot DROP INDEX course_code");
        }
    }

    /** True when a named index is on a table, so a migration can be skipped. */
    private static boolean indexExists(Connection conn, String table, String indexName)
            throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?""";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
