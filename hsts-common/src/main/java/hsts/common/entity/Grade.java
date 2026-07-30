package hsts.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One student's mark for one attempt.
 *
 * <h2>Two grades, not one</h2>
 *
 * <p>{@link #autoGrade} is what the system worked out; {@link #finalGrade} is what
 * the teacher settled on. Keeping both means a manual change stays visible as a
 * change rather than replacing the truth - which matters, because requirement 52
 * makes an explanation compulsory whenever they differ, and somebody may later ask
 * what was altered and why.</p>
 *
 * <p>Nothing here reaches a student until {@link #approved} is true
 * (requirement 53). Before that she is told only that it is waiting.</p>
 */
public class Grade implements Serializable {

    private static final long serialVersionUID = 1L;

    private int submissionId;

    /** What the automatic marking produced. Never overwritten. */
    private int autoGrade;

    /** What the teacher settled on. Starts equal to the automatic mark. */
    private int finalGrade;

    /** Added by a bulk factor (requirement 77). Already inside finalGrade. */
    private int factor;

    private boolean approved;
    private LocalDateTime approvedAt;

    /** Compulsory whenever finalGrade differs from autoGrade (requirement 52). */
    private String manualChangeExplanation;

    private String teacherGeneralComment;
    private String gradedBy;

    // ---- filled in for display ----
    private String studentId;
    private String studentName;
    private String examId;
    private String courseName;
    private Integer actualDuration;
    private int executionId;
    private String submissionStatus;

    /**
     * False for a student who is <em>still sitting</em> the exam.
     *
     * <p>Such a student has no mark yet - there is no row in {@code grade} for her -
     * but she must still appear in the teacher's list, because the sitting's own
     * "how many sat it" count includes her. Leaving her out made the two numbers
     * disagree and a sitting with one student in it look empty.</p>
     *
     * <p>Only the marking list ever produces one of these; every other query
     * returns real marks, so this stays true for them.</p>
     */
    private boolean marked = true;

    /** Per-question marking: which were wrong, and any comment. */
    private List<QuestionFeedback> feedback = new ArrayList<>();

    public Grade() {
    }

    public int getSubmissionId()               { return submissionId; }
    public int getAutoGrade()                  { return autoGrade; }
    public int getFinalGrade()                 { return finalGrade; }
    public int getFactor()                     { return factor; }
    public boolean isApproved()                { return approved; }
    public LocalDateTime getApprovedAt()       { return approvedAt; }
    public String getManualChangeExplanation() { return manualChangeExplanation; }
    public String getTeacherGeneralComment()   { return teacherGeneralComment; }
    public String getGradedBy()                { return gradedBy; }
    public String getStudentId()               { return studentId; }
    public String getStudentName()             { return studentName; }
    public String getExamId()                  { return examId; }
    public String getCourseName()              { return courseName; }
    public Integer getActualDuration()         { return actualDuration; }
    public int getExecutionId()                { return executionId; }
    public String getSubmissionStatus()        { return submissionStatus; }
    public boolean isMarked()                  { return marked; }
    public List<QuestionFeedback> getFeedback() { return feedback; }

    public void setSubmissionId(int id)                 { this.submissionId = id; }
    public void setAutoGrade(int grade)                 { this.autoGrade = grade; }
    public void setFinalGrade(int grade)                { this.finalGrade = grade; }
    public void setFactor(int factor)                   { this.factor = factor; }
    public void setApproved(boolean approved)           { this.approved = approved; }
    public void setApprovedAt(LocalDateTime at)         { this.approvedAt = at; }
    public void setManualChangeExplanation(String s)    { this.manualChangeExplanation = s; }
    public void setTeacherGeneralComment(String s)      { this.teacherGeneralComment = s; }
    public void setGradedBy(String userId)              { this.gradedBy = userId; }
    public void setStudentId(String id)                 { this.studentId = id; }
    public void setStudentName(String name)             { this.studentName = name; }
    public void setExamId(String examId)                { this.examId = examId; }
    public void setCourseName(String name)              { this.courseName = name; }
    public void setActualDuration(Integer minutes)      { this.actualDuration = minutes; }
    public void setExecutionId(int id)                  { this.executionId = id; }
    public void setSubmissionStatus(String status)      { this.submissionStatus = status; }
    public void setMarked(boolean marked)               { this.marked = marked; }

    public void setFeedback(List<QuestionFeedback> feedback) {
        this.feedback = (feedback == null) ? new ArrayList<>() : new ArrayList<>(feedback);
    }

    /** True when the teacher moved the mark away from what the system worked out. */
    public boolean wasChangedByHand() {
        return finalGrade != autoGrade;
    }

    /** The marking of one question, if it is loaded. */
    public QuestionFeedback feedbackFor(String questionId) {
        for (QuestionFeedback f : feedback) {
            if (f.getQuestionId().equals(questionId)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Whose approval this mark is waiting for, or null once it has been given.
     *
     * <p>The teacher who released the sitting is the one who approves it - decided
     * during planning and unchanged. Named here so every screen says the same
     * thing rather than each inventing its own phrase for "not approved yet".</p>
     */
    public String getWaitingFor() {
        return approved ? null : "the teacher";
    }

    @Override
    public String toString() {
        return studentName + "  ·  " + finalGrade
             + (approved ? "  (approved)" : "  (waiting for the teacher's approval)");
    }
}
