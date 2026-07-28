# HSTS — Phase 1 Implementation Plan

**Group 1** · Software Engineering 203.3140 · Spring 2026
**Written:** 2026-07-29 · **Status:** awaiting approval. No implementation code written yet.

Companion document: [00_understanding.md](00_understanding.md)

---

## 0. What is already settled

All twelve open questions from Phase 0 are answered. Recorded here so nothing drifts.

| # | Decision |
|---|---|
| 1 | **`IN_DRAWER` is deleted from `ExamStatus`.** Three values remain: `PENDING_APPROVAL`, `APPROVED`, `REJECTED`. "In the drawer" is not stored — it is answered by asking whether the exam has an open execution. |
| 2 | **Versioning by hidden columns.** The 5-digit question number and 6-digit exam number stay exactly as specified and are the only IDs shown on screen. Behind them sit a `version` number and an `is_current` flag. Primary key becomes (id + version). |
| 3 | **The teacher releases from the drawer** — open time, close time, 4-character code and attempt count, in one action. Follows מתווה scenario 5. |
| 4 | **A teacher sees the union** — exams she wrote *and* executions she released. |
| 5 | **The teacher who released the execution marks and approves it.** In the A-writes/B-releases case, that is B. The principal can never approve — she reads and pulls statistics only. |
| 6 | `IUserManagementSystem` stays as an interface, implemented as a **local adapter over our own `users` table**. Nothing external is called. |
| 7 | **The inactivity timer is suspended** while a student has an exam in progress. |
| 8 | **The close time is a deadline to start, not to finish.** Start at 11:55 on a 90-minute exam → you finish at 13:25. Start at 12:00 or later → refused. |
| 9 | **Factor is additive**, capped at 100, floored at 0, applied in bulk after approval. |
| 10 | **Soft delete** for questions — removed from the bank, still visible inside exams that already contain it. |
| 11 | **ת"ז is `User.userId`**, and the Israeli check digit is validated. |
| 12 | **Group 1** → `G1_Client.jar`, `G1_Server.jar`. |

### Environment — verified on this machine, not assumed

| Component | Version | How it was checked |
|---|---|---|
| JDK | **26.0.1** | `java -version`, `javac -version` |
| Maven | **3.9.15** | `mvn -v` |
| MySQL | **8.0**, service `MySQL80` running, listening on 3306 | `sc query`, `netstat` |
| OCSF | compiles on JDK 26 — **0 errors**, 4 warnings | `javac -Xlint:all` on the three classes we use |
| Gemini | **HTTP 200**, model alias `gemini-flash-latest` → `gemini-3.6-flash` | one live `generateContent` call |
| GitHub CLI | **2.96.0**, authenticated as `segals`, `repo` scope | `gh auth status` |

Library versions pinned from Maven Central metadata (not guessed):
**JavaFX 26.0.2** · **MySQL Connector/J 9.7.0** · **maven-shade-plugin 3.6.2** · **PDFBox 3.0.8** · **POI 5.5.1**

JavaFX 26 lines up with JDK 26 — the same release train, so it is the lowest-risk choice available.

---

## 1. Project and package structure

Four Maven modules under `C:\GitHub\SE\HSTS`:

```
HSTS/
├── pom.xml                  parent (packaging=pom), pins versions for everything
├── hsts-ocsf/               the reused OCSF framework, unmodified
├── hsts-common/             shared by both jars: entities, enums, protocol
├── hsts-server/  ─────────► G1_Server.jar
└── hsts-client/  ─────────► G1_Client.jar
```

**Why OCSF gets its own module.** The system description §11 explicitly asks for internal
*and* external reuse. Giving the borrowed framework its own module makes that reuse visible
on sight — a grader can see we took an existing framework and did not touch it. It also
guarantees we never accidentally edit it.

### Tier mapping

The three tiers from the submitted class diagram map to packages like this:

| Tier | Package | Contents |
|---|---|---|
| **Presentation** «Boundary» | `hsts.client.gui`, `hsts.client.net` | `GUIScreen` and all screens, `HSTSClient`, `ClientController` |
| **Presentation** «Boundary» *(system edge, runs on the server)* | `hsts.server.boundary` | `IUserManagementSystem`, `IBotAPI` and their implementations |
| **Application** «Control» | `hsts.server.control`, `hsts.server.dao`, `hsts.server.push` | `HSTSServer`, the nine controllers, strategies, factory, `DBController`, DAOs |
| **Data** «Entity» | `hsts.common.entity`, `hsts.common.enums` | all 20 entities and 6 enums |

**One clarification worth defending at the demo.** `IUserManagementSystem` and `IBotAPI` are
drawn in the Presentation tier on the submitted diagram but they run on the *server*. That
is correct and not a contradiction: in UML a **boundary class is anything at the edge of the
system**, including the edge facing another system — not just the edge facing a human. Both
interfaces are boundaries to external systems, so they are Boundary classes that happen to
live in the server process.

### Full package layout

