package hsts.common.entity;

import hsts.common.enums.SubmissionStatus;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One student's attempt at one exam sitting.
 *
 * <h2>Two ends, and the earlier one wins</h2>
 *
 * <p>An attempt can be stopped by two different clocks and they are not the same
 * thing:</p>
 *
 * <ul>
 *   <li>{@link #deadline} - <b>her own</b> time. Written when she enters her ID:
 *       start time plus the minutes allowed. Requirement 41: "עם הזנת מספר הזהות
 *       מתחיל מד-הזמן; עם תום הזמן המוקצה הבחינה נסגרת אוטומטית".</li>
 *   <li>{@link #closeTime} - the sitting's closing moment, the same for everybody
 *       in the room. Requirement 45: "בסיום זמן הבחינה, המערכת תסגור את הבחינה
 *       <b>עבור כל התלמידות</b> ותשמור את התשובות שהוזנו".</li>
 * </ul>
 *
 * <p>{@link #effectiveEnd()} is whichever comes first, and every judgement in the
 * system - how long is left, has she run out, how long did she take - is made
 * against it. A girl who starts ten minutes before the room closes has ten
 * minutes, not her full ninety.</p>
 *
 * <p>Kept as two fields rather than one clamped deadline because the teacher may
 * add time mid-exam (requirement 47), and because the two ends need different
 * words: "your time is up" and "the exam has closed for everyone" are different
 * things to be told.</p>
 *
 * <p>Storing the deadline also means the clock survives a client restart, and that
 * a teacher granting extra time can move one row rather than recalculating from
 * pieces scattered across the schema.</p>
 *
 * <p><b>Time is decided on the server.</b> The client displays what it is told.
 * Acceptance test 2.11 already demanded this - "הטיימר ממשיך מהנקודה הנכונה
 * (סונכרן מהשרת)" - and it also removes any worry about the two laptops' clocks
 * disagreeing.</p>
 */
public class StudentExam implements Serializable {

    private static final long serialVersionUID = 1L;

    private int submissionId;
    private int executionId;
    private String studentId;
    private String studentName;

    /** Needed to address a push: the session registry is keyed by username. */
    private String studentUsername;
    private int attemptNo = 1;

    private LocalDateTime startTime;
    private LocalDateTime deadline;
    private LocalDateTime endTime;

    /**
     * The sitting's closing moment, copied from the execution.
     *
     * <p>Not stored on this row - read with it, so a teacher moving the sitting's
     * window could never leave a stale copy behind. Null when the attempt was
     * loaded by something that does not join the execution, and every method here
     * falls back to the deadline alone in that case.</p>
     */
    private LocalDateTime closeTime;

    /** Whole minutes actually taken (requirement 46). Null until she is finished. */
    private Integer actualDuration;

    private SubmissionStatus status = SubmissionStatus.IN_PROGRESS;

    /** The paper she is sitting: questions, points and order. */
    private List<ExamQuestion> questions = new ArrayList<>();

    /** What she has chosen so far. */
    private List<StudentAnswer> answers = new ArrayList<>();

    // ---- for display ----
    private String examId;
    private String courseName;
    private String instructionsForStudents;

    public StudentExam() {
    }

    public int getSubmissionId()            { return submissionId; }
    public int getExecutionId()             { return executionId; }
    public String getStudentId()            { return studentId; }
    public String getStudentName()          { return studentName; }
    public String getStudentUsername()      { return studentUsername; }
    public int getAttemptNo()               { return attemptNo; }
    public LocalDateTime getStartTime()     { return startTime; }
    public LocalDateTime getDeadline()      { return deadline; }
    public LocalDateTime getCloseTime()     { return closeTime; }
    public LocalDateTime getEndTime()       { return endTime; }
    public Integer getActualDuration()      { return actualDuration; }
    public SubmissionStatus getStatus()     { return status; }
    public List<ExamQuestion> getQuestions() { return questions; }
    public List<StudentAnswer> getAnswers()  { return answers; }
    public String getExamId()               { return examId; }
    public String getCourseName()           { return courseName; }
    public String getInstructionsForStudents() { return instructionsForStudents; }

    public void setSubmissionId(int id)                { this.submissionId = id; }
    public void setExecutionId(int id)                 { this.executionId = id; }
    public void setStudentId(String id)                { this.studentId = id; }
    public void setStudentName(String name)            { this.studentName = name; }
    public void setStudentUsername(String u)           { this.studentUsername = u; }
    public void setAttemptNo(int no)                   { this.attemptNo = no; }
    public void setStartTime(LocalDateTime t)          { this.startTime = t; }
    public void setDeadline(LocalDateTime t)           { this.deadline = t; }
    public void setCloseTime(LocalDateTime t)          { this.closeTime = t; }
    public void setEndTime(LocalDateTime t)            { this.endTime = t; }
    public void setActualDuration(Integer minutes)     { this.actualDuration = minutes; }
    public void setStatus(SubmissionStatus status)     { this.status = status; }
    public void setExamId(String examId)               { this.examId = examId; }
    public void setCourseName(String name)             { this.courseName = name; }
    public void setInstructionsForStudents(String s)   { this.instructionsForStudents = s; }

    public void setQuestions(List<ExamQuestion> questions) {
        this.questions = (questions == null) ? new ArrayList<>() : new ArrayList<>(questions);
    }

    public void setAnswers(List<StudentAnswer> answers) {
        this.answers = (answers == null) ? new ArrayList<>() : new ArrayList<>(answers);
    }

    public boolean isInProgress() {
        return status == SubmissionStatus.IN_PROGRESS;
    }

    /**
     * The moment this attempt really ends: the earlier of her own deadline and the
     * sitting's close.
     *
     * <p>One method, used by everything, so the countdown on her screen, the guard
     * that refuses a late answer and the clock that hands her paper in can never
     * disagree about when her exam ends.</p>
     */
    public LocalDateTime effectiveEnd() {
        if (closeTime == null) {
            return deadline;
        }
        if (deadline == null) {
            return closeTime;
        }
        return closeTime.isBefore(deadline) ? closeTime : deadline;
    }

    /**
     * True when the room closing is what will stop her, rather than her own time.
     *
     * <p>Decides which of the two warnings she is sent, and which sentence she is
     * given when her paper is handed in for her. A tie goes to her own time: that
     * is the one the requirements name a warning for.</p>
     */
    public boolean isCutShortByClose() {
        return closeTime != null && deadline != null && closeTime.isBefore(deadline);
    }

    /** Seconds left at this moment, never negative. */
    public long secondsRemainingAt(LocalDateTime moment) {
        LocalDateTime end = effectiveEnd();
        if (end == null) {
            return 0;
        }
        return Math.max(0, Duration.between(moment, end).toSeconds());
    }

    /** True once her exam is over - by her own clock or by the room's. */
    public boolean isExpiredAt(LocalDateTime moment) {
        LocalDateTime end = effectiveEnd();
        return end != null && !moment.isBefore(end);
    }

    /**
     * The whole time she was allowed, in seconds - used to work out the 90% point.
     *
     * <p>Her own allowance, deliberately, not the shortened span: the 90% warning
     * belongs to the clock it measures, and when the room's closing is the binding
     * end she is sent the closing warning instead.</p>
     */
    public long totalSeconds() {
        if (startTime == null || deadline == null) {
            return 0;
        }
        return Math.max(0, Duration.between(startTime, deadline).toSeconds());
    }

    /** What she chose for one question, or null. */
    public Integer answerFor(String questionId) {
        for (StudentAnswer answer : answers) {
            if (answer.getQuestionId().equals(questionId)) {
                return answer.getSelectedAnswerNo();
            }
        }
        return null;
    }

    public int getAnsweredCount() {
        int count = 0;
        for (StudentAnswer answer : answers) {
            if (answer.isAnswered()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "Attempt " + attemptNo + " of exam " + examId + "  ·  " + status.getDisplayName();
    }
}
