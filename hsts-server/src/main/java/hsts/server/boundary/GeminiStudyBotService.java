package hsts.server.boundary;

import hsts.server.config.ConfigFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

/**
 * The real answering service: Google's Gemini API.
 *
 * <p>Requirement 69 - an existing external API, not a bot of our own.</p>
 *
 * <h2>The API key</h2>
 *
 * <p>Read from {@code %USERPROFILE%\.hsts\config.properties}, which is outside the
 * project folder because this repository is public. The key is <b>never</b> written
 * to a log, never put in an exception message, and never sent to a client - the
 * client does not call Gemini at all, the server does. {@link #describeUrl()}
 * exists so that failures can be reported without the query string that carries
 * the key.</p>
 *
 * <h2>Hand-written JSON</h2>
 *
 * <p>This project has three dependencies - JavaFX, the MySQL driver and OCSF - and
 * adding a JSON library for two small shapes was not worth the weight in the fat
 * jar. The request is a fixed shape built by {@link #buildBody}, and the reply is
 * read by {@link #extractText}, which finds the first {@code "text"} value inside
 * {@code candidates} and unescapes it.</p>
 *
 * <p><b>The limit of that is stated plainly:</b> it is a targeted reader, not a
 * JSON parser. It handles the shape Gemini actually returns, including escapes and
 * {@code \\uXXXX}. A different reply shape would not be understood - and would be
 * reported as "no usable answer" rather than crashing, which is what requirement 72
 * asks for anyway.</p>
 */
public class GeminiStudyBotService implements IStudyBotService {

    /** Overridable in the config file, because model names change over time. */
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    /** A student is waiting at a screen; a call that hangs is worse than a refusal. */
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    /** Gemini is given the material and told to stay inside it. */
    private static final String INSTRUCTIONS = """
            You are a study assistant for a high school course. Answer the student's \
            question using the course material below. Explain simply and briefly, as \
            to a school pupil. If the material does not cover the question, say so \
            plainly and give only general guidance. Never invent facts about the \
            course. Do not reveal exam answers if the question is asking you to do \
            the student's exam for her.""";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String model;

    public GeminiStudyBotService() {
        Properties config = ConfigFile.load();
        this.apiKey = ConfigFile.get(config, ConfigFile.KEY_GEMINI_API_KEY, null);
        this.model = ConfigFile.get(config, "gemini.model", DEFAULT_MODEL);
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getDescription() {
        return isConfigured()
                ? "Gemini (" + model + "), key read from " + ConfigFile.path()
                : "Gemini - NO API KEY. Add gemini.api.key to " + ConfigFile.path();
    }

    /** The endpoint without the key, so it is safe to print. */
    private String describeUrl() {
        return String.format(ENDPOINT, model);
    }

    @Override
    public String ask(String context, String question) throws BotUnavailableException {
        if (!isConfigured()) {
            throw new BotUnavailableException(
                    "The study bot is not set up on the server yet: no Gemini API key "
                  + "has been configured. Ask your teacher to add one.");
        }

        String prompt = INSTRUCTIONS
                + "\n\n=== COURSE MATERIAL ===\n" + context
                + "\n\n=== STUDENT'S QUESTION ===\n" + question;

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(describeUrl() + "?key=" + apiKey))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            buildBody(prompt), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));

        } catch (java.net.http.HttpTimeoutException e) {
            throw new BotUnavailableException(
                    "The study bot did not answer in time. Please try again.", e);
        } catch (java.io.IOException e) {
            // The message deliberately does not include the URI - it carries the key.
            throw new BotUnavailableException(
                    "Could not reach the study bot service. Check the server's "
                  + "internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BotUnavailableException("The request to the study bot was interrupted.", e);
        }

        if (response.statusCode() == 400 || response.statusCode() == 403) {
            throw new BotUnavailableException(
                    "The study bot service refused the request (" + response.statusCode()
                  + "). The API key may be wrong, expired, or not enabled for "
                  + model + ".");
        }
        if (response.statusCode() == 404) {
            throw new BotUnavailableException(
                    "The study bot service does not know the model \"" + model + "\". "
                  + "Set gemini.model in " + ConfigFile.path() + " to one that exists.");
        }
        if (response.statusCode() == 429) {
            throw new BotUnavailableException(
                    "The study bot has been asked too many questions just now. "
                  + "Please wait a moment and try again.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new BotUnavailableException(
                    "The study bot service returned an error (" + response.statusCode() + ").");
        }

        String answer = extractText(response.body());
        if (answer == null || answer.isBlank()) {
            // Requirement 72: no suitable answer, so say so rather than show a blank.
            throw new BotUnavailableException(
                    "The study bot did not have an answer for that. Try asking it a "
                  + "different way, or in more detail.");
        }
        return answer.trim();
    }

    // -----------------------------------------------------------------
    //  JSON, by hand
    // -----------------------------------------------------------------

    // These four are public so they can be tested directly. They are the parts
    // most likely to break silently - a JSON body that is subtly malformed comes
    // back as a 400 that looks like a bad API key - and they are pure functions, so
    // testing them needs no network, no key and no server.
    public static String buildBody(String prompt) {
        return "{\"contents\":[{\"parts\":[{\"text\":\"" + escape(prompt) + "\"}]}]}";
    }

    /** Escapes a Java string so it is a valid JSON string body. */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Control characters are illegal raw inside a JSON string.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Pulls the answer out of Gemini's reply.
     *
     * <p>Finds {@code "candidates"}, then the first {@code "text"} value after it,
     * and unescapes that string. Returns null if the shape is not what is expected -
     * which the caller turns into requirement 72's message rather than a crash.</p>
     */
    public static String extractText(String json) {
        if (json == null) {
            return null;
        }
        int candidates = json.indexOf("\"candidates\"");
        if (candidates < 0) {
            return null;
        }
        int key = json.indexOf("\"text\"", candidates);
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + 6);
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        return readJsonString(json, open + 1);
    }

    /** Reads one JSON string body starting at {@code from}, honouring escapes. */
    public static String readJsonString(String json, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                return out.toString();          // the closing quote
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= json.length()) {
                break;
            }
            char e = json.charAt(i);
            switch (e) {
                case 'n'  -> out.append('\n');
                case 'r'  -> out.append('\r');
                case 't'  -> out.append('\t');
                case 'b'  -> out.append('\b');
                case 'f'  -> out.append('\f');
                case '"'  -> out.append('"');
                case '\\' -> out.append('\\');
                case '/'  -> out.append('/');
                case 'u'  -> {
                    if (i + 4 < json.length()) {
                        try {
                            out.append((char) Integer.parseInt(
                                    json.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            out.append("\\u");
                        }
                    }
                }
                default -> out.append(e);
            }
        }
        return null;                            // never closed - malformed
    }
}
