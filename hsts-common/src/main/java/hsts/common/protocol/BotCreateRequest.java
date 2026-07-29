package hsts.common.protocol;

import java.io.Serializable;

/** Creating a course bot: which course, and what it is called (requirement 66). */
public class BotCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String courseCode;
    private final String name;

    public BotCreateRequest(String courseCode, String name) {
        this.courseCode = courseCode;
        this.name = name;
    }

    public String getCourseCode() { return courseCode; }
    public String getName()       { return name; }
}
