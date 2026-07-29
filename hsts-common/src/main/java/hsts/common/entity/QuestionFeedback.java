package hsts.common.entity;

import java.io.Serializable;

/**
 * How one student did on one question, and anything the teacher wrote about it.
 *
 * <p>Requirement 53: once the mark is approved she sees her paper with the wrong
 * questions marked and the teacher's optional comments beside them.</p>
 *
 * <p>The question <em>version</em> is carried through, so a marked paper shows the
 * wording she actually answered even if the question has been edited since.</p>
 */
public class QuestionFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    private String questionId;
    private int questionVersion;

    /** True when her answer was wrong, or she left it blank. */
    private boolean wrong;

    /** Optional note from the teacher (requirement 51). */
    private String comment;

    public QuestionFeedback() {
    }

    public QuestionFeedback(String questionId, int questionVersion, boolean wrong, String comment) {
        this.questionId = questionId;
        this.questionVersion = questionVersion;
        this.wrong = wrong;
        this.comment = comment;
    }

    public String getQuestionId()   { return questionId; }
    public int getQuestionVersion() { return questionVersion; }
    public boolean isWrong()        { return wrong; }
    public String getComment()      { return comment; }

    public void setQuestionId(String id)        { this.questionId = id; }
    public void setQuestionVersion(int version) { this.questionVersion = version; }
    public void setWrong(boolean wrong)         { this.wrong = wrong; }
    public void setComment(String comment)      { this.comment = comment; }

    public boolean hasComment() {
        return comment != null && !comment.isBlank();
    }
}