```
hsts-common/  hsts.common
    entity/    User Teacher SubjectCoordinator Student Principal Subject Course
               Question Answer Exam ExamQuestion ExamExecution StudentExam
               StudentAnswer Grade QuestionFeedback ExamStatistics Bot
               KnowledgeSource BotConversation Report
    enums/     UserRole ExamStatus SubmissionStatus DifficultyLevel BotStatus SourceType
    protocol/  Request RequestType Response ResponseType PushEvent PushType
    util/      IsraeliId  IdFormat

hsts-server/  hsts.server
    ServerLauncher            main(); opens the startup window
    HSTSServer                extends AbstractServer, Singleton, one dispatch switch
    gui/       ServerStartupScreen  ServerConsoleScreen
    control/   LoginController QuestionController ExamBuilderController
               ExamApprovalController ExamExecutionController GradingController
               ResultsViewController ReportController BotController
    control/strategy/  ExamBuildStrategy ManualBuildStrategy AutomaticBuildStrategy
                       ReportStrategy TeacherReportStrategy CourseReportStrategy
                       StudentReportStrategy ReportFactory
    dao/       DBController(Singleton) IDAO UserDAO QuestionDAO ExamDAO
               ExecutionDAO GradeDAO BotDAO
    boundary/  IUserManagementSystem LocalUserManagementAdapter
               IBotAPI GeminiBotAPI OfflineBotAPI
    push/      SessionRegistry PushService ExamClockService

hsts-client/  hsts.client
    ClientLauncher            main(); deliberately does NOT extend Application
    HSTSApp                   extends javafx.application.Application
    net/       HSTSClient(extends AbstractClient) ClientController
    gui/       GUIScreen(abstract) ClientStartupScreen LoginScreen MainMenuScreen
               QuestionMgmtScreen ExamBuilderScreen ExamApprovalScreen
               ExamReleaseScreen TakeExamScreen TeacherLiveExamScreen
               GradingScreen StudentResultsScreen ReportScreen BotScreen
               PrincipalDataScreen
                          ^ each of these is an FXML controller (decision A)

hsts-client/src/main/resources/
    fxml/      ClientStartup.fxml Login.fxml MainMenu.fxml QuestionMgmt.fxml
               ExamBuilder.fxml ExamApproval.fxml ExamRelease.fxml TakeExam.fxml
               TeacherLiveExam.fxml Grading.fxml StudentResults.fxml Report.fxml
               Bot.fxml PrincipalData.fxml
    css/       hsts.css
```

### Changes to the submitted class diagram

Every deviation, with its reason. Nothing here is silent.

| Change | Why |
|---|---|
| `ExamStatus` loses `IN_DRAWER` | Decision 1 |
| `Question` and `Exam` gain `version : int` and `isCurrent : boolean` | Decision 2 |
| `Question` gains `topic : String` | Phase 0 gap 3 — automatic building by topic is impossible without it |
| `Question` gains `isDeleted : boolean` | Phase 0 gap 2 + decision 10 |
| `Question.image` changes type from `Image` to `byte[]` | A JavaFX `Image` is not serializable and every entity crosses the network. Converted to an `Image` only inside the screens |
| `releaseFromDrawer()` and `setExamDates()` **move** from `ExamApprovalController` to `ExamExecutionController`; `setExecutionCode()` and `setMaxAttempts()` are added | Decision 3. The controller that owns `ExamExecution` should be the one that creates it. This is a *move*, not a new class — it keeps the diagram's class count unchanged |
| `QuestionController` gains `deleteQuestion()` | Phase 0 gap 2, מתווה 2.4 |
| `BotController` gains `updateKnowledgeSource()` and `removeKnowledgeSource()` | Phase 0 gap 5.12, מתווה 13.2 |
| `GradingController` gains `applyFactor()` | Requirement 77 |
| New screens: `MainMenuScreen`, `PrincipalDataScreen`, `ExamReleaseScreen`, `TeacherLiveExamScreen`, `ClientStartupScreen`, plus server-side `ServerStartupScreen` | מתווה 1 (role menu), מתווה 11 (principal browse), מתווה 5 (release), מתווה 7 (live monitor), מתווה 15 (both startup windows) |
| New server classes `SessionRegistry`, `PushService`, `ExamClockService` | NFR 18 — no manual refresh. See §6 |
| Every entity implements `Serializable` with an explicit `serialVersionUID` | OCSF sends bare `Object` over an object stream |

---

## 2. The database

MySQL 8.0, schema `hsts`, user `hsts`. Access is JDBC-only.

### How versioning works, in plain words

Every question and every exam row carries a `version` number. Editing never overwrites:
it inserts a **new row** with `version + 1`, and flips `is_current` from the old row to the
new one. The old row is untouched forever.

The piece that makes old exams keep working is in `exam_question`: it stores **both** the
question id **and** the question version. So an exam that was built in March points at
*(question 10123, version 1)*. When that question is later edited into version 2, the March
exam still points at version 1 and still shows exactly what the students saw.

The same applies one level up: `exam_execution` stores both the exam id and the exam
version, so a sitting from March keeps its March questions even after the exam is edited.

### Schema (DDL)

