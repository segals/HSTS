package hsts.common.enums;

/**
 * How one student's attempt at an exam ended.
 *
 * <p>Requirement 48 asks an execution to report how many students started, how
 * many finished by themselves, and how many "לא הספיקו" - ran out of time. These
 * three values are what those counts are derived from.</p>
 */
public enum SubmissionStatus {

    /** She is sitting it right now. */
    IN_PROGRESS("In progress"),

    /** She pressed Submit herself, within her time. */
    FINISHED("Finished"),

    /** Her time ran out and the system closed it for her. */
    TIMED_OUT("Ran out of time");

    private final String displayName;

    SubmissionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
