package hsts.common.protocol;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * What a teacher may know about how her bot is being used.
 *
 * <p>Requirement 75: <i>"המורה תוכל לצפות במידע כללי על השימוש בבוט (מספר שאלות,
 * שאלות נפוצות), ללא זיהוי משתמשים"</i> - how many questions, which are common,
 * <b>with no identities</b>.</p>
 *
 * <p>There is deliberately no student name anywhere in this class, and no id
 * either. {@link #distinctStudents} is a count, which tells her whether the bot is
 * used by one girl or twenty without telling her which. Everything here is built
 * on the server from anonymised rows, so a name never reaches the wire at all.</p>
 */
public class BotUsage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String botName;
    private final String courseName;
    private final int totalQuestions;
    private final int distinctStudents;

    /** The most-asked wordings, commonest first. Never who asked them. */
    private final List<CommonQuestion> commonQuestions;

    /** Recent questions and answers, with no names on them. */
    private final List<hsts.common.entity.BotConversation> recent;

    public BotUsage(String botName, String courseName, int totalQuestions,
                    int distinctStudents, List<CommonQuestion> commonQuestions,
                    List<hsts.common.entity.BotConversation> recent) {
        this.botName = botName;
        this.courseName = courseName;
        this.totalQuestions = totalQuestions;
        this.distinctStudents = distinctStudents;
        this.commonQuestions = (commonQuestions == null)
                ? new ArrayList<>() : new ArrayList<>(commonQuestions);
        this.recent = (recent == null) ? new ArrayList<>() : new ArrayList<>(recent);
    }

    public String getBotName()         { return botName; }
    public String getCourseName()      { return courseName; }
    public int getTotalQuestions()     { return totalQuestions; }
    public int getDistinctStudents()   { return distinctStudents; }
    public List<CommonQuestion> getCommonQuestions() { return commonQuestions; }
    public List<hsts.common.entity.BotConversation> getRecent() { return recent; }

    /** One repeated question and how often it was asked. */
    public static class CommonQuestion implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String question;
        private final int timesAsked;

        public CommonQuestion(String question, int timesAsked) {
            this.question = question;
            this.timesAsked = timesAsked;
        }

        public String getQuestion() { return question; }
        public int getTimesAsked()  { return timesAsked; }
    }
}
