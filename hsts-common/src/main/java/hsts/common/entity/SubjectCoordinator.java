package hsts.common.entity;

import hsts.common.enums.UserRole;

/**
 * A subject coordinator: a teacher with one extra duty.
 *
 * <p>She approves or rejects exams before anyone can sit them (requirement 30),
 * but only for the one subject she coordinates (requirement 31). She may also
 * edit questions belonging to that subject (requirement 19).</p>
 *
 * <p>Extending {@link Teacher} rather than {@link User} is deliberate and matches
 * the submitted class diagram: a coordinator <em>is</em> a teacher and does
 * everything a teacher does, plus approving.</p>
 */
public class SubjectCoordinator extends Teacher {

    private static final long serialVersionUID = 1L;

    /** The one subject she coordinates. Requirement 31 limits her authority to it. */
    private String coordinatedSubjectCode;

    public SubjectCoordinator() {
        super();
        setRole(UserRole.COORDINATOR);
    }

    public SubjectCoordinator(String userId, String username, String fullName,
                              String coordinatedSubjectCode) {
        super(userId, username, fullName, UserRole.COORDINATOR);
        this.coordinatedSubjectCode = coordinatedSubjectCode;
    }

    public String getCoordinatedSubjectCode() {
        return coordinatedSubjectCode;
    }

    public void setCoordinatedSubjectCode(String code) {
        this.coordinatedSubjectCode = code;
    }

    public boolean coordinates(String subjectCode) {
        return coordinatedSubjectCode != null && coordinatedSubjectCode.equals(subjectCode);
    }

    @Override
    public boolean checkPermission(String action) {
        if (super.checkPermission(action)) {
            return true;
        }
        return switch (action) {
            case "APPROVE_EXAM", "EDIT_SUBJECT_QUESTIONS" -> true;
            default -> false;
        };
    }
}
