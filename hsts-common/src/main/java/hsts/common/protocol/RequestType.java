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
    LIVE_CHANGE_TIME
}
