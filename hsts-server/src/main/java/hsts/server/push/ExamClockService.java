package hsts.server.push;

import hsts.common.entity.StudentExam;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.server.dao.SubmissionDAO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The one clock in the system.
 *
 * <p>Once a second it looks at every attempt still running and does two things:
 * tells that student how long she has left, and closes the exam of anyone whose
 * time has run out.</p>
 *
 * <h2>Why the server owns the clock</h2>
 *
 * <p>A countdown running on the student's own computer would decide when her exam
 * ends. That is the wrong place for the decision three times over: the two
 * laptops' clocks need not agree, a client that freezes would quietly award extra
 * time, and anyone who cared to could stop the timer altogether.</p>
 *
 * <p>So the client displays a number it is <em>told</em>. Every judgement - is she
 * still allowed to answer, has she run out, how long did she take - is made here
 * against the deadline stored in the database. Acceptance test 2.11 already
 * required the timer to be synchronised from the server.</p>
 *
 * <h2>Why it also survives everything</h2>
 *
 * <p>Because the deadline is a database column rather than a countdown held in
 * memory, a student can close her client and reopen it, or the server can be
 * restarted, and her remaining time is still exactly right.</p>
 */
public class ExamClockService {

    /** Once a second: fine enough for a visible countdown, cheap enough to ignore. */
    private static final long TICK_SECONDS = 1;

    private final SubmissionDAO submissionDAO;
    private final PushService pushService;

    private ScheduledExecutorService ticker;
    private Consumer<String> logSink = message -> { };

    /** Told (executionId, description) whenever the clock closes somebody's exam. */
    private java.util.function.BiConsumer<Integer, String> onExamClosed = (id, what) -> { };

    public ExamClockService(SubmissionDAO submissionDAO, PushService pushService) {
        this.submissionDAO = submissionDAO;
        this.pushService = pushService;
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = (sink == null) ? message -> { } : sink;
    }

    /** Registers who to tell when an exam is closed automatically. */
    public void setOnExamClosed(java.util.function.BiConsumer<Integer, String> listener) {
        this.onExamClosed = (listener == null) ? (id, what) -> { } : listener;
    }

    /** Starts ticking. Safe to call twice. */
    public synchronized void start() {
        if (ticker != null && !ticker.isShutdown()) {
            return;
        }
        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hsts-exam-clock");
            // A daemon thread: it must never be the reason the server refuses to exit.
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        logSink.accept("Exam clock started.");
    }

