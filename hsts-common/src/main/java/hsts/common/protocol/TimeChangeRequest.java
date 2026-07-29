package hsts.common.protocol;

import java.io.Serializable;

/**
 * A teacher changing how long a sitting is allowed to run, while it runs.
 *
 * <p>מתווה scenario 7 and requirement 47: "במקרים חריגים, בזמן ביצוע בחינה המורה
 * יכולה לשנות את הזמן המוקצה" - and the change is temporary, valid for
 * <em>this</em> sitting only. It never touches the exam itself, so the next class
 * to sit the same paper is unaffected.</p>
 *
 * <p>{@link #deltaMinutes} is a change rather than a new total. A teacher thinks
 * "give them another quarter of an hour", not "make it 105 minutes" - and a delta
 * is also the only form that behaves correctly when students started at different
 * moments, since each one's own deadline simply moves by the same amount.</p>
 */
public class TimeChangeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int executionId;

    /** Positive to give more time, negative to take some back. */
    private final int deltaMinutes;

    public TimeChangeRequest(int executionId, int deltaMinutes) {
        this.executionId = executionId;
        this.deltaMinutes = deltaMinutes;
    }

    public int getExecutionId()  { return executionId; }
    public int getDeltaMinutes() { return deltaMinutes; }
}