```sql
CREATE DATABASE IF NOT EXISTS hsts
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hsts;

-- ---------- externally-managed reference data ----------
CREATE TABLE subject (
  subject_code CHAR(2)     PRIMARY KEY,
  name         VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE course (
  course_code  CHAR(2)      PRIMARY KEY,
  name         VARCHAR(100) NOT NULL,
  subject_code CHAR(2)      NOT NULL,
  CONSTRAINT fk_course_subject FOREIGN KEY (subject_code) REFERENCES subject(subject_code)
) ENGINE=InnoDB;

-- ---------- users (seeded; managed externally per system description §8) ----------
CREATE TABLE users (
  user_id             CHAR(9)      PRIMARY KEY,           -- Israeli ID, check digit validated
  username            VARCHAR(50)  NOT NULL UNIQUE,
  password_hash       CHAR(64)     NOT NULL,              -- SHA-256, hex; never plaintext
  password_salt       CHAR(32)     NOT NULL,              -- per-user random salt
  full_name           VARCHAR(100) NOT NULL,
  role                ENUM('TEACHER','COORDINATOR','STUDENT','PRINCIPAL') NOT NULL,
  coordinated_subject CHAR(2)      NULL,                  -- only for COORDINATOR
  CONSTRAINT fk_users_subject FOREIGN KEY (coordinated_subject) REFERENCES subject(subject_code)
) ENGINE=InnoDB;

CREATE TABLE course_teacher (
  course_code CHAR(2), user_id CHAR(9),
  PRIMARY KEY (course_code, user_id),
  CONSTRAINT fk_ct_course FOREIGN KEY (course_code) REFERENCES course(course_code),
  CONSTRAINT fk_ct_user   FOREIGN KEY (user_id)     REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE course_student (
  course_code CHAR(2), user_id CHAR(9),
  PRIMARY KEY (course_code, user_id),
  CONSTRAINT fk_cs_course FOREIGN KEY (course_code) REFERENCES course(course_code),
  CONSTRAINT fk_cs_user   FOREIGN KEY (user_id)     REFERENCES users(user_id)
) ENGINE=InnoDB;

-- ---------- question bank (versioned) ----------
CREATE TABLE question (
  question_id  CHAR(5)  NOT NULL,       -- 3 digits question no + 2 digits course code
  version      INT      NOT NULL DEFAULT 1,
  course_code  CHAR(2)  NOT NULL,
  text         TEXT     NOT NULL,
  instructions TEXT     NULL,
  topic        VARCHAR(60)  NOT NULL,
  difficulty   ENUM('EASY','MEDIUM','HARD') NOT NULL,
  image        LONGBLOB NULL,           -- binary, not a file path
  is_current   BOOLEAN  NOT NULL DEFAULT TRUE,
  is_deleted   BOOLEAN  NOT NULL DEFAULT FALSE,
  author_id    CHAR(9)  NOT NULL,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (question_id, version),
  KEY ix_question_course_current (course_code, is_current, is_deleted),
  KEY ix_question_topic (course_code, topic, difficulty),
  CONSTRAINT fk_q_course FOREIGN KEY (course_code) REFERENCES course(course_code),
  CONSTRAINT fk_q_author FOREIGN KEY (author_id)   REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE answer (
  question_id      CHAR(5)  NOT NULL,
  question_version INT      NOT NULL,
  answer_no        TINYINT  NOT NULL,
  text             TEXT     NOT NULL,
  is_correct       BOOLEAN  NOT NULL DEFAULT FALSE,
  PRIMARY KEY (question_id, question_version, answer_no),
  CONSTRAINT ck_answer_no CHECK (answer_no BETWEEN 1 AND 4),
  CONSTRAINT fk_a_question FOREIGN KEY (question_id, question_version)
    REFERENCES question(question_id, version) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------- exams (versioned) ----------
CREATE TABLE exam (
  exam_id      CHAR(6)  NOT NULL,       -- 2 exam + 2 course + 2 subject
  version      INT      NOT NULL DEFAULT 1,
  course_code  CHAR(2)  NOT NULL,
  subject_code CHAR(2)  NOT NULL,
  duration_minutes          INT  NOT NULL,
  instructions_for_students TEXT NULL,
  notes_for_teacher         TEXT NULL,  -- never sent to a student
  author_id    CHAR(9)  NOT NULL,
  status       ENUM('PENDING_APPROVAL','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING_APPROVAL',
  rejection_reason TEXT NULL,
  approved_by  CHAR(9)  NULL,
  approved_at  DATETIME NULL,
  is_current   BOOLEAN  NOT NULL DEFAULT TRUE,
  created_at   DATETIME NOT NULL,
  PRIMARY KEY (exam_id, version),
  CONSTRAINT ck_exam_duration CHECK (duration_minutes > 0),
  CONSTRAINT fk_e_course FOREIGN KEY (course_code) REFERENCES course(course_code),
  CONSTRAINT fk_e_author FOREIGN KEY (author_id)   REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE exam_question (
  exam_id          CHAR(6) NOT NULL,
  exam_version     INT     NOT NULL,
  question_id      CHAR(5) NOT NULL,
  question_version INT     NOT NULL,     -- pins the exact version used
  points           INT     NOT NULL,
  q_order          INT     NOT NULL,
  PRIMARY KEY (exam_id, exam_version, q_order),
  UNIQUE KEY uq_exam_question (exam_id, exam_version, question_id),
  CONSTRAINT ck_points CHECK (points > 0),
  CONSTRAINT fk_eq_exam     FOREIGN KEY (exam_id, exam_version)
    REFERENCES exam(exam_id, version) ON DELETE CASCADE,
  CONSTRAINT fk_eq_question FOREIGN KEY (question_id, question_version)
    REFERENCES question(question_id, version)
) ENGINE=InnoDB;

-- ---------- taking the exam out of the drawer ----------
CREATE TABLE exam_execution (
  execution_id       INT AUTO_INCREMENT PRIMARY KEY,
  exam_id            CHAR(6)  NOT NULL,
  exam_version       INT      NOT NULL,
  execution_code     CHAR(4)  NOT NULL UNIQUE,   -- stored upper-case
  open_time          DATETIME NOT NULL,
  close_time         DATETIME NOT NULL,          -- deadline to START (decision 8)
  allocated_duration INT      NOT NULL,          -- may be changed live by the teacher
  original_duration  INT      NOT NULL,          -- what it was before any live change
  max_attempts       INT      NOT NULL DEFAULT 1,
  released_by        CHAR(9)  NOT NULL,          -- the teacher: owns grading (decision 5)
  created_at         DATETIME NOT NULL,
  CONSTRAINT ck_window CHECK (close_time > open_time),
  CONSTRAINT fk_ex_exam     FOREIGN KEY (exam_id, exam_version) REFERENCES exam(exam_id, version),
  CONSTRAINT fk_ex_teacher  FOREIGN KEY (released_by) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE student_exam (
  submission_id   INT AUTO_INCREMENT PRIMARY KEY,
  execution_id    INT      NOT NULL,
  student_id      CHAR(9)  NOT NULL,
  attempt_no      INT      NOT NULL DEFAULT 1,
  start_time      DATETIME NOT NULL,
  deadline        DATETIME NOT NULL,   -- start_time + allocated_duration (decision 8)
  end_time        DATETIME NULL,
  actual_duration INT      NULL,       -- whole minutes
  status          ENUM('IN_PROGRESS','FINISHED','TIMED_OUT') NOT NULL DEFAULT 'IN_PROGRESS',
  UNIQUE KEY uq_attempt (execution_id, student_id, attempt_no),
  CONSTRAINT fk_se_exec    FOREIGN KEY (execution_id) REFERENCES exam_execution(execution_id),
  CONSTRAINT fk_se_student FOREIGN KEY (student_id)   REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE student_answer (
  submission_id      INT     NOT NULL,
  question_id        CHAR(5) NOT NULL,
  question_version   INT     NOT NULL,
  selected_answer_no TINYINT NULL,     -- NULL = left blank
  PRIMARY KEY (submission_id, question_id, question_version),
  CONSTRAINT fk_sa_sub FOREIGN KEY (submission_id) REFERENCES student_exam(submission_id)
    ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------- grading ----------
CREATE TABLE grade (
  submission_id             INT PRIMARY KEY,
  auto_grade                INT     NOT NULL,
  final_grade               INT     NOT NULL,
  factor                    INT     NOT NULL DEFAULT 0,
  is_approved               BOOLEAN NOT NULL DEFAULT FALSE,
  approved_at               DATETIME NULL,
  manual_change_explanation TEXT    NULL,   -- mandatory whenever final <> auto
  teacher_general_comment   TEXT    NULL,
  graded_by                 CHAR(9) NULL,
  CONSTRAINT ck_grade_range CHECK (final_grade BETWEEN 0 AND 100),
  CONSTRAINT fk_g_sub FOREIGN KEY (submission_id) REFERENCES student_exam(submission_id)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE question_feedback (
  submission_id    INT     NOT NULL,
  question_id      CHAR(5) NOT NULL,
  question_version INT     NOT NULL,
  comment          TEXT    NULL,
  is_wrong         BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (submission_id, question_id, question_version),
  CONSTRAINT fk_qf_sub FOREIGN KEY (submission_id) REFERENCES student_exam(submission_id)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE exam_statistics (
  execution_id INT PRIMARY KEY,
  average      DOUBLE NOT NULL,
  median       DOUBLE NOT NULL,
  d1 INT NOT NULL, d2 INT NOT NULL, d3 INT NOT NULL, d4  INT NOT NULL, d5  INT NOT NULL,
  d6 INT NOT NULL, d7 INT NOT NULL, d8 INT NOT NULL, d9  INT NOT NULL, d10 INT NOT NULL,
  computed_at  DATETIME NOT NULL,
  CONSTRAINT fk_st_exec FOREIGN KEY (execution_id) REFERENCES exam_execution(execution_id)
    ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------- study bot ----------
CREATE TABLE bot (
  bot_id      INT AUTO_INCREMENT PRIMARY KEY,
  course_code CHAR(2)      NOT NULL UNIQUE,     -- Course 1 -- 0..1 Bot
  name        VARCHAR(100) NOT NULL,
  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'INACTIVE',
  created_by  CHAR(9)      NOT NULL,
  created_at  DATETIME     NOT NULL,
  CONSTRAINT fk_bot_course FOREIGN KEY (course_code) REFERENCES course(course_code)
) ENGINE=InnoDB;

CREATE TABLE knowledge_source (
  source_id INT AUTO_INCREMENT PRIMARY KEY,
  bot_id    INT NOT NULL,
  type      ENUM('QUESTION_BANK','PDF','WORD','FREE_TEXT') NOT NULL,
  title     VARCHAR(200) NOT NULL,
  content   MEDIUMTEXT   NOT NULL,   -- text already extracted in Java
  added_by  CHAR(9)  NOT NULL,
  added_at  DATETIME NOT NULL,
  CONSTRAINT fk_ks_bot FOREIGN KEY (bot_id) REFERENCES bot(bot_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE bot_conversation (
  conv_id       INT AUTO_INCREMENT PRIMARY KEY,
  bot_id        INT      NOT NULL,
  student_id    CHAR(9)  NOT NULL,
  question_text TEXT     NOT NULL,
  answer_text   MEDIUMTEXT NOT NULL,
  asked_at      DATETIME NOT NULL,
  CONSTRAINT fk_bc_bot     FOREIGN KEY (bot_id)     REFERENCES bot(bot_id),
  CONSTRAINT fk_bc_student FOREIGN KEY (student_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- ---------- 3-strikes lockout (requirement 39) ----------
CREATE TABLE code_attempt (
  student_id   CHAR(9) PRIMARY KEY,
  fail_count   INT      NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  CONSTRAINT fk_ca_student FOREIGN KEY (student_id) REFERENCES users(user_id)
) ENGINE=InnoDB;
```

