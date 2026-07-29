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
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        for (StudentExam attempt : running) {
            if (attempt.isExpiredAt(now)) {
                closeExpired(attempt);
            } else {
                // Only worth sending to somebody who is actually connected.
                pushService.toUsername(attempt.getStudentUsername(), new PushEvent(
                        PushType.EXAM_TIME_TICK,
                        attempt.secondsRemainingAt(now),
                        null));
            }
        }
    }

    /**
     * Closes one attempt whose time has run out.
     *
     * <p>The end time recorded is her <em>deadline</em>, not the moment this tick
     * happened to run. Otherwise her recorded duration would include however long
     * the tick took to notice, and two students who ran out at the same instant
     * could be recorded as taking different amounts of time.</p>
     */
    private void closeExpired(StudentExam attempt) throws Exception {
        int minutes = TakeExamMinutes.between(attempt.getStartTime(), attempt.getDeadline());

        boolean closedByUs = submissionDAO.finish(attempt.getSubmissionId(),
                SubmissionStatus.TIMED_OUT, attempt.getDeadline(), minutes);

        if (!closedByUs) {
            // She pressed Submit in the same instant and got there first. Her
            // record already exists and must not be overwritten.
            return;
        }

        logSink.accept("Time up: " + attempt.getStudentName()
                     + " on exam " + attempt.getExamId()
                     + " (attempt " + attempt.getAttemptNo() + ") - closed automatically.");

        onExamClosed.accept(attempt.getExecutionId(),
                attempt.getStudentName() + " ran out of time.");

        pushService.toUsername(attempt.getStudentUsername(), new PushEvent(
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
