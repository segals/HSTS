package hsts.common.protocol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the teacher settled on for one paper, published in one action.
 *
 * <h2>Why one request and not four</h2>
 *
 * <p>The marking screen used to have a button to save the mark, a button to save
 * the overall comment, a button beside every single question, and a button to
 * approve - so a ten-question paper carried thirteen buttons, and a teacher could
 * publish a mark while a comment she had typed sat unsaved on screen.</p>
 *
 * <p>Now she fills the paper in and presses <b>Approve and publish</b> once. This
 * object is what that press sends: the mark, the reason for it if she moved it,
 * the overall comment, and a comment for each question she wrote one against.</p>
 *
 * <h2>The compulsory reason survives</h2>
 *
 * <p>Requirement 52 and acceptance test 3.4 make an explanation compulsory when a
 * mark is changed by hand. That is unchanged - the server checks it here, and
 * refuses the <em>whole</em> request before writing anything, so a mark is never
 * published with the reason missing.</p>
 */
public class PublishRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int submissionId;

    /** What the mark box reads. Equal to the stored mark when she did not touch it. */
    private final Integer finalGrade;

    /** Needed only when {@link #finalGrade} differs from the stored mark. */
    private final String reason;

    /** About the paper as a whole. Null or blank clears it. */
    private final String generalComment;

    /** One entry per question she wrote a comment against. Never null. */
    private final List<CommentRequest> questionComments;

    public PublishRequest(int submissionId, Integer finalGrade, String reason,
                          String generalComment, List<CommentRequest> questionComments) {
        this.submissionId = submissionId;
        this.finalGrade = finalGrade;
        this.reason = reason;
        this.generalComment = generalComment;
        // A copy, not the caller's list. A view returned by List.subList is not
        // serialisable, and that has already broken this project once.
        this.questionComments = (questionComments == null)
                ? new ArrayList<>() : new ArrayList<>(questionComments);
    }

    public int getSubmissionId()   { return submissionId; }
    public Integer getFinalGrade() { return finalGrade; }
    public String getReason()      { return reason; }
    public String getGeneralComment() { return generalComment; }
    public List<CommentRequest> getQuestionComments() { return questionComments; }
}
