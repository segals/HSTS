package hsts.common.util;

import java.security.SecureRandom;

/**
 * The short code a teacher reads out so a class can start an exam.
 *
 * <h2>Why it is 4 characters of letters OR digits</h2>
 *
 * <p>The two staff documents disagree. מתווה scenario 5 item 3 says
 * "קוד ביצוע בן 4 <b>ספרות</b>" - four digits. System description §4 and our
 * requirement 37 say "קוד ביצוע בן 4 <b>שדות – ספרות ואותיות</b>" - four fields
 * of digits and letters.</p>
 *
 * <p>Accepting four characters where each may be a digit or a letter satisfies
 * both: every 4-digit code the מתווה describes is valid here, and so is every
 * mixed code the system description describes. This was settled during planning
 * and is to be mentioned in the report.</p>
 *
 * <h2>Two details that matter in a noisy classroom</h2>
 *
 * <p><b>Case does not count.</b> The teacher says the code out loud; nobody hears
 * capitals. Codes are stored upper-case and compared upper-case, so a student who
 * types {@code k7m2} gets in.</p>
 *
 * <p><b>Ambiguous characters are not generated.</b> {@code O}/{@code 0} and
 * {@code I}/{@code 1} are indistinguishable when spoken and easy to confuse when
 * written on a board, so generated codes leave them out. They are still
 * <em>accepted</em> if a teacher types one deliberately - this restricts what we
 * produce, not what she may choose.</p>
 */
public final class ExecutionCode {

    public static final int LENGTH = 4;

    /** Letters and digits minus the ones that sound or look alike. */
    private static final String GENERATOR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ExecutionCode() {
    }

    /** True for exactly 4 characters, each a letter or a digit. */
    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!Character.isLetterOrDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The form stored and compared: trimmed and upper-case.
     *
     * @return null if the code is not valid at all
     */
    public static String normalise(String code) {
        return isValid(code) ? code.trim().toUpperCase() : null;
    }

    /** A fresh random code, avoiding characters that are easy to mishear. */
    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(GENERATOR_ALPHABET.charAt(RANDOM.nextInt(GENERATOR_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Why a code is unacceptable, or null when it is fine. Shown to the user. */
    public static String describeProblem(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "Enter a " + LENGTH + "-character code, or press Generate.";
        }
        String trimmed = code.trim();
        if (trimmed.length() != LENGTH) {
            return "The code is exactly " + LENGTH + " characters (you entered "
                 + trimmed.length() + ").";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isLetterOrDigit(trimmed.charAt(i))) {
                return "The code may contain only letters and digits.";
            }
        }
        return null;
    }
}
