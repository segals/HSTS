package hsts.server.boundary;

/**
 * The external answering service the bot uses.
 *
 * <p>Requirement 69: <i>"הבוט יממש API חיצוני קיים לשליחת שאלות וקבלת תשובות; אין
 * צורך לפתח בוט חדש"</i> - use an existing external API, do not write a bot. So
 * this is a <b>Boundary</b> class in the three-tier sense: the whole of the outside
 * world, behind one method, exactly as {@link IUserManagementSystem} does for the
 * school's user directory.</p>
 *
 * <h2>Why an interface and not just the Gemini class</h2>
 *
 * <p>Three reasons, and the third is the one that matters.</p>
 *
 * <ol>
 *   <li>The rules around the bot - who may ask, when it is unavailable, what gets
 *       stored - are the assignment. Which vendor answers is not. Swapping Gemini
 *       for something else should touch one class.</li>
 *   <li>Requirement 72 needs a sensible message when no usable answer comes back.
 *       That is far easier to be sure of when a failure can be produced on demand.</li>
 *   <li><b>The tests must not need the network or a real API key.</b> Every check
 *       of requirements 70 to 75 runs against a stub implementation of this
 *       interface. A suite that calls a paid API is a suite nobody runs twice.</li>
 * </ol>
 */
public interface IStudyBotService {

    /**
     * Asks the external service a question, given the material to answer from.
     *
     * @param context  the bot's knowledge sources, already joined into text
     * @param question what the student typed
     * @return the answer, never null and never blank
     * @throws BotUnavailableException when no usable answer can be had - the
     *         service is unreachable, refused, or sent nothing back. Requirement 72
     *         turns this into a message on her screen.
     */
    String ask(String context, String question) throws BotUnavailableException;

    /** Shown on the server console, so it is obvious which service is wired in. */
    String getDescription();

    /**
     * False when the service cannot work at all - typically no API key configured.
     *
     * <p>Checked before a student is allowed to ask, so she is told the bot is not
     * set up rather than waiting for a call that was never going to succeed.</p>
     */
    boolean isConfigured();

    /** Thrown when no usable answer came back. Requirement 72. */
    class BotUnavailableException extends Exception {

        private static final long serialVersionUID = 1L;

        public BotUnavailableException(String message) {
            super(message);
        }

        public BotUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
