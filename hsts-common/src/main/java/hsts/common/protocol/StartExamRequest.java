package hsts.common.protocol;

import java.io.Serializable;

/**
 * A student saying "this is me, start my clock".
 *
 * <p>System description §4: she types her identity number onto the form, and only
 * then may she answer. The timer starts at that moment, not when the code was
 * accepted - so a student who types the code early does not lose minutes waiting
 * for the class to settle.</p>
 */
public class StartExamRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int executionId;

    /** The ת"ז she typed. Checked against the account she is signed in to. */
    private final String typedIdNumber;

    public StartExamRequest(int executionId, String typedIdNumber) {
        this.executionId = executionId;
        this.typedIdNumber = typedIdNumber;
    }

    public int getExecutionId()      { return executionId; }
    public String getTypedIdNumber() { return typedIdNumber; }
}
