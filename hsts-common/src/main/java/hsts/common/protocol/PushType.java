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
     * Nine tenths of her time has gone. Payload is the <b>seconds</b> remaining.
     *
     * <p>Seconds, not minutes. The wording the customer asked for names both -
     * "you have 6 minutes and 42 seconds left" - and a payload of whole minutes
     * cannot produce that. It also read badly at the end: with fifty seconds to go
     * the popup said "less than a minute left", which is true and useless.</p>
     *
     * <p>Requirement 43: <i>"לקראת סיום 90% מזמן הבחינה יופיע popup שמודיע זאת עם
     * מספר הדקות שנשארו"</i> - a popup near the 90% mark saying how many minutes
     * are left.</p>
     *
     * <p>Sent <b>once</b> per attempt. The clock ticks every second, and a warning
     * that reappeared every second for the last tenth of the exam would be worse
     * than no warning at all.</p>
     */
    EXAM_TIME_WARNING,

    /**
     * The <b>sitting</b> is about to close for everybody. Payload is the seconds
     * remaining.
     *
     * <p>The other half of {@link #EXAM_TIME_WARNING}. An attempt has two possible
     * ends - her own allowance and the room's closing time (requirement 45) - and
     * whichever comes first is the one worth warning her about. A girl who started
     * ten minutes before the room closes has ninety per cent of her own time still
     * in hand, so the 90% popup would never reach her and she would be handed in
     * with no warning at all.</p>
     *
     * <p>Sent five minutes before the close, and <b>only to the students the close
     * will actually cut short</b>. Exactly one of the two warnings reaches any one
     * attempt - the one attached to the end that is going to stop her.</p>
     */
    EXAM_CLOSING_WARNING,

    /**
     * The student's own time ran out and the server closed her exam.
     *
     * <p>Requirement 41 and acceptance test 2.6: whatever she had entered is
     * saved and the paper is closed for her.</p>
     */
    EXAM_AUTO_SUBMITTED,

    /**
     * The sitting's closing time arrived and the server closed her exam with it.
     *
     * <p>Requirement 45: <i>"בסיום זמן הבחינה, המערכת תסגור את הבחינה עבור כל
     * התלמידות ותשמור את התשובות שהוזנו"</i> - at the end of the exam time the
     * system closes the exam <b>for all the students</b> and keeps what they
     * entered.</p>
     *
     * <p>Told apart from {@link #EXAM_AUTO_SUBMITTED} so the screen can say which
     * happened. "Your time is up" is wrong and confusing for a girl who still had
     * twenty minutes of her own left when the room closed.</p>
     */
    EXAM_CLOSED_FOR_EVERYONE,

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
    BOT_AVAILABILITY_CHANGED,

    /**
     * Signed out for being idle. Payload is how many minutes she was idle.
     *
     * <p>Requirement 76. Sent just before the connection is closed, so the client
     * can return to the login screen saying <em>why</em> - "the connection was
     * lost" is a different thing and invites her to blame the network.</p>
     *
     * <p>A student inside an exam is never sent this: she is working, not idle, and
     * the server knows it because there is a row saying so.</p>
     */
    SESSION_TIMED_OUT,

    /**
     * Her teacher has opened one more attempt for her. Payload is the execution id.
     *
     * <p>Requirement 61. Told rather than discovered: without this she would find
     * out by typing the code again and being surprised it worked.</p>
     */
    EXTRA_ATTEMPT_GRANTED
}
