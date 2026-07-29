package hsts.server.control.strategy;

/**
 * Thrown when the question bank cannot satisfy an automatic build request.
 *
 * <p>Requirement 29: "אם אין מספיק שאלות תואמות לקריטריונים – המערכת תודיע על כך
 * ולא תיצור בחינה". The exam must not be created at all - not a partial one, not
 * a draft. So this is an exception rather than a return value: it stops the build
 * where it stands and cannot be accidentally ignored.</p>
 *
 * <p>The message names exactly which line of the request could not be met and by
 * how much, because "not enough questions" alone leaves the teacher to guess
 * which criterion to relax.</p>
 */
public class InsufficientQuestionsException extends Exception {

    private static final long serialVersionUID = 1L;

    public InsufficientQuestionsException(String message) {
        super(message);
    }
}
