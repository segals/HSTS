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

    public AttemptGrantRequest(int executionId, String studentId, String reason) {
        this.executionId = executionId;
        this.studentId = studentId;
        this.reason = reason;
    }

    public int getExecutionId() { return executionId; }
    public String getStudentId() { return studentId; }
    public String getReason()    { return reason; }
}
