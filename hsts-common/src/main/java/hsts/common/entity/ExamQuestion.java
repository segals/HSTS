package hsts.common.entity;

import java.io.Serializable;

/**
 * One question's place in one exam: which question, worth how much, in what order.
 *
 * <p>This is the association class from the submitted class diagram - the link
 * between {@code Exam} and {@code Question} carries information of its own, so it
 * has to be a class rather than a plain reference.</p>
 *
 * <h2>The important field</h2>
 *
 * <p>{@link #questionVersion} is what makes exam history work. An exam records
 * not just <em>which</em> question but <em>which version</em> of it. When the
 * question is later edited into version 2, this exam still points at version 1
 * and still shows exactly what the students saw when they sat it.</p>
 *
 * <p>Without this field, editing a question would silently rewrite every exam
 * that ever used it, including papers already marked.</p>
 */
public class ExamQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    private String questionId;
    private int questionVersion;
    private int points;
    private int order;

    /** The question itself, filled in for display. Not stored in this table. */
    private Question question;

    public ExamQuestion() {
    }

    public ExamQuestion(String questionId, int questionVersion, int points, int order) {
        this.questionId = questionId;
        this.questionVersion = questionVersion;
        this.points = points;
        this.order = order;
    }

    public String getQuestionId()    { return questionId; }
    public int getQuestionVersion()  { return questionVersion; }
    public int getPoints()           { return points; }
    public int getOrder()            { return order; }
    public Question getQuestion()    { return question; }

    public void setQuestionId(String questionId)   { this.questionId = questionId; }
    public void setQuestionVersion(int version)    { this.questionVersion = version; }
    public void setPoints(int points)              { this.points = points; }
    public void setOrder(int order)                { this.order = order; }
    public void setQuestion(Question question)     { this.question = question; }

    @Override
    public String toString() {
        String text = (question == null) ? questionId : question.getText();
        if (text != null && text.length() > 60) {
            text = text.substring(0, 57) + "...";
        }
        return points + " pts   " + text;
    }
}
