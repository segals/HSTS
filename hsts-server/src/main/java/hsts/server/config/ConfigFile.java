package hsts.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Reads and writes the server's local settings file.
 *
 * <h2>Where it lives, and why</h2>
 *
 * <p>{@code %USERPROFILE%\.hsts\config.properties} - deliberately <em>outside</em>
 * the project folder.</p>
 *
 * <p>This repository is public. A secrets file inside the project would be one
 * mistaken {@code git add -A} away from being published, and a {@code .gitignore}
 * entry is not real protection - it only helps if it is correct, and it silently
 * stops helping if a file is ever renamed. Keeping the file in the user's home
 * directory means the secret is not in the repository at all, so no git mistake
 * can leak it.</p>
 *
 * <p>The file holds the MySQL password and the Gemini API key. Only the machine
 * running the server ever needs it - the client never talks to the database and
 * never calls Gemini.</p>
 */
public final class ConfigFile {

    public static final String KEY_MYSQL_HOST     = "mysql.host";
    public static final String KEY_MYSQL_PORT     = "mysql.port";
    public static final String KEY_MYSQL_DATABASE = "mysql.database";
    public static final String KEY_MYSQL_USER     = "mysql.user";
    public static final String KEY_MYSQL_PASSWORD = "mysql.password";
    public static final String KEY_SERVER_PORT    = "server.port";
    public static final String KEY_GEMINI_API_KEY = "gemini.api.key";

    /**
     * Minutes of inactivity before a user is signed out (requirement 76).
     *
     * <p>The requirement says "a defined period" without fixing one, so it is
     * settable rather than hard-coded - a demo may want two minutes and a school
     * may want sixty.</p>
     */
    public static final String KEY_INACTIVITY_MINUTES = "session.inactivity.minutes";

    private ConfigFile() {
        // utility class
    }

    /** {@code %USERPROFILE%\.hsts\config.properties}, or the equivalent on any OS. */
    public static Path path() {
        String home = System.getenv("USERPROFILE");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home");
        }
        return Paths.get(home, ".hsts", "config.properties");
    }

    public static boolean exists() {
        return Files.isRegularFile(path());
    }

    /** Loads the file, or returns empty properties if it is not there yet. */
    public static Properties load() {
        Properties props = new Properties();
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // A damaged config file must not stop the server from starting -
            // the startup window can still be filled in by hand.
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return props;
    }

    /**
     * Saves the given values, keeping every other key that is already in the file.
     *
     * <p>The merge matters: the Gemini API key lives in the same file, and saving
     * database settings must never wipe it.</p>
     */
    public static void save(Properties toSave) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());

        Properties merged = load();
        merged.putAll(toSave);

        try (OutputStream out = Files.newOutputStream(file)) {
            merged.store(out, "HSTS local settings - NOT part of the git repository");
        }
    }

    public static String get(Properties props, String key, String fallback) {
        String value = props.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
