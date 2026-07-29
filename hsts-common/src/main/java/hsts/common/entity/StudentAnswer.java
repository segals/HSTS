package hsts.common.entity;

import java.io.Serializable;

/**
 * What one student chose for one question.
 *
 * <p>The question <em>version</em> is recorded alongside the id, matching the
 * version the exam pinned. Without it, a later edit of the question would make
 * her stored answer number point at a different set of options - and her marked
 * paper would show her answering something she never saw.</p>
 *
 * <p>{@link #selectedAnswerNo} may be null: a question she never answered is a
 * real state, and requirement 45 says whatever she had entered is saved when the
 * time runs out. Acceptance test 2.12 then marks the blanks wrong.</p>
 */
public class StudentAnswer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String questionId;
    private int questionVersion;

    /** 1 to 4, or null when she left it blank. */
    private Integer selectedAnswerNo;

    public StudentAnswer() {
    }

    public StudentAnswer(String questionId, int questionVersion, Integer selectedAnswerNo) {
        this.questionId = questionId;
        this.questionVersion = questionVersion;
        this.selectedAnswerNo = selectedAnswerNo;
    }

    public String getQuestionId()        { return questionId; }
    public int getQuestionVersion()      { return questionVersion; }
    public Integer getSelectedAnswerNo() { return selectedAnswerNo; }

    public void setQuestionId(String id)          { this.questionId = id; }
    public void setQuestionVersion(int version)   { this.questionVersion = version; }
    public void setSelectedAnswerNo(Integer no)   { this.selectedAnswerNo = no; }

    public boolean isAnswered() {
        return selectedAnswerNo != null;
    }

    @Override
    public String toString() {
        return questionId + " v" + questionVersion + " -> "
             + (selectedAnswerNo == null ? "blank" : selectedAnswerNo);
    }
}
