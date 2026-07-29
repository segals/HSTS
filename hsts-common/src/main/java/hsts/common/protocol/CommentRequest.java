package hsts.common.protocol;

import java.io.Serializable;

/**
 * A comment the teacher is leaving on a paper (requirement 51).
 *
 * <p>{@link #questionId} is null when the comment is about the paper as a whole
 * rather than one question.</p>
 */
public class CommentRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int submissionId;
    private final String questionId;
    private final int questionVersion;
    private final String comment;

    public CommentRequest(int submissionId, String questionId, int questionVersion,
                          String comment) {
        this.submissionId = submissionId;
        this.questionId = questionId;
        this.questionVersion = questionVersion;
        this.comment = comment;
    }

    public static CommentRequest general(int submissionId, String comment) {
        return new CommentRequest(submissionId, null, 0, comment);
    }

    public int getSubmissionId()    { return submissionId; }
    public String getQuestionId()   { return questionId; }
    public int getQuestionVersion() { return questionVersion; }
    public String getComment()      { return comment; }
}