### Five schema decisions worth explaining

**Execution counts are computed, never stored.** Requirement 48 wants "how many started,
finished themselves, ran out of time". The class diagram has these as fields on
`ExamExecution`. I derive them with a `COUNT` over `student_exam` instead of storing them.
Reason: stored copies drift. A stored counter that someone forgets to increment is a bug you
find at the demo; a `COUNT` is right by construction. The `ExamExecution` object sent to the
client still carries the three numbers, so the class diagram is honoured.

**`deadline` is stored per student, not computed on the fly.** This is what makes decision 8
work. When a student enters her ID, the server writes `deadline = now + allocated_duration`.
The window's `close_time` is checked only at *start*. A live time extension updates the
`deadline` of every in-progress student in that execution.

**Execution codes are globally unique.** A student types only a 4-character code with no
other context, so it must identify one execution unambiguously. Scoping uniqueness to
"currently open" executions is fiddly in SQL and easy to get wrong. 36⁴ = 1,679,616
combinations is far more than this school will ever need, so a plain `UNIQUE` is both
simpler and safer. Codes are stored upper-case and compared case-insensitively, so a student
typing lower-case still gets in.

**Who is locked out after 3 wrong codes.** Requirement 39 says "the exam is blocked for 10
minutes", but on a wrong code we do not know which exam she meant. The only implementable
reading is to lock *the student* out of code entry for 10 minutes, which is what
`code_attempt` does.

**Single-login is tracked in memory, not in a table.** A `logged_in` column would survive a
crash and lock a user out permanently. The `SessionRegistry` map lives in the server process
and is cleared by OCSF's `clientDisconnected` hook — and if the server ever restarts,
everyone is cleanly logged out, which is the correct state anyway.

### Seed data

Quantities are exactly as specified in the project brief: 4 subjects, 8 courses (2 per
subject, some with two teachers), 1 principal, 4 coordinators, 8 teachers, ~40 students
enrolled in 3–5 courses each, ~200 questions across 4–6 topics and 3 difficulties with one
course deliberately short on hard questions, ~10 questions with 2–3 versions, ~12 exams
covering every status including one edited exam, executions in every state, ~150 submissions
with a realistic spread, 2–3 bots with ~30 stored conversations.

