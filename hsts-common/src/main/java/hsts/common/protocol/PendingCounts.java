package hsts.common.protocol;

import java.io.Serializable;

/**
 * How many things are waiting for this user, for the badges on her menu.
 *
 * <p>One reply carries every number the menu can show, because the alternative is
 * four requests each time a menu opens - and four chances for the badges to
 * disagree with each other about the moment they describe.</p>
 *
 * <p>Every count is a count of things <b>she</b> has still to do. Anything waiting
 * on somebody else - an exam of hers sitting with the coordinator, say - is not
 * here. That is what the status wording is for; a badge is a queue, not a
 * notice board.</p>
 *
 * <p>Not every field applies to every role, and the ones that do not are simply
 * zero: the server fills in what the user's role can act on, and the menu only
 * looks at the entries it has actually built.</p>
 */
public class PendingCounts implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int examsToApprove;
    private final int papersToApprove;
    private final int examsToSit;
    private final int newResults;

    public PendingCounts(int examsToApprove, int papersToApprove,
                         int examsToSit, int newResults) {
        this.examsToApprove  = examsToApprove;
        this.papersToApprove = papersToApprove;
        this.examsToSit      = examsToSit;
        this.newResults      = newResults;
    }

    /** Coordinator: exams in her subject waiting for her decision. */
    public int getExamsToApprove() {
        return examsToApprove;
    }

    /** Teacher: papers handed in on her sittings that she has not approved yet. */
    public int getPapersToApprove() {
        return papersToApprove;
    }

    /** Student: sittings open right now that she may still sit. */
    public int getExamsToSit() {
        return examsToSit;
    }

    /** Student: marks published since she last opened her results. */
    public int getNewResults() {
        return newResults;
    }

    public int total() {
        return examsToApprove + papersToApprove + examsToSit + newResults;
    }

    @Override
    public String toString() {
        return "pending: examsToApprove=" + examsToApprove
             + " papersToApprove=" + papersToApprove
             + " examsToSit=" + examsToSit
             + " newResults=" + newResults;
    }
}
