package hsts.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One question a student asked and the answer she was given.
 *
 * <p>Requirement 73 says both are kept. Requirement 74 gives the student her own
 * history - the question, the answer, and when she asked. Requirement 75 gives the
 * teacher general usage <b>with no identities</b>, which is what {@link #anonymise}
 * is for: the same object serves both, and the teacher's copy has the name taken
 * out of it on the server before it is ever sent.</p>
 */
public class BotConversation implements Serializable {

    private static final long serialVersionUID = 1L;

    private int convId;
    private int botId;
    private String courseName;

    /** Null on anything a teacher sees - requirement 75. */
    private String studentId;
    private String studentName;

    private String question;
    private String answer;
    private LocalDateTime askedAt;

    public BotConversation() {
    }

    public int getConvId()             { return convId; }
    public int getBotId()              { return botId; }
    public String getCourseName()      { return courseName; }
    public String getStudentId()       { return studentId; }
    public String getStudentName()     { return studentName; }
    public String getQuestion()        { return question; }
    public String getAnswer()          { return answer; }
    public LocalDateTime getAskedAt()  { return askedAt; }

    public void setConvId(int id)                { this.convId = id; }
    public void setBotId(int botId)              { this.botId = botId; }
    public void setCourseName(String name)       { this.courseName = name; }
    public void setStudentId(String id)          { this.studentId = id; }
    public void setStudentName(String name)      { this.studentName = name; }
    public void setQuestion(String question)     { this.question = question; }
    public void setAnswer(String answer)         { this.answer = answer; }
    public void setAskedAt(LocalDateTime at)     { this.askedAt = at; }

    /**
     * Strips who asked. Requirement 75.
     *
     * <p>Called on the <b>server</b>, before the object goes onto the wire. Doing
     * it on the client would mean the name had already been sent, and "the screen
     * does not draw it" is not the same as "the teacher cannot have it".</p>
     */
    public void anonymise() {
        this.studentId = null;
        this.studentName = null;
    }
}
