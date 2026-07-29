package hsts.common.protocol;

/**
 * Things the server tells a client without being asked.
 *
 * <h2>Why the server has to push at all</h2>
 *
 * <p>NFR 18 forbids manual screen refresh - "ללא רענון מסכים יזום". A client that
 * only ever speaks when spoken to would need a Refresh button, or would have to
 * poll the server on a timer. Polling is worse than it looks: too slow and the
 * screen is stale, too fast and every idle client hammers the server for nothing.</p>
 *
 * <p>So the server keeps the connection and speaks first when something changes.
 * That is the <b>Observer</b> pattern from the submitted class diagram, made real:
 * the screens observe, the server drives.</p>
 */
public enum PushType {

    /** A coordinator approved an exam. Sent to its author. */
    EXAM_APPROVED,

    /**
     * A coordinator rejected an exam. Sent to its author with the reason.
     *
     * <p>Requirement 33 says the reason is "תישלח למורה" - <em>sent</em> to the
     * teacher, not merely stored for her to find later. This is what sends it.</p>
     */
    EXAM_REJECTED,

    /** A new exam is waiting. Sent to the coordinator of that subject. */
    EXAM_AWAITING_APPROVAL
}