**How it will be produced.** Not as hand-written SQL. A small Java class,
`hsts.server.seed.SeedRunner`, generates and inserts it, for two reasons that hand-written
SQL cannot satisfy:

1. **Israeli ID check digits.** Every one of the ~50 user IDs has to pass the check-digit
   test now that decision 11 validates it. `123456789` fails; `123456782` passes. Generating
   them guarantees they are all valid.
2. **The demo needs an exam that is open *right now*.** Hard-coded dates go stale the moment
   the demo slips by a day. The seeder computes every date relative to the moment it runs —
   one execution opened 20 minutes ago and closing in 3 hours, one starting next week, one
   that closed yesterday. Re-running `SeedRunner` before the demo makes the data fresh again.

The check-digit rule, for the record: multiply the digits alternately by 1 and 2; if a
product exceeds 9, subtract 9; the total must divide by 10.

---

## 3. The client–server protocol

OCSF sends a bare `Object`. Everything is wrapped in one envelope so that
`handleMessageFromClient` is a single readable `switch`.

```java
class Request  implements Serializable { RequestType  type; Object payload; String requestId; }
class Response implements Serializable { ResponseType type; Object payload; String message; String requestId; }
class PushEvent implements Serializable { PushType   type; Object payload; }   // server → client, unsolicited
```

`ResponseType` is just `OK` or `ERROR`; `message` carries the text the user sees, which is
how every "error message" in the acceptance tests gets delivered. `requestId` lets the client
match a reply to the screen that asked for it.

**Why one envelope rather than a separate class per message.** Two reasons. It keeps the
server's dispatch to one switch statement I can read out loud at the demo, and adding a
feature means adding one enum value rather than a new class plus a new handler — which is
the flexibility NFR 19 and requirement 64 ask for.

### Request types, by use case

| Use case | Request types |
|---|---|
| SUC-1 | `LOGIN`, `LOGOUT`, `HEARTBEAT` |
| SUC-2 | `QUESTION_LIST_BY_COURSE`, `QUESTION_GET`, `QUESTION_ADD`, `QUESTION_EDIT`, `QUESTION_DELETE`, `QUESTION_TOPICS_BY_COURSE`, `QUESTION_VERSIONS` |
| SUC-3 / 4 | `EXAM_BUILD_MANUAL`, `EXAM_BUILD_AUTOMATIC`, `EXAM_SAVE`, `EXAM_EDIT`, `EXAM_LIST_BY_TEACHER`, `EXAM_GET` |
| SUC-5 | `EXAM_PENDING_FOR_COORDINATOR`, `EXAM_APPROVE`, `EXAM_REJECT` |
| SUC-6 / 8 | `EXAM_APPROVED_FOR_RELEASE`, `EXECUTION_RELEASE`, `EXECUTION_LIST_BY_TEACHER`, `EXECUTION_CHANGE_TIME`, `EXECUTION_GRANT_ATTEMPT`, `EXECUTION_LIVE_STATUS` |
| SUC-7 | `EXEC_VALIDATE_CODE`, `EXEC_START_SESSION`, `EXEC_SAVE_ANSWER`, `EXEC_SUBMIT` |
| SUC-9 | `GRADING_PENDING_LIST`, `GRADING_GET_SUBMISSION`, `GRADING_CHANGE_GRADE`, `GRADING_ADD_COMMENT`, `GRADING_APPROVE`, `GRADING_APPROVE_ALL`, `GRADING_APPLY_FACTOR`, `GRADING_STATISTICS` |
| SUC-10 | `RESULTS_MY_LIST`, `RESULTS_MY_CHECKED_EXAM`, `RESULTS_BY_EXECUTION`, `RESULTS_HISTOGRAM` |
| SUC-11 / 12 | `REPORT_GENERATE`, `PRINCIPAL_BROWSE_QUESTIONS`, `PRINCIPAL_BROWSE_EXAMS`, `PRINCIPAL_BROWSE_RESULTS` |
| SUC-13 | `BOT_GET_FOR_COURSE`, `BOT_CREATE`, `BOT_SET_ACTIVE`, `BOT_SOURCE_ADD`, `BOT_SOURCE_UPDATE`, `BOT_SOURCE_REMOVE`, `BOT_SOURCE_LIST` |
| SUC-14 / 15 | `BOT_ASK`, `BOT_MY_HISTORY`, `BOT_USAGE_STATS` |

### Push event types (server → client, no request)

| Push type | Sent when | Which מתווה / requirement it satisfies |
|---|---|---|
| `EXAM_TIME_CHANGED` | teacher extends time mid-exam | מתווה 7, acceptance test 2.7 |
| `EXAM_TIME_WARNING` | 90% of a student's time has elapsed | requirement 43 |
| `EXAM_AUTO_SUBMITTED` | a student's deadline passes | requirement 45, test 2.6 |
| `EXAM_LIVE_STATUS` | a student starts or submits — refreshes the teacher's monitor | NFR 18 |
| `EXAM_APPROVED` / `EXAM_REJECTED` | coordinator decides | requirement 33 ("sent to the teacher") |
| `GRADE_APPROVED` | teacher publishes a grade | NFR 18 |
| `FORCE_LOGOUT` | inactivity timeout, or the same user logs in elsewhere | requirements 4, 76 |

---

## 4. The 15 use cases mapped to classes and methods

