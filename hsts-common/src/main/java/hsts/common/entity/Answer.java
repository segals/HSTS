package hsts.common.entity;

import java.io.Serializable;

/**
 * One of the four possible answers to a question.
 *
 * <p>System description §3.1: a question has four answers and exactly one of
 * them is marked correct. Both halves of that rule are enforced in
 * {@code QuestionController} before anything is saved - the database can hold
 * the shape, but it cannot express "exactly one of these four is true".</p>
 *
 * <p>{@link #answerNo} is 1 to 4 and fixes the display order, so a question
 * always looks the same to every student and in every exam.</p>
 */
public class Answer implements Serializable {

    private static final long serialVersionUID = 1L;

    private int answerNo;          // 1..4
    private String text;
    private boolean correct;

    public Answer() {
    }

    public Answer(int answerNo, String text, boolean correct) {
        this.answerNo = answerNo;
        this.text = text;
        this.correct = correct;
    }

    public int getAnswerNo()    { return answerNo; }
    public String getText()     { return text; }
    public boolean isCorrect()  { return correct; }

    public void setAnswerNo(int answerNo) { this.answerNo = answerNo; }
    public void setText(String text)      { this.text = text; }
    public void setCorrect(boolean correct) { this.correct = correct; }

    @Override
    public String toString() {
        return answerNo + ". " + text + (correct ? "  [correct]" : "");
    }
}
