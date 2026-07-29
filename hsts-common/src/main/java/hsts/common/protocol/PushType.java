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
    EXAM_AWAITING_APPROVAL,

    /**
     * How many seconds a student has left. Payload is a Long.
     *
     * <p>Sent by the server rather than counted by the client, so the clock
     * cannot drift and cannot be tampered with. Acceptance test 2.11 requires
     * the timer to be synchronised from the server.</p>
     */
    EXAM_TIME_TICK,

    /**
     * The student's time ran out and the server closed her exam.
     *
     * <p>Requirement 45 and acceptance test 2.6: whatever she had entered is
     * saved and the paper is closed for her.</p>
     */
    EXAM_AUTO_SUBMITTED,

    /**
     * The teacher changed how long this sitting is allowed to run.
     *
     * <p>Acceptance test 2.7: the student's countdown moves <em>by itself</em>.
     * Payload is the new seconds remaining, so the screen does not have to work
     * anything out - it is told.</p>
     */
    EXAM_TIME_CHANGED,

    /**
     * Somebody started or handed in, so the teacher's live view is out of date.
     *
     * <p>Sent to the teacher who released the sitting. NFR 18 forbids a Refresh
     * button on her screen just as much as on a student's.</p>
     */
    EXAM_LIVE_STATUS,

    /**
     * A mark has been approved, or changed after approval.
     *
     * <p>Requirement 53 makes the result available to the student on approval;
     * NFR 18 means she should not have to keep checking. Payload is the
     * submission id.</p>
     */
    GRADE_APPROVED,

    /**
     * A mark was approved, changed or factored, so any results on screen are stale.
     *
     * <p>Sent to the exam's <b>author</b> (requirement 59 gives her the results of
     * exams she wrote), to the teacher who <b>released</b> the sitting, and to the
     * principal (requirement 62). {@code GRADE_APPROVED} tells the student; this
     * tells everybody looking at the figures.</p>
     */
    RESULTS_CHANGED,

    /**
     * Something about a course's bots changed. Sent to the course's <b>teachers</b>.
     *
     * <p>Created, renamed, switched on or off, material added or removed, deleted -
     * or a student asked it something, which changes the usage figures. Requirement
     * 67 lets a colleague change a bot, so her colleagues' screens must follow
     * without a Refresh button.</p>
     *
     * <p><b>Never carries a student's name</b>, even when a question is what
     * changed - requirement 75.</p>
     */
    BOT_CHANGED,

    /**
     * A course's bot became usable, or stopped being usable. Sent to its students.
     *
     * <p>Requirement 70 makes the bot available only while it is switched on, and
     * requirement 60 lets the teacher flip that at any moment. Without this a
     * student would sit looking at "not switched on" after it had been switched
     * on.</p>
     */
    BOT_AVAILABILITY_CHANGED
}
