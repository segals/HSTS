package hsts.common.protocol;

import java.io.Serializable;

/**
 * A teacher opening one more attempt for one student (requirement 61).
 *
 * <p><i>"כדי לפתוח ניסיון נוסף המורה צריכה לאשר זאת"</i> - an attempt beyond the
 * number set at release needs her approval, and this carries it.</p>
 *
 * <p>{@link #getReason()} is optional. Nothing requires one, but a teacher granting
 * a re-sit usually has a reason - the power went off, the girl was ill - and a
 * grant with no note is hard to account for six weeks later.</p>
 */
public class AttemptGrantRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int executionId;
    private final String studentId;
    private final String reason;

    /**
     * True when this grants an attempt to <b>everyone</b> who sat the sitting.
     *
     * <p>A separate flag rather than a null {@link #studentId} meaning "all", because
     * a null id also arrives when something has gone wrong on the way here, and
     * "everybody gets another go" is not a mistake that should be possible to make
     * by accident.</p>
     */
    private final boolean everyone;

    public AttemptGrantRequest(int executionId, String studentId, String reason) {
        this.executionId = executionId;
        this.studentId = studentId;
        this.reason = reason;
        this.everyone = false;
    }

    private AttemptGrantRequest(int executionId, String reason, boolean everyone) {
        this.executionId = executionId;
        this.studentId = null;
        this.reason = reason;
        this.everyone = everyone;
    }

    /**
     * One more attempt for every student who has sat this sitting.
     *
     * <p>Asked for by the customer. The case it is for is a whole room, not a
     * person: the power failed, or the network went down mid-exam.</p>
     */
    public static AttemptGrantRequest forEveryone(int executionId, String reason) {
        return new AttemptGrantRequest(executionId, reason, true);
    }

    public int getExecutionId() { return executionId; }
    public String getStudentId() { return studentId; }
    public String getReason()    { return reason; }
    public boolean isEveryone()  { return everyone; }
}
