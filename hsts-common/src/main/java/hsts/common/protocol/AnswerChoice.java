package hsts.common.protocol;

import java.io.Serializable;

/**
 * One answer a student has just chosen.
 *
 * <p>Sent as she picks it rather than all together on submit. Requirement 45
 * keeps whatever she had entered when her time runs out, and a client that dies
 * mid-exam must not take her work with it.</p>
 */
public class AnswerChoice implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int submissionId;
    private final String questionId;
    private final int questionVersion;

    /** 1 to 4, or null to clear it again. */
    private final Integer selectedAnswerNo;

    public AnswerChoice(int submissionId, String questionId, int questionVersion,
                        Integer selectedAnswerNo) {
        this.submissionId = submissionId;
        this.questionId = questionId;
        this.questionVersion = questionVersion;
        this.selectedAnswerNo = selectedAnswerNo;
    }

    public int getSubmissionId()         { return submissionId; }
    public String getQuestionId()        { return questionId; }
    public int getQuestionVersion()      { return questionVersion; }
    public Integer getSelectedAnswerNo() { return selectedAnswerNo; }
}
