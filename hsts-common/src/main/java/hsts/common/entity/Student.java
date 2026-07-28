package hsts.common.entity;

import hsts.common.enums.UserRole;

import java.util.ArrayList;
import java.util.List;

/**
 * A student: sits exams, sees her own results, uses the course bot.
 *
 * <p>Requirement 21 restricts exam access to the courses she is enrolled in, and
 * requirement 70 does the same for the bot - hence
 * {@link #getEnrolledCourseCodes()}.</p>
 *
 * <p>Requirement 57 is the sharpest rule attached to this class: a student may
 * see only her own results, never another student's. That is enforced on the
 * server, in every query, by filtering on her own {@code userId}.</p>
 */
public class Student extends User {

    private static final long serialVersionUID = 1L;

    private List<String> enrolledCourseCodes = new ArrayList<>();

    public Student() {
        super();
    }

    public Student(String userId, String username, String fullName) {
        super(userId, username, fullName, UserRole.STUDENT);
    }

    public List<String> getEnrolledCourseCodes() {
        return enrolledCourseCodes;
    }

    public void setEnrolledCourseCodes(List<String> codes) {
        this.enrolledCourseCodes = (codes == null) ? new ArrayList<>() : codes;
    }

    public boolean isEnrolledIn(String courseCode) {
        return enrolledCourseCodes.contains(courseCode);
    }

    @Override
    public boolean checkPermission(String action) {
        return switch (action) {
            case "TAKE_EXAM", "VIEW_OWN_RESULTS", "USE_BOT", "VIEW_OWN_BOT_HISTORY" -> true;
            default -> false;
        };
    }
}
