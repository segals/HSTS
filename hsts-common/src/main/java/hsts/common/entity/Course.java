package hsts.common.entity;

import java.io.Serializable;

/**
 * A course, such as Plane Geometry, belonging to one subject.
 *
 * <p>A course may be taught by more than one teacher (system description §2),
 * which matters for the bot: if a course already has one, a second teacher may
 * add knowledge sources to it (requirement 67).</p>
 *
 * <p>The 2-digit code appears inside both generated identifiers - digits 3-4 of
 * a 5-digit question number and digits 2-3 of a 6-digit exam number.</p>
 */
public class Course implements Serializable {

    private static final long serialVersionUID = 1L;

    private String courseCode;    // exactly 2 digits
    private String name;
    private String subjectCode;

    /** Filled in for display; not every query needs it. */
    private String subjectName;

    public Course() {
    }

    public Course(String courseCode, String name, String subjectCode) {
        this.courseCode = courseCode;
        this.name = name;
        this.subjectCode = subjectCode;
    }

    public String getCourseCode()  { return courseCode; }
    public String getName()        { return name; }
    public String getSubjectCode() { return subjectCode; }
    public String getSubjectName() { return subjectName; }

    public void setCourseCode(String courseCode)   { this.courseCode = courseCode; }
    public void setName(String name)               { this.name = name; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * "Plane Geometry (01)".
     *
     * <p>Both, everywhere. The two digits are inside the id of every question and
     * exam in the course, so a teacher does need to know which is which; and a
     * list of courses reading 01 to 08 tells her nothing at all. This is what a
     * combo box shows, because {@code toString} is what a combo box shows.</p>
     */
    public String describe() {
        return (name == null || name.isBlank())
                ? courseCode : name + " (" + courseCode + ")";
    }
}