| SUC | Screen | Controller · key methods | DAO |
|---|---|---|---|
| **1** Login | `LoginScreen` → `MainMenuScreen` | `LoginController.authenticate(user,pwd)` · `isAlreadyConnected()` · `logout()` · `startInactivityTimer()` — delegates credential checking to `IUserManagementSystem` | `UserDAO` |
| **2** Question bank | `QuestionMgmtScreen` | `QuestionController.addQuestion()` · `editQuestion()` *(new version)* · `deleteQuestion()` *(soft)* · `getQuestionsByCourse()` · `getTopics()` · `generateQuestionId()` | `QuestionDAO` |
| **3** Build manually | `ExamBuilderScreen` | `ExamBuilderController.setStrategy(Manual)` · `buildExam()` · `saveExam()` · `generateExamId()` · `validateTotalPoints()` | `ExamDAO`, `QuestionDAO` |
| **4** Build automatically | `ExamBuilderScreen` | same, `setStrategy(Automatic)`; `AutomaticBuildStrategy.selectQuestions()` returns empty → "not enough questions", no exam created | `QuestionDAO` |
| **5** Approve / reject | `ExamApprovalScreen` | `ExamApprovalController.getPendingForCoordinator()` · `approveExam()` · `rejectExam(reason)` → pushes `EXAM_REJECTED` | `ExamDAO` |
| **6** Release from drawer | `ExamReleaseScreen` | `ExamExecutionController.releaseFromDrawer()` · `setExamDates()` · `setExecutionCode()` · `setMaxAttempts()` — approved exams only | `ExecutionDAO` |
| **7** Take exam | `TakeExamScreen` | `ExamExecutionController.validateCode()` · `startSession()` · `saveAnswer()` · `submitExam()` · `autoSubmit()`; timing owned by `ExamClockService` | `ExecutionDAO` |
| **8** Manage live | `TeacherLiveExamScreen` | `ExamExecutionController.modifyAllocatedTime()` → pushes `EXAM_TIME_CHANGED` · `openExtraAttempt()` · `getLiveStatus()` | `ExecutionDAO` |
| **9** Mark and grade | `GradingScreen` | `GradingController.autoGrade()` · `changeGradeManually(value,reason)` · `addComment()` · `approveGrade()` · `approveAll()` · `applyFactor()` · `computeStatistics()` | `GradeDAO` |
| **10** View grades | `StudentResultsScreen`, `ReportScreen` | `ResultsViewController.getMyResults()` · `getCheckedExam()` · `getResultsByExecution()` · `getHistogram()` | `GradeDAO` |
| **11** Teacher reports | `ReportScreen` | `ReportController.generateReport(TEACHER, params)` via `ReportFactory` → `TeacherReportStrategy` | `GradeDAO` |
| **12** Principal | `PrincipalDataScreen`, `ReportScreen` | read-only browse + `ReportController` with `Course` / `Student` / `TeacherReportStrategy` | all DAOs, read-only |
| **13** Create bot | `BotScreen` | `BotController.createBot()` · `addKnowledgeSource()` · `updateKnowledgeSource()` · `removeKnowledgeSource()` · `setActive()` | `BotDAO` |
| **14** Use bot | `BotScreen` | `BotController.askBot()` — refuses if not enrolled, bot inactive, **or the student has an `IN_PROGRESS` session in that course** | `BotDAO` |
| **15** Bot history | `BotScreen` | `BotController.getPersonalHistory()` · `getUsageStats()` *(no identities)* | `BotDAO` |

### Where the five patterns actually live

| Pattern | Class | What it buys us |
|---|---|---|
| **Singleton** | `HSTSServer`, `DBController` | One server, one connection manager. `getInstance()`, private constructor. |
| **Strategy** | `ExamBuildStrategy` → `Manual` / `Automatic` | `ExamBuilderController` never asks "which mode?" — it holds a strategy and calls `selectQuestions()`. Adding a third build mode touches no existing class. |
| **Strategy** | `ReportStrategy` → `Teacher` / `Course` / `Student` | The three comparisons מתווה 12 demands. A fourth report = one new class. This *is* requirement 64. |
| **Factory** | `ReportFactory.createStrategy(type)` | Turns a report type into the right strategy, so `ReportController` has no `if`-chain. |
| **DAO** | `IDAO` + `UserDAO`, `QuestionDAO`, `ExamDAO`, `ExecutionDAO`, `GradeDAO`, `BotDAO` | All SQL in one layer; controllers never see SQL. This is what makes the version-2 web move cheap (requirement 8). |
| **Observer** | `PushService` → `ClientController` → screens | Screens register as listeners; the server pushes; nothing polls. This is how NFR 18 is met. |

---

## 5. Server-to-client push — how it works

The requirement that forces this: **NFR 18 forbids manual screen refresh**, and acceptance
test 2.7 requires a student's timer to jump *by itself* when the teacher grants more time.

Three small classes:

**`SessionRegistry`** — a `Map<String userId, ConnectionToClient>` plus the reverse. Filled
on successful login, cleared by OCSF's `clientDisconnected` hook. This is also what enforces
requirement 4 (no double login) and lets us `FORCE_LOGOUT` a user.

**`PushService`** — `toUser(userId, event)`, `toUsers(collection, event)`,
`toStudentsInExecution(executionId, event)`. Each looks up the connection and calls
`ConnectionToClient.sendToClient(...)`.

**`ExamClockService`** — one `ScheduledExecutorService` on the server, ticking once a second
over every `IN_PROGRESS` session. Per tick it: pushes remaining seconds to that student;
fires `EXAM_TIME_WARNING` once when 90% has elapsed; and when `now >= deadline`, auto-submits
and pushes `EXAM_AUTO_SUBMITTED`.

**The server owns the clock — the client never does.** Acceptance test 2.11 already
demands this ("the timer continues from the right point, synced from the server"). The client
displays what it is told and interpolates between ticks for smoothness, but every decision —
90% warning, auto-submit, actual duration — is made on the server against `deadline` in the
database. This also removes any worry about the two laptops' clocks disagreeing.

**One JavaFX rule this creates.** Push events arrive on an OCSF network thread, and JavaFX
forbids touching the UI from any thread but its own. Every screen update triggered by a push
must go through `Platform.runLater(...)`. Getting this wrong produces intermittent crashes
that are painful to debug, so it goes in from the first push, not later.

---

## 6. Build, packaging and running on two laptops

### Maven

Parent `pom.xml` pins: Java release **26**, JavaFX **26.0.2**, MySQL Connector/J **9.7.0**,
shade **3.6.2**, PDFBox **3.0.8**, POI **5.5.1**. Modules inherit; no module repeats a version.

### The JavaFX fat-jar trap, and how we avoid it

This is the single most likely thing to go wrong, so it is designed for up front.

