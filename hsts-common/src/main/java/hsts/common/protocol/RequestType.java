package hsts.common.protocol;

/**
 * Every kind of message a client can send to the server.
 *
 * <p>The whole client-server conversation goes through one envelope class
 * ({@link Request}) carrying one of these values. That keeps the server's
 * dispatch to a single readable switch statement, and means adding a feature
 * later costs one new enum value rather than a new class and a new handler.</p>
 *
 * <p>Values are added milestone by milestone, as the feature that needs them is
 * built. An unused value would be a promise the server does not keep.</p>
 */
public enum RequestType {

    // ---- infrastructure ----

    /** Health probe: proves client to server to database and back. */
    PING,

    // ---- SUC-1: login (milestone 2) ----

    /** Username and password. Payload is a {@link Credentials}. */
    LOGIN,

    /** Ends the session held by this connection. */
    LOGOUT,

    /**
     * How many things are waiting for the signed-in user, for the menu badges.
     * No payload; the reply is a {@link PendingCounts}.
     *
     * <p>Asked when the menu opens and again whenever a push says something that
     * could change a count has happened. Never on a timer - a badge that costs a
     * request a second would be a worse problem than the one it solves.</p>
     */
    PENDING_COUNTS,

    // ---- SUC-2: question bank (milestone 3) ----

    /** The courses the signed-in teacher teaches. No payload. */
    COURSE_LIST_MINE,

    /** The live question bank for one course. Payload is the course code. */
    QUESTION_LIST_BY_COURSE,

    /** One full question including its picture. Payload is a {@link QuestionRef}. */
    QUESTION_GET,

    /** Every stored version of one question. Payload is a {@link QuestionRef}. */
    QUESTION_VERSIONS,

    /** Topics already used in a course, for the combo box. Payload is the course code. */
    QUESTION_TOPICS,

    /** Adds a new question. Payload is a {@code Question}. */
    QUESTION_ADD,

    /** Saves an edit as a new version. Payload is a {@code Question}. */
    QUESTION_EDIT,

    /** Removes a question from the bank (soft delete). Payload is the question id. */
    QUESTION_DELETE,

    // ---- SUC-3 / SUC-4: building exams (milestone 4) ----

    /**
     * Chooses questions and returns an unsaved draft exam.
     * Payload is an {@link ExamBuildCriteria}. Nothing is written to the database -
     * requirement 29 needs the "not enough questions" refusal to happen before
     * anything exists.
     */
    EXAM_BUILD_DRAFT,

    /** Saves a new exam. Payload is an {@code Exam}. */
    EXAM_SAVE,

    /** Saves an edit as a new version. Payload is an {@code Exam}. */
    EXAM_EDIT,

    /** Current versions of every exam the signed-in teacher wrote. No payload. */
    EXAM_LIST_MINE,

    /** One exam with its questions. Payload is an {@link ExamRef}. */
    EXAM_GET,

    /** Every stored version of one exam. Payload is an {@link ExamRef}. */
    EXAM_VERSIONS,

    // ---- SUC-5: approving exams (milestone 5) ----

    /** Exams awaiting a decision in the coordinator's own subject. No payload. */
    EXAM_PENDING_FOR_COORDINATOR,

    /** Approves one exam version. Payload is an {@link ExamDecision}. */
    EXAM_APPROVE,

    /** Rejects one exam version with a mandatory reason. Payload is an {@link ExamDecision}. */
    EXAM_REJECT,

    // ---- SUC-6: releasing an exam from the drawer (milestone 6) ----

    /** Approved exam versions this teacher may release. No payload. */
    EXECUTION_RELEASABLE_EXAMS,

    /** A free 4-character code the teacher can use. No payload. */
    EXECUTION_SUGGEST_CODE,

    /** Takes an approved exam out of the drawer. Payload is an {@link ExamReleaseRequest}. */
    EXECUTION_RELEASE,

    /** Everything this teacher has released. No payload. */
    EXECUTION_LIST_MINE,

    // ---- SUC-7: sitting an exam (milestone 7) ----

    /** Checks the code the teacher read out. Payload is the typed code. */
    TAKE_VALIDATE_CODE,

    /** Identifies the student and starts her clock. Payload is a {@link StartExamRequest}. */
    TAKE_START,

    /** Records one chosen answer. Payload is an {@link AnswerChoice}. */
    TAKE_SAVE_ANSWER,

    /** Hands the exam in. Payload is the submission id. */
    TAKE_SUBMIT,

    /** Reloads an attempt still in progress. Payload is the submission id. */
    TAKE_RESUME,

    // ---- SUC-8: managing a sitting while it runs (milestone 8) ----

    /** The teacher's sittings that are open right now. No payload. */
    LIVE_RUNNING_NOW,

    /** Who is sitting one execution and how far they have got. Payload is the execution id. */
    LIVE_STATUS,

