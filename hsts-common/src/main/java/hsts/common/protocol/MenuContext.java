package hsts.common.protocol;

import hsts.common.entity.Course;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Who the signed-in user is, in words rather than codes, for the main menu.
 *
 * <h2>Why the menu cannot work this out for itself</h2>
 *
 * <p>The {@code User} object carries course <em>codes</em> - {@code [02]} - and the
 * menu was printing exactly that: "teaches course(s): 02". True, and no use to
 * anybody reading it. Names live in the {@code course} table.</p>
 *
 * <p>It cannot borrow the question bank's course list either. Since requirement 19
 * that list is deliberately <b>wider</b> than the courses a coordinator teaches -
 * it includes every course in the subject she coordinates, because she may edit
 * their questions. Printing that as "teaches" would be plainly false.</p>
 *
 * <p>So this carries the two lists separately, each meaning exactly what it says,
 * and the subject a coordinator runs by name.</p>
 */
public class MenuContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<Course> taughtCourses;
    private final List<Course> enrolledCourses;
    private final String coordinatedSubjectCode;
    private final String coordinatedSubjectName;

    public MenuContext(List<Course> taughtCourses, List<Course> enrolledCourses,
                       String coordinatedSubjectCode, String coordinatedSubjectName) {
        // Copies: a view returned by List.subList is not serialisable, and that has
        // already broken this project once.
        this.taughtCourses = (taughtCourses == null)
                ? new ArrayList<>() : new ArrayList<>(taughtCourses);
        this.enrolledCourses = (enrolledCourses == null)
                ? new ArrayList<>() : new ArrayList<>(enrolledCourses);
        this.coordinatedSubjectCode = coordinatedSubjectCode;
        this.coordinatedSubjectName = coordinatedSubjectName;
    }

    /** Courses she actually teaches. Empty for a student, and for a coordinator
     *  who has been given no classes of her own. */
    public List<Course> getTaughtCourses() {
        return taughtCourses;
    }

    /** Courses she studies. Empty for anybody who is not a student. */
    public List<Course> getEnrolledCourses() {
        return enrolledCourses;
    }

    public String getCoordinatedSubjectCode() {
        return coordinatedSubjectCode;
    }

    /** The subject she coordinates, by name, or null if she coordinates none. */
    public String getCoordinatedSubjectName() {
        return coordinatedSubjectName;
    }
}