    public synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    /**
     * One tick, with everything caught.
     *
     * <p>A scheduled task that throws is silently cancelled and never runs again -
     * so the clock would stop, no exam would ever auto-close, and nothing would
     * say why. Catching everything here means one bad tick costs one tick.</p>
     */
    /**
     * Attempts that have already had the 90% warning (requirement 43).
     *
     * <p>Concurrent because the ticker thread writes it while nothing else does -
     * but the clock can be stopped and started, and a plain {@code HashSet} shared
     * across that is asking for trouble for no saving at all.</p>
     */
    private final java.util.Set<Integer> warned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            logSink.accept("Exam clock tick failed: " + t);
        }
    }

    private void tick() throws Exception {
        List<StudentExam> running = submissionDAO.findAllInProgress();
        if (running.isEmpty()) {
            // Nobody is sitting anything, so nobody can be owed a warning. Clearing
            // here stops the set growing for ever in a long-running server.
            warned.clear();
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        java.util.Set<Integer> stillRunning = new java.util.HashSet<>();

        for (StudentExam attempt : running) {
            stillRunning.add(attempt.getSubmissionId());

            if (attempt.isExpiredAt(now)) {
                closeExpired(attempt);
                warned.remove(attempt.getSubmissionId());
            } else {
                // Only worth sending to somebody who is actually connected.
                pushService.toUsername(attempt.getStudentUsername(), new PushEvent(
                        PushType.EXAM_TIME_TICK,
                        attempt.secondsRemainingAt(now),
                        null));
                warnIfNearlyOut(attempt, now);
            }
        }
        warned.retainAll(stillRunning);
    }

    /** How long before the room closes its students are warned. */
    private static final long CLOSING_WARNING_SECONDS = 5 * 60;

    /**
     * One warning per attempt - the one attached to the end that will actually stop
     * her.
     *
     * <p>An attempt has two possible ends and they warrant different words:</p>
     *
     * <ul>
     *   <li>Her own allowance runs out first - requirement 43's popup at nine
     *       tenths of the way through.</li>
     *   <li>The sitting closes first (requirement 45 closes it for everybody) - a
     *       warning five minutes before that, naming the time the room shuts.</li>
     * </ul>
     *
     * <p>Only one is sent, because two popups saying nearly the same thing is worse
     * than one saying the right thing. Which one is decided by
     * {@link StudentExam#isCutShortByClose()}, so a girl who started ten minutes
     * before the room closes is warned about the room - the 90% mark of her own
     * ninety minutes would never arrive.</p>
     *
     * <p>The 90% point is worked out from <b>her own</b> start and deadline, not
     * from the sitting's allotted minutes. The teacher may extend the time mid-exam
     * (requirement 47), and a warning computed from the original length would then
     * fire at the wrong moment - or, worse, have fired already and never fire
     * again.</p>
     *
     * <p>{@link #warned} remembers who has had it, because the clock ticks every
     * second and the condition stays true right to the end.</p>
     */
    private void warnIfNearlyOut(StudentExam attempt, LocalDateTime now) {
        if (warned.contains(attempt.getSubmissionId())) {
            return;
        }
        // The seconds, so the wording can name minutes AND seconds. The customer
        // asked for exactly that, and rightly: "less than a minute left" is true
        // with fifty seconds to go and tells her nothing she can act on.
        long leftSeconds = attempt.secondsRemainingAt(now);

        if (attempt.isCutShortByClose()) {
            if (leftSeconds > CLOSING_WARNING_SECONDS) {
                return;
            }
            warned.add(attempt.getSubmissionId());
            pushService.toUsername(attempt.getStudentUsername(), new PushEvent(
                    PushType.EXAM_CLOSING_WARNING,
                    leftSeconds,
                    describeClosingWarning(leftSeconds, attempt.getCloseTime())));
            return;
        }

        if (attempt.getStartTime() == null || attempt.getDeadline() == null) {
            return;
        }
        long totalSeconds = java.time.Duration.between(
                attempt.getStartTime(), attempt.getDeadline()).getSeconds();
        if (totalSeconds <= 0) {
            return;
        }
        if (leftSeconds * 10 > totalSeconds) {
            return;                                  // more than a tenth left
        }

        warned.add(attempt.getSubmissionId());

        pushService.toUsername(attempt.getStudentUsername(), new PushEvent(
                PushType.EXAM_TIME_WARNING,
                leftSeconds,
                describeWarning(leftSeconds)));
    }

    /**
     * "90% of the exam time has gone. You have 6 minutes and 42 seconds left."
     *
     * <p>Built here so the sentence the student sees is the server's, not something
     * a client composed - the same reasoning as the countdown itself. The screen may
     * lay it out differently but does not invent the numbers.</p>
     */
    public static String describeWarning(long secondsLeft) {
        return "90% of the exam time has gone. You have " + describeAmount(secondsLeft)
             + " left.";
    }

    /**
     * "This exam closes for everyone at 13:30. You have 4 minutes and 12 seconds
     * left, and your paper will be handed in for you."
     *
     * <p>Names the wall-clock time as well as the countdown, because this end is not
     * hers - it is the room's, it is the same for the girl beside her, and it will
     * not move however fast she works. The 90% wording would be actively misleading
     * here: she may have most of her own time still in hand.</p>
     */
    public static String describeClosingWarning(long secondsLeft, LocalDateTime closesAt) {
        String when = (closesAt == null) ? null
                : closesAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        return "This exam closes for everyone"
             + (when == null ? "" : " at " + when)
             + ". You have " + describeAmount(secondsLeft)
             + " left, and your paper will be handed in for you.";
    }

    /** "6 minutes and 42 seconds", or "45 seconds" under a minute. */
    private static String describeAmount(long secondsLeft) {
        long minutes = Math.max(0, secondsLeft) / 60;
        long seconds = Math.max(0, secondsLeft) % 60;

        if (minutes == 0) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes")
             + " and " + seconds + (seconds == 1 ? " second" : " seconds");
    }

    /**
     * Closes one attempt whose time has run out - by her own clock or the room's.
     *
     * <p>The end time recorded is the <em>deadline that stopped her</em>, not the
     * moment this tick happened to run. Otherwise her recorded duration would
     * include however long the tick took to notice, and two students closed by the
     * same event could be recorded as taking different amounts of time.</p>
     *
     * <p>Both endings are recorded as {@code TIMED_OUT}. Requirement 48 counts
     * students who "started, finished, and did not manage" - two categories, not
     * three - and a girl the room closed on did not finish by herself either. What
     * differs is what she is <em>told</em>, which is the part she can see.</p>
     */
    private void closeExpired(StudentExam attempt) throws Exception {
        LocalDateTime end = attempt.effectiveEnd();
        boolean byTheRoom = attempt.isCutShortByClose();
        int minutes = TakeExamMinutes.between(attempt.getStartTime(), end);

        boolean closedByUs = submissionDAO.finish(attempt.getSubmissionId(),
                SubmissionStatus.TIMED_OUT, end, minutes);

        if (!closedByUs) {
            // She pressed Submit in the same instant and got there first. Her
            // record already exists and must not be overwritten.
            return;
        }

        logSink.accept((byTheRoom ? "Sitting closed: " : "Time up: ")
                     + attempt.getStudentName()
                     + " on exam " + attempt.getExamId()
                     + " (attempt " + attempt.getAttemptNo() + ") - closed automatically.");

        onExamClosed.accept(attempt.getExecutionId(), byTheRoom
                ? attempt.getStudentName() + " was still working when the sitting closed."
                : attempt.getStudentName() + " ran out of time.");

        pushService.toUsername(attempt.getStudentUsername(), byTheRoom
                ? new PushEvent(
                    PushType.EXAM_CLOSED_FOR_EVERYONE,
                    attempt.getSubmissionId(),
                    "The exam has closed for everyone. Your paper has been handed in, "
                  + "and everything you had chosen was saved.")
                : new PushEvent(
                    PushType.EXAM_AUTO_SUBMITTED,
                    attempt.getSubmissionId(),
                    "Your time is up. The exam has been handed in for you, and everything "
                  + "you had chosen was saved."));
    }

    /** Rounded-up whole minutes, kept in one place so the clock and the controller agree. */
    static final class TakeExamMinutes {
        private TakeExamMinutes() { }

        static int between(LocalDateTime from, LocalDateTime to) {
            long seconds = Math.max(0, java.time.Duration.between(from, to).toSeconds());
            return (int) ((seconds + 59) / 60);
        }
    }
}
