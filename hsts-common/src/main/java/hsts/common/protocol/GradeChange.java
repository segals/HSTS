package hsts.common.protocol;

import java.io.Serializable;

/**
 * A change to a mark - by hand for one student, or as a factor for a whole sitting.
 *
 * <p>One class for both because they carry the same three things: what to change,
 * by how much or to what, and why.</p>
 */
public class GradeChange implements Serializable {

    private static final long serialVersionUID = 1L;

    /** For a single change: which paper. Zero when this is a factor. */
    private final int submissionId;

    /** For a factor: which sitting. Zero when this is a single change. */
    private final int executionId;

    /** The new mark, or the number of points to add. */
    private final int value;

    /** Compulsory for a single change (requirement 52). */
    private final String explanation;

    private GradeChange(int submissionId, int executionId, int value, String explanation) {
        this.submissionId = submissionId;
        this.executionId = executionId;
        this.value = value;
        this.explanation = explanation;
    }

    /** One student, a new mark, and why. */
    public static GradeChange forOne(int submissionId, int newGrade, String explanation) {
        return new GradeChange(submissionId, 0, newGrade, explanation);
    }

    /** A whole sitting, a number of points to add. */
    public static GradeChange factor(int executionId, int points) {
        return new GradeChange(0, executionId, points, null);
    }

    public int getSubmissionId()   { return submissionId; }
    public int getExecutionId()    { return executionId; }
    public int getValue()          { return value; }
    public String getExplanation() { return explanation; }
}
