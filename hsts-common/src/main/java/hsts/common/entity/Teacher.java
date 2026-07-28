package hsts.common.entity;

import hsts.common.enums.UserRole;

import java.util.ArrayList;
import java.util.List;

/**
 * A teacher: writes questions and exams, releases them to a class, marks them.
 *
 * <p>Requirements 14 and 20 restrict her to the courses she actually teaches,
 * which is what {@link #getTaughtCourseCodes()} is for.</p>
 */
public class Teacher extends User {

    private static final long serialVersionUID = 1L;

    /** Course codes this teacher teaches. Requirements 14 and 20 depend on it. */
    protected List<String> taughtCourseCodes = new ArrayList<>();

    public Teacher() {
        super();
    }

    public Teacher(String userId, String username, String fullName) {
        super(userId, username, fullName, UserRole.TEACHER);
    }

    protected Teacher(String userId, String username, String fullName, UserRole role) {
        super(userId, username, fullName, role);
    }

    public List<String> getTaughtCourseCodes() {
        return taughtCourseCodes;
    }

    public void setTaughtCourseCodes(List<String> codes) {
        this.taughtCourseCodes = (codes == null) ? new ArrayList<>() : codes;
    }

    public boolean teaches(String courseCode) {
        return taughtCourseCodes.contains(courseCode);
    }

    @Override
    public boolean checkPermission(String action) {
        return switch (action) {
            case "MANAGE_QUESTIONS", "BUILD_EXAM", "RELEASE_EXAM",
                 "GRADE_EXAM", "MANAGE_BOT", "VIEW_OWN_REPORTS" -> true;
            default -> false;
        };
    }
}
