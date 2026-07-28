package hsts.common.util;

/**
 * Validates an Israeli identity number (תעודת זהות).
 *
 * <p>Required because a student must type her ID number before an exam starts
 * (system description §4, מתווה scenario 6) and it has to be checked. The
 * decision to verify the check digit - not merely the length - was taken
 * deliberately during planning.</p>
 *
 * <h2>How the check digit works</h2>
 *
 * <p>An Israeli ID is 9 digits, and the last one is a checksum over the other
 * eight. Walking left to right, digits in even positions count once and digits in
 * odd positions count twice. Any doubled value above 9 has 9 subtracted from it
 * (which is the same as adding its two digits). If the total divides by 10, the
 * number is valid.</p>
 *
 * <p>Worked example - {@code 123456782}:</p>
 * <pre>
 *   digit   1  2  3  4  5  6  7  8  2
 *   times   1  2  1  2  1  2  1  2  1
 *   ---------------------------------
 *           1  4  3  8  5 12  7 16  2
 *   over 9:          -      3     7
 *   ---------------------------------
 *           1 +4 +3 +8 +5 +3 +7 +7 +2  =  40   ->  40 % 10 == 0, valid
 * </pre>
 *
 * <p>This is why seeded test users cannot simply be numbered {@code 123456789} -
 * that one fails (its total is 47).</p>
 */
public final class IsraeliId {

    public static final int LENGTH = 9;

    private IsraeliId() {
        // utility class
    }

    /** True only for a 9-digit string whose check digit is correct. */
    public static boolean isValid(String id) {
        if (id == null) {
            return false;
        }
        String trimmed = id.trim();
        if (trimmed.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return checksum(trimmed, LENGTH) % 10 == 0;
    }

    /**
     * Given the first 8 digits, returns the 9th that makes the number valid.
     *
     * <p>Used by the seeder so every generated test user has a genuinely valid ID.</p>
     */
    public static int checkDigitFor(String firstEightDigits) {
        if (firstEightDigits == null || firstEightDigits.length() != LENGTH - 1) {
            throw new IllegalArgumentException("expected exactly 8 digits");
        }
        int sum = checksum(firstEightDigits, LENGTH - 1);
        return (10 - (sum % 10)) % 10;
    }

    /** Builds a complete, valid 9-digit ID from an 8-digit prefix. */
    public static String complete(String firstEightDigits) {
        return firstEightDigits + checkDigitFor(firstEightDigits);
    }

    /**
     * Explains why an ID is not acceptable, or returns null when it is fine.
     * The text is written to be shown straight to the user.
     */
    public static String describeProblem(String id) {
        if (id == null || id.trim().isEmpty()) {
            return "Enter your ID number.";
        }
        String trimmed = id.trim();
        if (trimmed.length() != LENGTH) {
            return "An ID number has exactly " + LENGTH + " digits (you entered "
                 + trimmed.length() + ").";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return "An ID number contains digits only.";
            }
        }
        if (checksum(trimmed, LENGTH) % 10 != 0) {
            return "That is not a valid ID number - please check the digits.";
        }
        return null;
    }

    /** Shared core of the calculation, over the first {@code count} digits. */
    private static int checksum(String digits, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) {
            int value = digits.charAt(i) - '0';
            value *= (i % 2 == 0) ? 1 : 2;
            if (value > 9) {
                value -= 9;
            }
            sum += value;
        }
        return sum;
    }
}
