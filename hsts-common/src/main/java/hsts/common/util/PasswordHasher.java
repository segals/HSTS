package hsts.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Turns a password into a salted SHA-256 hash, and checks one against another.
 *
 * <p><b>Why a salt.</b> If every password were hashed on its own, two users who
 * happened to pick the same password would end up with the same hash, and that
 * would be visible to anyone reading the table. A salt is a short random string
 * stored next to each user; it is mixed in before hashing, so identical
 * passwords still produce completely different hashes.</p>
 *
 * <p><b>Honest limitation, worth being able to say out loud at the demo.</b>
 * Salted SHA-256 is the right shape but not what a real production system would
 * use today - SHA-256 is fast, and being fast is a disadvantage here, because it
 * also makes guessing fast. A real system would use a deliberately slow function
 * such as bcrypt, scrypt or Argon2. SHA-256 is used here because it is in the
 * standard library, needs no extra dependency, and demonstrates the principle
 * clearly.</p>
 */
public final class PasswordHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 16 random bytes rendered as 32 hex characters - matches CHAR(32) in the schema. */
    private static final int SALT_BYTES = 16;

    private PasswordHasher() {
        // utility class, never instantiated
    }

    /** Produces a fresh random salt for a new user. */
    public static String newSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    /**
     * Hashes a password with its salt.
     *
     * @return 64 lower-case hex characters - matches CHAR(64) in the schema.
     */
    public static String hash(String password, String salt) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("password and salt must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required to exist in every Java platform, so this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /**
     * Checks a typed password against a stored salt and hash.
     *
     * <p>The comparison walks the whole string even after a mismatch is found.
     * A normal {@code equals} would return early, and the tiny timing difference
     * can in principle leak information about the stored value.</p>
     */
    public static boolean matches(String password, String salt, String expectedHash) {
        if (password == null || salt == null || expectedHash == null) {
            return false;
        }
        String actual = hash(password, salt);
        if (actual.length() != expectedHash.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < actual.length(); i++) {
            difference |= actual.charAt(i) ^ expectedHash.charAt(i);
        }
        return difference == 0;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