If the class named in the jar's manifest **extends `Application`**, the JVM refuses to start
it from a plain fat jar and prints *"JavaFX runtime components are missing"*. The fix is
standard and small: the manifest points at **`ClientLauncher`**, which does *not* extend
`Application` and whose whole body is

```java
public static void main(String[] args) { Application.launch(HSTSApp.class, args); }
```

Two more things the client build needs:

- **Windows-native JavaFX artifacts.** JavaFX ships its native libraries per platform, so
  each dependency is declared with `<classifier>win</classifier>`. Both laptops are Windows,
  so this is correct — and it does mean the client jar is Windows-only. That is a stated
  limitation, not an accident.
- **Signature files stripped.** Shading merges signed jars, and leftover `META-INF/*.SF`,
  `*.DSA`, `*.RSA` entries make the JVM reject the jar. A shade `<filter>` removes them.

Shade also needs `ServicesResourceTransformer` (so the JDBC driver is still discovered) and
`ManifestResourceTransformer` (to set `Main-Class`). Output names are set with `<finalName>`
to **`G1_Client`** and **`G1_Server`**, which is exactly what the Assignment 3 instructions
require.

### Running it

**Server laptop.** `java -jar G1_Server.jar` opens a small window asking for the listening
port (default 5555) and the MySQL password, reading `%USERPROFILE%\.hsts\config.properties`
first and offering to save what is missing. It then connects to MySQL, starts OCSF listening,
and shows a console screen with connected clients.

**Client laptop.** `java -jar G1_Client.jar` opens a window asking for the server IP and
port, then connects and shows the login screen. Both startup windows together satisfy
מתווה item 15, "GUI לאתחול הקשר".

**Windows Firewall.** On the *server* laptop only, run once as Administrator:

```bash
netsh advfirewall firewall add rule name="HSTS Server" dir=in action=allow protocol=TCP localport=5555
```

MySQL deliberately gets **no** firewall rule — it stays bound to localhost on the server
laptop, and only the server process talks to it. The client never touches the database
directly. That is both the correct three-tier design and one less thing to open on the network.

---

## 7. Milestones

**Milestone 1 is a walking skeleton and nothing else.** It contains no HSTS features
whatsoever. Its only job is to prove that JavaFX 26 + OCSF + MySQL + fat-jar packaging all
work together on JDK 26, across two laptops, *before* a single feature is written. JavaFX on
JDK 26 is the one unproven piece and there is no course template to copy from.

| # | Milestone | Proves / delivers | Sources |
|---|---|---|---|
| **1** | **Walking skeleton** | Four Maven modules build; both fat jars produced with the right names; server startup window → MySQL connect → OCSF listen; client startup window → connect → send `PING` → server reads one row → client displays it. The client startup window is a **real FXML screen loaded from inside the built jar**, and the login check runs a **real salted SHA-256 comparison** against one seeded row — so decisions A and B are proven here, not at milestone 9. **Run on both laptops over the LAN.** | NFR 15 |
| 2 | Login, roles, menus | `users` seeded, login, single-session, per-role menu, auto-logout groundwork | SUC-1, מתווה 1 |
| 3 | Question bank | add / edit *(new version)* / soft delete / browse, topic combo, image upload, 5-digit IDs | SUC-2, מתווה 2 |
| 4 | Exam building | manual + automatic, 100-point rule, "not enough questions", exam versioning, 6-digit IDs | SUC-3/4, מתווה 3 |
| 5 | Approval | coordinator approves / rejects with reason, pushed to the teacher | SUC-5, מתווה 4 |
| 6 | Release from drawer | teacher sets open/close/code/attempts on approved exams only | SUC-6, מתווה 5 |
| 7 | Taking the exam | code, ת"ז, server-driven timer, save answers, submit, auto-submit at deadline | SUC-7, מתווה 6 |
| 8 | Live management | teacher extends time mid-exam → student timers jump by themselves; live monitor | SUC-8, מתווה 7 |
| 9 | Grading | auto-grade, manual change + mandatory reason, comments, approve, bulk approve, statistics | SUC-9, מתווה 8 |
| 10 | Student results | grade list, checked paper, wrong answers marked, teacher comments | SUC-10, מתווה 9 |
| 11 | Teacher results + histogram | results table **and histogram** | מתווה 10 |
| 12 | Principal browse | read-only questions / exams / results | SUC-12, מתווה 11 |
| 13 | Reports | average, median, deciles; the three comparisons via Factory + Strategy | SUC-11/12, מתווה 12 |
| 14 | Study bot | create, sources (PDF / Word / free text / question bank), activate, ask, history, anonymous stats, blocked during an exam | SUC-13/14/15, מתווה 13/14 |
| 15 | **The six derived requirements** | 3 wrong codes → 10-min lock (39); 90% popup (43); multiple attempts (61); factor (77); inactivity auto-logout (76); coordinator edits subject questions (19) | as instructed, after the core |
| 16 | Full seed data, acceptance testing, submission | run the assignment-1 test plan, fill the results table, rewrite tests 2.11 and 4.6, Word document, ZIP, verify both jars on two machines | Assignment 3 |

Milestones 2–14 each end with: what was built, what works, what does not, what is next.

---

## 8. Risks, honestly

| Risk | Likelihood | Why it matters | Mitigation |
|---|---|---|---|
| **JavaFX 26 fat jar will not launch** | Medium | Nothing ships if the client jar does not run | This is the *entire point* of milestone 1. Launcher-class trick and `win` classifiers are designed in from the start |
| **FXML does not load from inside the fat jar** | Medium | Every one of the ~14 screens depends on it, so discovering this late would mean rewriting all of them | Decision A. Milestone 1 loads a real FXML screen from the built jar before any screen is written |
| **Git mangles the CR-only OCSF files** | Medium-high | The classes silently vanish into one comment — and this has *already happened once* to `EchoServer.java` in this repo | Convert those files to normal line endings once, add `.gitattributes` marking them, and verify by cloning into a scratch folder before relying on it |
| **Serialization mismatch between the jars** | Medium | Any entity change rebuilt into one jar but not the other throws `InvalidClassException` at the demo | Entities live in one shared module; explicit `serialVersionUID`; **always rebuild and copy both jars together** |
| **No internet in the demo room** | Medium | The bot is a whole מתווה scenario | `OfflineBotAPI` behind `IBotAPI`, selectable at server startup, answering from the stored knowledge sources |
| **Seeded demo data goes stale** | High if ignored | מתווה needs an exam that is open *at that moment* | The seeder computes all dates relative to run time; re-run it before the demo |
| **Two-laptop networking on the day** | Medium | Firewall, wrong IP, different subnets | Milestone 1 forces this to be solved first; the exact steps get written down |
| **Gemini free-tier limits or cost run-away** | Low | A test loop could burn the quota | Hard request cap in `GeminiBotAPI`, thinking budget turned down, one call per student question |
| **Scope** | High | 15 use cases, 78 requirements, ~30 screens' worth of behaviour | Strict milestone order, מתווה-critical first, derived requirements last |

