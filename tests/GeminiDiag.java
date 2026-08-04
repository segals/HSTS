import hsts.server.config.ConfigFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * Diagnostic: shows what Gemini actually replies, so a refusal can be read.
 *
 * <p>{@code GeminiStudyBotService} turns every failure into wording a school pupil
 * can understand, which is right for her and useless for working out what is wrong.
 * This prints the status code and the raw body.</p>
 *
 * <p>The key is in the URL's query string, never in the body, so printing the body
 * is safe. Nothing here prints the URL.</p>
 */
public class GeminiDiag {

    private static final String[] MODELS = {
        "gemini-2.0-flash",
        "gemini-2.5-flash",
        "gemini-flash-latest",
    };

    public static void main(String[] args) throws Exception {
        Properties config = ConfigFile.load();
        String key = ConfigFile.get(config, ConfigFile.KEY_GEMINI_API_KEY, null);

        if (key == null) {
            System.out.println("no key in " + ConfigFile.path());
            return;
        }
        System.out.println("key length : " + key.length() + " characters");
        System.out.println("key shape  : starts \"" + key.substring(0, Math.min(4, key.length()))
                         + "...\", ends \"..." + key.substring(Math.max(0, key.length() - 2))
                         + "\"");
        System.out.println("(a Google API key is normally 39 characters and starts AIza)");
        System.out.println();

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();

        // ---- what models does this key actually have? ----
        System.out.println("=== asking the key which models it may use ===");
        HttpRequest list = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key="
                        + key + "&pageSize=100"))
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> listed = http.send(list, HttpResponse.BodyHandlers.ofString());
        System.out.println("status " + listed.statusCode());
        if (listed.statusCode() / 100 == 2) {
            // Just the names, so the output stays readable.
            for (String piece : listed.body().split("\"name\": \"models/")) {
                int end = piece.indexOf('"');
                if (end > 0 && !piece.startsWith("{")) {
                    System.out.println("   " + piece.substring(0, end));
                }
            }
        } else {
            System.out.println(trim(listed.body()));
        }

        // ---- try a real generateContent on each candidate model ----
        for (String model : MODELS) {
            System.out.println();
            System.out.println("=== generateContent on " + model + " ===");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                            + model + ":generateContent?key=" + key))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"contents\":[{\"parts\":[{\"text\":\"Say OK.\"}]}]}",
                            StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                System.out.println("status " + response.statusCode());
                System.out.println(trim(response.body()));
            } catch (Exception e) {
                System.out.println("threw " + e.getClass().getSimpleName()
                                 + ": " + e.getMessage());
            }
        }
    }

    private static String trim(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 1200 ? flat : flat.substring(0, 1200) + " ...";
    }
}
