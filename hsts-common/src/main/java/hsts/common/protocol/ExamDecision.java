package hsts.common.protocol;

import java.io.Serializable;

/**
 * A coordinator's decision on one exam version.
 *
 * <p>The version matters: an exam may have been edited since she looked at it,
 * and approving "exam 010101" without saying which version could approve a paper
 * nobody has read. Requirement 34 speaks of "לכל גרסת בחינה מאושרת" - each
 * approved exam <em>version</em>.</p>
 */
public class ExamDecision implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String examId;
    private final int version;

    /** Mandatory on a rejection (requirement 33), unused on an approval. */
    private final String reason;

    public ExamDecision(String examId, int version, String reason) {
        this.examId = examId;
        this.version = version;
        this.reason = reason;
    }

    public String getExamId() { return examId; }
    public int getVersion()   { return version; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return examId + " v" + version;
    }
}
