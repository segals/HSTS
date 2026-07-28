package hsts.common.entity;

import hsts.common.enums.UserRole;

/**
 * The school principal: reads everything, changes nothing.
 *
 * <p>System description §7.3 is explicit - "המנהלת לא מוסיפה או משנה מידע במערכת
 * אלא רק מקבלת מידע". She browses all questions, exams and results
 * (requirement 62) and pulls statistical reports (requirement 63).</p>
 *
 * <p>Note what {@link #checkPermission} does <em>not</em> allow: approving a
 * grade. That was confirmed during planning - only the teacher who released an
 * exam may approve its grades. The principal sees the numbers and never
 * changes them.</p>
 */
public class Principal extends User {

    private static final long serialVersionUID = 1L;

    public Principal() {
        super();
    }

    public Principal(String userId, String username, String fullName) {
        super(userId, username, fullName, UserRole.PRINCIPAL);
    }

    @Override
    public boolean checkPermission(String action) {
        return switch (action) {
            case "VIEW_ALL_QUESTIONS", "VIEW_ALL_EXAMS",
                 "VIEW_ALL_RESULTS", "GENERATE_REPORTS" -> true;
            default -> false;   // read-only: no approving, no editing, no creating
        };
    }
}
