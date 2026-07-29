package hsts.common.protocol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * What the teacher asked for when building an exam.
 *
 * <p>One class covers both ways of building, because from the server's point of
 * view they are the same request with a different rule for choosing questions -
 * which is exactly what the <b>Strategy</b> pattern is for. The controller picks
 * a strategy from {@link #isAutomatic()} and then does not care which one it got.</p>
 */
public class ExamBuildCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String courseCode;
    private final boolean automatic;

    /** Manual building: the exact questions the teacher picked, in her order. */
    private final List<String> manualQuestionIds;

    /** Automatic building: how many of what. */
    private final List<QuestionQuota> quotas;

    /**
     * Copies both lists rather than keeping the caller's.
     *
     * <p>This is not tidiness - it is what keeps the object sendable. Several
     * common list operations return a <em>view</em> rather than a real list, and
     * those views are not serializable. {@code List.subList(0, 4)} is the usual
     * one: perfectly ordinary Java, and it makes the whole message fail at
     * {@code sendToServer} with {@code NotSerializableException:
     * java.util.ArrayList$SubList} - an error that points at the network layer
     * and says nothing about the actual cause.</p>
     *
     * <p>Copying into a plain {@code ArrayList} means no caller can build an
     * unsendable request by accident.</p>
     */
    private ExamBuildCriteria(String courseCode, boolean automatic,
                              List<String> manualQuestionIds, List<QuestionQuota> quotas) {
        this.courseCode = courseCode;
        this.automatic = automatic;
        this.manualQuestionIds = (manualQuestionIds == null)
                ? new ArrayList<>() : new ArrayList<>(manualQuestionIds);
        this.quotas = (quotas == null)
                ? new ArrayList<>() : new ArrayList<>(quotas);
    }

    public static ExamBuildCriteria manual(String courseCode, List<String> questionIds) {
        return new ExamBuildCriteria(courseCode, false, questionIds, null);
    }

    public static ExamBuildCriteria automatic(String courseCode, List<QuestionQuota> quotas) {
        return new ExamBuildCriteria(courseCode, true, null, quotas);
    }

    public String getCourseCode()               { return courseCode; }
    public boolean isAutomatic()                { return automatic; }
    public List<String> getManualQuestionIds()  { return manualQuestionIds; }
    public List<QuestionQuota> getQuotas()      { return quotas; }

    /** Total questions requested - the sum of the quota lines. */
    public int getRequestedTotal() {
        if (!automatic) {
            return manualQuestionIds.size();
        }
        int total = 0;
        for (QuestionQuota quota : quotas) {
            total += quota.getCount();
        }
        return total;
    }
}
