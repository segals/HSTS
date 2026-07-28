package hsts.common.protocol;

import java.io.Serializable;

/**
 * Points at one question, optionally at one exact version of it.
 *
 * <p>Because questions are versioned, "which question" is two pieces of
 * information, not one. A version of {@code 0} means "whichever version is
 * current".</p>
 */
public class QuestionRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Version value meaning "the current one, whatever number it has reached". */
    public static final int CURRENT = 0;

    private final String questionId;
    private final int version;

    public QuestionRef(String questionId, int version) {
        this.questionId = questionId;
        this.version = version;
    }

    /** Points at the current version. */
    public QuestionRef(String questionId) {
        this(questionId, CURRENT);
    }

    public String getQuestionId() { return questionId; }
    public int getVersion()       { return version; }

    @Override
    public String toString() {
        return questionId + (version == CURRENT ? " (current)" : " v" + version);
    }
}