    /** Changes the time allowed, mid-exam. Payload is a {@link TimeChangeRequest}. */
    LIVE_CHANGE_TIME,

    /** Opens one more attempt for one student. Payload is an {@link AttemptGrantRequest}. */
    LIVE_GRANT_ATTEMPT,

    // ---- SUC-9: marking (milestone 9) ----

    /** Sittings this teacher released that have papers in them. No payload. */
    GRADING_SITTINGS,

    /** Every mark in one sitting. Payload is the execution id. */
    GRADING_LIST,

    /** One paper, marked, with the right answers shown. Payload is the submission id. */
    GRADING_GET,

    /** Changes a mark by hand. Payload is a {@link GradeChange}. */
    GRADING_CHANGE,

    /** A note against one question. Payload is a {@link CommentRequest}. */
    GRADING_QUESTION_COMMENT,

    /** A note about the paper as a whole. Payload is a {@link CommentRequest}. */
    GRADING_GENERAL_COMMENT,

    /** Publishes one mark. Payload is the submission id. */
    GRADING_APPROVE,

    /**
     * Saves the mark, the reason, every comment, and publishes - in one action.
     * Payload is a {@link PublishRequest}. This is what the marking screen's single
     * button sends; the separate save types above remain for anything that needs
     * one step on its own.
     */
    GRADING_PUBLISH,

    /** Publishes every unapproved mark in a sitting. Payload is the execution id. */
    GRADING_APPROVE_ALL,

    /** Adds a factor to every mark in a sitting. Payload is a {@link GradeChange}. */
    GRADING_FACTOR,

    /** Average, median and deciles. Payload is the execution id. */
    GRADING_STATISTICS,

    // ---- SUC-10: a student reading her results (milestone 10) ----

    /** Every exam she has sat. No payload. */
    RESULTS_MINE,

    /** One of her marked papers. Payload is the submission id. */
    RESULTS_MARKED_EXAM,

    // ---- SUC-11: a teacher's results and histogram (milestone 11) ----

    /** Every exam she wrote, whoever ran it (requirement 59). No payload. */
    TEACHER_REPORT_EXAMS,

    /** The sittings of one of her exams. Payload is the exam id. */
    TEACHER_REPORT_SITTINGS,

    /** Marks and statistics together. Payload is a {@link ResultsQuery}. */
    TEACHER_REPORT_RESULTS,

    // ---- SUC-12: the principal's read-only browse (milestone 12) ----

    /** The whole question bank, every course. No payload. Answers are NOT included. */
    PRINCIPAL_QUESTIONS,

    /** One question with its answers. Payload is a {@link QuestionRef}. */
    PRINCIPAL_QUESTION_GET,

    /** Every exam, whatever its state. No payload. */
    PRINCIPAL_EXAMS,

    /** One exam in full. Payload is an {@link ExamRef}. */
    PRINCIPAL_EXAM_GET,

    /** The sittings of one exam. Payload is the exam id. */
    PRINCIPAL_SITTINGS,

    /** Marks and statistics together. Payload is a {@link ResultsQuery}. */
    PRINCIPAL_RESULTS,

    // ---- SUC-11 / SUC-12: statistical reports (milestone 13) ----

    /** Which reports this user may run. No payload. */
    REPORT_TYPES,

    /** What a report can be run about. Payload is a {@link hsts.common.enums.ReportType}. */
    REPORT_SUBJECTS,

    /** Builds one report. Payload is a {@link ReportRequest}. */
    REPORT_GENERATE,

    // ---- SUC-13: the teacher builds a course bot (milestone 14) ----

    /** The bots of the courses she teaches. No payload. */
    BOT_LIST_MINE,

    /** Courses she teaches that have no bot yet. No payload. */
    BOT_COURSES_FREE,

    /** Creates a bot. Payload is a {@link BotCreateRequest}. */
    BOT_CREATE,

    /** Turns a bot on or off. Payload is a {@link BotStatusRequest}. */
    BOT_SET_STATUS,

    /** Deletes a bot, its material AND its history. Payload is the bot id. */
    BOT_DELETE,

    /** How many stored questions a delete would destroy. Payload is the bot id. */
    BOT_DELETE_IMPACT,

    /** Adds material for the bot to read. Payload is a {@link SourceRequest}. */
    BOT_ADD_SOURCE,

    /** Removes one piece of material. Payload is the source id. */
    BOT_REMOVE_SOURCE,

    /** Usage figures, with no identities. Payload is the bot id. */
    BOT_USAGE,

    // ---- SUC-14 / SUC-15: the student uses it (milestone 14) ----

    /** The bots of the courses she is enrolled in. No payload. */
    BOT_AVAILABLE,

    /** Asks a question. Payload is a {@link BotQuestion}. */
    BOT_ASK,

    /** Her own question history. No payload. */
    BOT_MY_HISTORY
}
