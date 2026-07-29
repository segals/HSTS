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
 * <h2>The deadline is stored, not calculated on demand</h2>
 *
 * <p>{@link #deadline} is written when she enters her ID: start time plus the
 * minutes allowed. That single field is what makes the agreed rule work - the
 * execution's closing moment is a deadline to <em>start</em>, so a student who
 * begins at 11:55 of a 10:00-12:00 window with 90 minutes allowed works until
 * 13:25.</p>
 *
 * <p>Storing it also means the clock survives a client restart, and that a
 * teacher granting extra time can move one row rather than recalculating from
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

    /** Seconds left at this moment, never negative. */
    public long secondsRemainingAt(LocalDateTime moment) {
        if (deadline == null) {
            return 0;
        }
        return Math.max(0, Duration.between(moment, deadline).toSeconds());
    }

    /** True once her personal time is up, whatever the sitting's window says. */
    public boolean isExpiredAt(LocalDateTime moment) {
        return deadline != null && !moment.isBefore(deadline);
    }

    /** The whole time she was allowed, in seconds - used to work out the 90% point. */
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
