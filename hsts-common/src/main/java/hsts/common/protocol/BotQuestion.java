package hsts.common.protocol;

import java.io.Serializable;

/**
 * A student asking her course bot something (SUC-14).
 *
 * <p>The course, not the bot id: which bot belongs to a course is the server's
 * business, and requirement 70 - she must be enrolled - is a question about the
 * course rather than about the bot.</p>
 */
public class BotQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String courseCode;
    private final String question;

    public BotQuestion(String courseCode, String question) {
        this.courseCode = courseCode;
        this.question = question;
    }

    public String getCourseCode() { return courseCode; }
    public String getQuestion()   { return question; }
}
