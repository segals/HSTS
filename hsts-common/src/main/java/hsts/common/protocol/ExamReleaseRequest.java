package hsts.common.protocol;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Everything a teacher settles when taking an exam out of the drawer.
 *
 * <p>מתווה scenario 5 groups all of this into one action: pick an approved exam,
 * set the opening and closing moments, and set the 4-character code. The attempt
 * count and the allotted minutes go with it, because they are fixed per release
 * rather than per exam.</p>
 *
 * <p>It names the exam <em>version</em>, not just the exam. Requirement 34 speaks
 * of "לכל גרסת בחינה מאושרת" - each approved exam version - and releasing "exam
 * 010101" without saying which version could hand out a paper nobody approved.</p>
 */
public class ExamReleaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String examId;
    private final int examVersion;

    private final LocalDateTime openTime;
    private final LocalDateTime closeTime;

    /** 4 characters, letters or digits. Normalised to upper case by the server. */
    private final String executionCode;

    private final int allocatedDuration;
    private final int maxAttempts;

    public ExamReleaseRequest(String examId, int examVersion,
                              LocalDateTime openTime, LocalDateTime closeTime,
                              String executionCode, int allocatedDuration, int maxAttempts) {
        this.examId = examId;
        this.examVersion = examVersion;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.executionCode = executionCode;
        this.allocatedDuration = allocatedDuration;
        this.maxAttempts = maxAttempts;
    }

    public String getExamId()             { return examId; }
    public int getExamVersion()           { return examVersion; }
    public LocalDateTime getOpenTime()    { return openTime; }
    public LocalDateTime getCloseTime()   { return closeTime; }
    public String getExecutionCode()      { return executionCode; }
    public int getAllocatedDuration()     { return allocatedDuration; }
    public int getMaxAttempts()           { return maxAttempts; }

    @Override
    public String toString() {
        return "Release " + examId + " v" + examVersion + " with code " + executionCode;
    }
}