---

## 9. Documents that this plan makes out of date

Rule: nothing drifts silently. These are the edits the submitted Assignment 1 and 2 documents
will need. I will produce the exact redline text as a separate deliverable in Phase 3.

**Assignment 1 — requirements table**
- **27** reword: an exam is saved as `PENDING_APPROVAL`; "in the drawer" means it has no open execution (decision 1)
- **15** add the **topic** field (gap 3)
- **37** re-trace from SUC-8 to SUC-6 — the code is set at release time (decision 3)
- **76** add "except while an exam is in progress" (decision 7)
- **77** define factor as additive, capped 0–100 (decision 9)
- **new**: question and exam versioning (מתווה 2.2, 3.5)
- **new**: question deletion (מתווה 2.4)
- **new**: total exam points must equal exactly 100 (מתווה 3 note 3)
- **new**: histogram display of results (מתווה 10)
- **new**: start-deadline rule (decision 8)
- **new**: ת"ז validated by the Israeli check digit (decision 11)
- **new NFR**: the server pushes updates; no manual refresh (NFR 18)
- **67** widen from "add sources" to "edit sources" (מתווה 13.2/13.3)

**Assignment 1 — use case table and textual specs**
- **SUC-1** — authentication is against our own user table via a local adapter (decision 6)
- **SUC-3** post-condition — status is `PENDING_APPROVAL`, not "in drawer" (decision 1)
- **SUC-6** — gains the execution code and attempt count (decision 3)
- **SUC-8** — loses code definition, keeps live time change and extra attempts (decision 3)
- **SUC-9** — the teacher who *released* the execution grades it (decision 5)
- **SUC-10** — teacher sees the union of authored and released (decision 4)

**Assignment 1 — acceptance tests**
- **2.11** rewrite: "browser refreshed" → client reconnects to the server and the timer resyncs
- **4.6** rewrite: "change a URL parameter" → request another student's submission id directly and be refused
- **1.1** needs no change — it already says "ממתינה לאישור", which decision 1 makes correct

**Assignment 2 — class diagram and class table**
- `ExamStatus` drops `IN_DRAWER`
- `Question` gains `version`, `isCurrent`, `topic`, `isDeleted`; `image` becomes `byte[]`
- `Exam` gains `version`, `isCurrent`
- `releaseFromDrawer` / `setExamDates` move to `ExamExecutionController`, plus
  `setExecutionCode` and `setMaxAttempts`
- `QuestionController.deleteQuestion`, `BotController.update/removeKnowledgeSource`,
  `GradingController.applyFactor`
- new screens: `MainMenuScreen`, `PrincipalDataScreen`, `ExamReleaseScreen`,
  `TeacherLiveExamScreen`, `ClientStartupScreen`, `ServerStartupScreen`
- new server classes: `SessionRegistry`, `PushService`, `ExamClockService`
- all entities marked `Serializable`

**Assignment 2 — activity and sequence diagrams**
- A1 and the SUC-3 sequence: status is `PENDING_APPROVAL`, not `IN_DRAWER`
- SUC-3 sequence: `setPointsPerQuestion(points)` becomes per-`ExamQuestion` points
- SUC-7 sequence: timing and auto-submit are owned by the server's `ExamClockService`

---

## 10. Three choices — decided 2026-07-29

**A. Screens are written in FXML.** Layout lives in `.fxml` files under
`src/main/resources/fxml/`, one per screen, each with a controller class in
`hsts.client.gui`. This is the industry-standard JavaFX structure and separates layout from
behaviour cleanly.

*What this adds, and how it is handled.* FXML is loaded by resource path at runtime
(`getClass().getResource("/fxml/Login.fxml")`), so it is one more thing that must survive
fat-jar packaging. It also instantiates controllers by reflection. Both work fine on the
classpath, which is where the launcher trick already puts us — but neither is proven until
tested. **Milestone 1 therefore loads a real FXML screen from inside the built jar**, so this
is settled before any screen is written rather than discovered at milestone 9.

**B. Passwords are hashed — SHA-256 with a per-user salt.** The `users` table stores
`password_hash` and `password_salt`; no plaintext is ever stored. `LocalUserManagementAdapter`
hashes the supplied password with the stored salt and compares.

*The demo problem this creates, and the fix.* Once hashed, you cannot read a password out of
the database when a login fails on the day. So the seeded test accounts follow a **documented
convention** written in `docs/test_accounts.md`: username is the role plus a number
(`teacher1`, `student14`, `coordinator2`, `principal`), and the password is the username with
`!` and the role initial appended. The convention is safe to publish because every one of
these users is fictional test data — but it means you always know how to log in.

**C. The public GitHub repo is created now, at the start of milestone 1.** `gh` 2.96.0 is
authenticated as `segals` with `repo` scope. Creating it first means `.gitattributes`
protecting the CR-only OCSF files is in place *before* those files are ever committed —
which matters, because that corruption has already happened once to `EchoServer.java` in this
very folder. `.gitignore` will exclude local config, build output, and anything holding a
secret. **Nothing is pushed until the first commit is reviewed.**

---

## 11. What happens on approval

I build milestone 1 and nothing else, then report back with what works and what does not
before touching milestone 2.
