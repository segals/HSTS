package hsts.common.protocol;

import java.io.Serializable;

/**
 * Which results to report on.
 *
 * <p>An exam is written once and handed out many times, so "the results of this
 * exam" is ambiguous: it can mean one sitting, or every sitting together. Both are
 * wanted - מתווה scenario 10 shows a class's marks, while requirement 59 asks for
 * an analysis of the exam as a whole - so the question is asked explicitly rather
 * than guessed.</p>
 *
 * <p>{@link #getExecutionId()} of {@code null} means <b>every sitting together</b>.</p>
 */
public class ResultsQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String examId;
    private final int version;
    private final Integer executionId;

    public ResultsQuery(String examId, int version, Integer executionId) {
        this.examId = examId;
        this.version = version;
        this.executionId = executionId;
    }

    /** One sitting of one exam. */
    public static ResultsQuery sitting(String examId, int version, int executionId) {
        return new ResultsQuery(examId, version, executionId);
    }

    /** Every sitting of one exam, together. */
    public static ResultsQuery wholeExam(String examId, int version) {
        return new ResultsQuery(examId, version, null);
    }

    public String getExamId()      { return examId; }
    public int getVersion()        { return version; }
    public Integer getExecutionId() { return executionId; }

    /** True when this asks about the exam as a whole rather than one sitting. */
    public boolean isWholeExam() {
        return executionId == null;
    }
}
