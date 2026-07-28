package hsts.common.enums;

/**
 * The four kinds of user in the system.
 *
 * <p>Roles are <em>seeded</em>, never created in HSTS. System description §8 says
 * user details and permissions are managed by a separate external system and are
 * merely available in our database - which is why there is no sign-up screen and
 * no user-administration screen anywhere in this project.</p>
 */
public enum UserRole {

    /** Writes questions and exams, releases them, marks them. */
    TEACHER("Teacher"),

    /** A teacher with one extra duty: approving exams for the subject she coordinates. */
    COORDINATOR("Subject coordinator"),

    /** Sits exams and sees only her own results. */
    STUDENT("Student"),

    /** Reads everything, changes nothing, pulls reports. */
    PRINCIPAL("Principal");

    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
