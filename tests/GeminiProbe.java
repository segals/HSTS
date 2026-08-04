import hsts.server.boundary.GeminiStudyBotService;
import hsts.server.boundary.IStudyBotService;

/**
 * One real call to Gemini, to prove the configured key works.
 *
 * <p>Separate from M14Test on purpose. That suite runs against a stub so it needs
 * no network and no key; this one deliberately spends a single call, so it is run
 * by hand when the key changes rather than as part of the regression.</p>
 *
 * <p>The key is never printed. {@code getDescription()} reports the model and the
 * path of the config file, and nothing else.</p>
 */
public class GeminiProbe {

    public static void main(String[] args) {
        GeminiStudyBotService gemini = new GeminiStudyBotService();

        System.out.println("service : " + gemini.getDescription());
        System.out.println("key set : " + gemini.isConfigured());

        if (!gemini.isConfigured()) {
            System.out.println();
            System.out.println("FAILED: no key was loaded. Add a line");
            System.out.println("    gemini.api.key=YOUR_KEY");
            System.out.println("to the file named above, with no quotes and no spaces.");
            System.exit(1);
        }

        String context = """
                --- Plane Geometry notes ---
                The angles of a triangle add up to 180 degrees.
                A right triangle has one angle of exactly 90 degrees.
                Pythagoras' theorem: in a right triangle, the square of the
                hypotenuse equals the sum of the squares of the other two sides.
                """;
        String question = "In one sentence: why do the angles of a triangle add to 180?";

        System.out.println();
        System.out.println("asking  : " + question);
        long started = System.currentTimeMillis();

        try {
            String answer = gemini.ask(context, question);
            long took = System.currentTimeMillis() - started;

            System.out.println();
            System.out.println("ANSWER (" + took + " ms, " + answer.length() + " chars):");
            System.out.println();
            System.out.println(answer);
            System.out.println();
            System.out.println("==== the key works ====");

        } catch (IStudyBotService.BotUnavailableException e) {
            long took = System.currentTimeMillis() - started;
            System.out.println();
            System.out.println("REFUSED after " + took + " ms:");
            System.out.println("  " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("  cause: " + e.getCause().getClass().getSimpleName()
                                 + " - " + e.getCause().getMessage());
            }
            System.out.println();
            System.out.println("==== the key does NOT work yet ====");
            System.exit(1);
        }
    }
}
