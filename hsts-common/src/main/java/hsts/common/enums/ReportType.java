package hsts.common.enums;

/**
 * The comparisons מתווה scenario 12 asks for.
 *
 * <p><i>"ניתן לראות ממוצע, חציון והתפלגות עשרונית של בחינות ולהשוות בין: בחינות
 * שונות של אותה מורה, בחינות שונות של אותו קורס, בחינות שונות של אותה תלמידה"</i>
 * - the same three figures, compared across a teacher's exams, a course's exams,
 * or one student's exams. Requirement 63 says the same thing.</p>
 *
 * <p>Each value has exactly one {@code ReportStrategy} behind it, found by
 * {@code ReportFactory}. <b>Requirement 64</b> - that a new report should take
 * minimal work - is what this arrangement exists for: adding a fourth report means
 * adding one value here and one new strategy class, and touching nothing that
 * already works.</p>
 */
public enum ReportType {

    /** Requirement 59 and 63: every exam one teacher wrote, side by side. */
    BY_TEACHER("A teacher's exams",
               "Every exam one teacher wrote, compared with each other.",
               "Teacher"),

    /** Requirement 63: every exam set in one course. */
    BY_COURSE("A course's exams",
              "Every exam written for one course, compared with each other.",
              "Course"),

    /** Requirement 63: how one student did across the exams she sat. */
    BY_STUDENT("One student's exams",
               "Every exam one student sat, with her mark against the class.",
               "Student");

    private final String displayName;
    private final String description;

    /** What the thing being compared is called, for the chooser on screen. */
    private final String subjectNoun;

    ReportType(String displayName, String description, String subjectNoun) {
        this.displayName = displayName;
        this.description = description;
        this.subjectNoun = subjectNoun;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getSubjectNoun() { return subjectNoun; }

    @Override
    public String toString() {
        return displayName;
    }
}
