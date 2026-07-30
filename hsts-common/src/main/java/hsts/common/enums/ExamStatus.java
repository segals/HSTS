package hsts.common.enums;

/**
 * How far an exam has got through approval.
 *
 * <h2>Why there is no IN_DRAWER value</h2>
 *
 * <p>The submitted class diagram had four values, including {@code IN_DRAWER}.
 * It was removed during planning because it could not be made to work.</p>
 *
 * <p>System description §2.1 uses "in the drawer" to mean <em>not currently being
 * sat</em>. So an exam the coordinator approved on Sunday, which no class takes
 * until Thursday, is both {@code APPROVED} and in the drawer at the same time -
 * and a single field cannot hold two values.</p>
 *
 * <p>Worse, the same exam can be taken out of the drawer many times (§2.2). Given
 * one sitting that has finished and another scheduled for next week, there is no
 * honest answer to "is this exam in the drawer?" - because the question does not
 * belong to the exam at all. It belongs to a particular release.</p>
 *
 * <p>So "in the drawer" is not stored. It is answered by asking whether the exam
 * has an execution that is open right now, which is always correct without anyone
 * having to remember to flip a flag.</p>
 */
public enum ExamStatus {

    /** Written and saved, waiting for the subject coordinator to look at it. */
    PENDING_APPROVAL("Waiting for Subject Coordinator approval", "Subject Coordinator"),

    /** The coordinator approved it. Only now may it be released to a class. */
    APPROVED("Approved", null),

    /** The coordinator rejected it. The reason is stored and sent to the author. */
    REJECTED("Rejected", null);

    private final String displayName;
    private final String waitingFor;

    ExamStatus(String displayName, String waitingFor) {
        this.displayName = displayName;
        this.waitingFor = waitingFor;
    }

    /**
     * What every screen shows for this status.
     *
     * <p>"Pending approval" used to be the wording, and it left the obvious
     * question unanswered: pending on whom? Naming the role here rather than on
     * each screen means the exam list, the exam detail, the principal's browser
     * and the teacher's report cannot drift apart.</p>
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whose approval this is waiting for, or null when nobody's is outstanding.
     *
     * <p>Separate from {@link #getDisplayName()} so a screen that wants to build
     * its own sentence - "Saved. Waiting for Subject Coordinator approval." - does
     * not have to take the display name apart to find the role in it.</p>
     */
    public String getWaitingFor() {
        return waitingFor;
    }

    /** True while somebody still has to approve this. */
    public boolean isWaitingForApproval() {
        return waitingFor != null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
