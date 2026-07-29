package hsts.common.entity;

import hsts.common.enums.DifficultyLevel;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One multiple-choice question in the bank.
 *
 * <h2>The identifier</h2>
 *
 * <p>Five digits, fixed by the system description §3.1 table: digits 0-2 are the
 * question number and digits 3-4 are the course code. Question 7 of course 05 is
 * therefore {@code 00705}. This is the only identifier ever shown on screen.</p>
 *
 * <h2>Versioning</h2>
 *
 * <p>Editing a question never overwrites it. A new row is written with
 * {@code version + 1} and {@code isCurrent} moves to it; the old row stays
 * untouched forever. מתווה scenario 2 item 2 requires exactly that - "השאלה
 * בגרסה הקודמת נשארת במאגר השאלות".</p>
 *
 * <p>The five-digit number has no spare digit for a version, and the format is
 * fixed by the documents, so the version lives beside it as a separate field.
 * Together they form the database key. An exam records both, which is why an
 * exam sat in March still shows the March wording of its questions.</p>
 *
 * <h2>Two fields that are easy to get wrong</h2>
 *
 * <p><b>{@code image} is {@code byte[]}, not a JavaFX {@code Image}.</b> The
 * submitted class diagram said {@code Image}; that cannot work here. A JavaFX
 * {@code Image} is not serializable, and every question travels between the two
 * laptops. The bytes are turned into a picture only inside the screen that shows
 * them. Storing a file path instead would be worse still - a path on the
 * teacher's laptop means nothing on the student's.</p>
 *
 * <p><b>{@code topic} is free text.</b> Automatic exam building filters on topic
 * and difficulty (requirement 28). The submitted requirements table never gave a
 * question a topic - only a subject tag, which is redundant because a question
 * already belongs to one course and a course to one subject. The field was added
 * during planning, and the screen offers the topics already used in that course
 * through a combo box so that typing variations do not split one topic in two.</p>
 */
public class Question implements Serializable {

    private static final long serialVersionUID = 1L;

    private String questionId;              // 5 digits: 3 question number + 2 course code
    private int version = 1;

    private String courseCode;
    private String text;
    private String instructions;
    private String topic;
    private DifficultyLevel difficulty;

    /** Optional picture, stored as raw bytes. Null when the question has none. */
    private byte[] image;

    private boolean current = true;
    private boolean deleted = false;

    private String authorId;
    private String authorName;              // filled in for display only
    private LocalDateTime createdAt;

    /** Exactly four, in display order. */
    private List<Answer> answers = new ArrayList<>();

    public Question() {
    }

    public String getQuestionId()          { return questionId; }
    public int getVersion()                { return version; }
    public String getCourseCode()          { return courseCode; }
    public String getText()                { return text; }
    public String getInstructions()        { return instructions; }
    public String getTopic()               { return topic; }
    public DifficultyLevel getDifficulty() { return difficulty; }
    public byte[] getImage()               { return image; }
    public boolean isCurrent()             { return current; }
    public boolean isDeleted()             { return deleted; }
    public String getAuthorId()            { return authorId; }
    public String getAuthorName()          { return authorName; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public List<Answer> getAnswers()       { return answers; }

    public void setQuestionId(String questionId)   { this.questionId = questionId; }
    public void setVersion(int version)            { this.version = version; }
    public void setCourseCode(String courseCode)   { this.courseCode = courseCode; }
    public void setText(String text)               { this.text = text; }
    public void setInstructions(String s)          { this.instructions = s; }
    public void setTopic(String topic)             { this.topic = topic; }
    public void setDifficulty(DifficultyLevel d)   { this.difficulty = d; }
    public void setImage(byte[] image)             { this.image = image; }
    public void setCurrent(boolean current)        { this.current = current; }
    public void setDeleted(boolean deleted)        { this.deleted = deleted; }
    public void setAuthorId(String authorId)       { this.authorId = authorId; }
    public void setAuthorName(String authorName)   { this.authorName = authorName; }
    public void setCreatedAt(LocalDateTime t)      { this.createdAt = t; }

    /**
     * Copies the list rather than keeping the caller's.
     *
     * <p>A {@code Question} crosses the network, so its fields must be
     * serializable. List views such as {@code subList(...)} and {@code List.of(...)}
     * are not, and one stored here would make the question unsendable.</p>
     */
    public void setAnswers(List<Answer> answers) {
        this.answers = (answers == null) ? new ArrayList<>() : new ArrayList<>(answers);
    }

    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    /** The one answer marked correct, or null if the data is malformed. */
    public Answer getCorrectAnswer() {
        for (Answer answer : answers) {
            if (answer.isCorrect()) {
                return answer;
            }
        }
        return null;
    }

    /** The question number part of the id - digits 0 to 2. */
    public String getQuestionNumberPart() {
        return (questionId == null || questionId.length() != 5) ? "" : questionId.substring(0, 3);
    }

    /** The course code part of the id - digits 3 and 4. */
    public String getCourseCodePart() {
        return (questionId == null || questionId.length() != 5) ? "" : questionId.substring(3, 5);
    }

    /** Short label for tables and lists. */
    public String getSummary() {
        String t = (text == null) ? "" : text.replace('\n', ' ');
        if (t.length() > 70) {
            t = t.substring(0, 67) + "...";
        }
        return questionId + " (v" + version + ")  " + t;
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
