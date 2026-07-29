package hsts.common.protocol;

import java.io.Serializable;

/**
 * One thing a report can be run about - a teacher, a course, or a student.
 *
 * <p>The screen has to offer a choice before it can ask for a report, and what to
 * offer depends entirely on the report type. Rather than the screen knowing that
 * "teacher reports need a list of teachers", each {@code ReportStrategy} supplies
 * its own list - so a new report type brings its own chooser with it and the
 * screen does not change. That is requirement 64 applying to the client as well
 * as the server.</p>
 */
public class ReportSubject implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What the strategy needs back: a user id, or a course code. */
    private final String key;

    private final String name;

    /** A second line, e.g. how many exams there are to compare. */
    private final String detail;

    public ReportSubject(String key, String name, String detail) {
        this.key = key;
        this.name = name;
        this.detail = detail;
    }

    public String getKey()    { return key; }
    public String getName()   { return name; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return name;
    }
}
