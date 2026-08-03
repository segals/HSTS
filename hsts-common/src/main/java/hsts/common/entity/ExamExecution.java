package hsts.common.entity;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * One occasion on which an exam is given to students - a "release from the drawer".
 *
 * <p>System description §2.2 allows the same exam to be released many times, and
 * each release is its own event with its own code, its own window and its own
 * results. That is why this is a separate entity and not a set of fields on
 * {@link Exam}.</p>
 *
 * <p>It also answers the "in the drawer" question that {@code ExamStatus}
 * deliberately does not: an exam is in the drawer when it has no execution open
 * right now.</p>
 *
 * <h2>The window is a deadline to START, not to finish</h2>
 *
 * <p>Settled during planning. With a window of 10:00-12:00 and 90 minutes
 * allotted, a student who begins at 11:55 works until 13:25 - she gets her full
 * time. What {@link #closeTime} controls is whether she may <em>begin</em>.</p>
 *
 * <p>That reading matches acceptance test 2.10, whose message is
 * "מועד <b>פתיחת</b> הבחינה הסתיים" - the exam's <em>opening</em> period has
 * ended, not the exam itself.</p>
 */
public class ExamExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private int executionId;

    private String examId;
    private int examVersion;

    /** 4 characters, letters or digits, stored upper-case. Spoken aloud to the class. */
    private String executionCode;

    private LocalDateTime openTime;
    private LocalDateTime closeTime;

    /** Minutes allowed per student. A teacher may change this while the exam runs. */
    private int allocatedDuration;

    /** What it was before any live change, so the change is visible. */
    private int originalDuration;

    private int maxAttempts = 1;

    /** The teacher who released it. She is the one who marks it. */
    private String releasedBy;
    private String releasedByName;

    private LocalDateTime createdAt;

    // ---- filled in for display; not columns of this table ----
    /** The exam's name, so a sitting can be named rather than numbered. */
    private String examName;

    private String courseName;
    private String examTitle;

    /**
     * Counted from the submissions rather than stored (requirement 48).
     *
     * <p>A stored counter that someone forgets to increment is a bug found at the
     * demo; a {@code COUNT} is right by construction.</p>
     */
    private int numStarted;
    private int numFinishedSelf;
    private int numTimedOut;

    public ExamExecution() {
    }

    public int getExecutionId()             { return executionId; }
    public String getExamId()               { return examId; }
    public int getExamVersion()             { return examVersion; }
    public String getExecutionCode()        { return executionCode; }
    public LocalDateTime getOpenTime()      { return openTime; }
    public LocalDateTime getCloseTime()     { return closeTime; }
    public int getAllocatedDuration()       { return allocatedDuration; }
    public int getOriginalDuration()        { return originalDuration; }
    public int getMaxAttempts()             { return maxAttempts; }
    public String getReleasedBy()           { return releasedBy; }
    public String getReleasedByName()       { return releasedByName; }
    public LocalDateTime getCreatedAt()     { return createdAt; }
    public String getExamName()             { return examName; }

    /** "Mid-term  ·  010101", the same way round as everywhere else. */
    public String describeExam() {
        return (examName == null || examName.isBlank())
                ? examId : examName + "  ·  " + examId;
    }

    public String getCourseName()           { return courseName; }
    public String getExamTitle()            { return examTitle; }
    public int getNumStarted()              { return numStarted; }
    public int getNumFinishedSelf()         { return numFinishedSelf; }
    public int getNumTimedOut()             { return numTimedOut; }

    public void setExecutionId(int id)               { this.executionId = id; }
    public void setExamId(String examId)             { this.examId = examId; }
    public void setExamVersion(int version)          { this.examVersion = version; }
    public void setExecutionCode(String code)        { this.executionCode = code; }
    public void setOpenTime(LocalDateTime t)         { this.openTime = t; }
    public void setCloseTime(LocalDateTime t)        { this.closeTime = t; }
    public void setAllocatedDuration(int minutes)    { this.allocatedDuration = minutes; }
    public void setOriginalDuration(int minutes)     { this.originalDuration = minutes; }
    public void setMaxAttempts(int attempts)         { this.maxAttempts = attempts; }
    public void setReleasedBy(String userId)         { this.releasedBy = userId; }
    public void setReleasedByName(String name)       { this.releasedByName = name; }
    public void setCreatedAt(LocalDateTime t)        { this.createdAt = t; }
    public void setExamName(String name)             { this.examName = name; }
    public void setCourseName(String name)           { this.courseName = name; }
    public void setExamTitle(String title)           { this.examTitle = title; }
    public void setNumStarted(int n)                 { this.numStarted = n; }
    public void setNumFinishedSelf(int n)            { this.numFinishedSelf = n; }
    public void setNumTimedOut(int n)                { this.numTimedOut = n; }

    /** True while students may still begin - the window is open. */
    public boolean isOpenAt(LocalDateTime moment) {
        return !moment.isBefore(openTime) && moment.isBefore(closeTime);
    }

    /** True before the window opens. */
    public boolean isNotYetOpenAt(LocalDateTime moment) {
        return moment.isBefore(openTime);
    }

    /** True once nobody new may begin. Students already inside carry on. */
    public boolean hasClosedAt(LocalDateTime moment) {
        return !moment.isBefore(closeTime);
    }

    /** True when the allotted time has been changed since release. */
    public boolean isDurationExtended() {
        return allocatedDuration != originalDuration;
    }

    /** Students who began but did not finish by themselves. */
    public int getNumUnfinished() {
        return Math.max(0, numStarted - numFinishedSelf - numTimedOut);
    }

    public String describeWindow() {
        return openTime + "  to  " + closeTime;
    }

    /** How long the window lasts, for display. */
    public long getWindowMinutes() {
        return Duration.between(openTime, closeTime).toMinutes();
    }

    @Override
    public String toString() {
        return "Code " + executionCode + "  ·  exam " + examId + " v" + examVersion;
    }
}
