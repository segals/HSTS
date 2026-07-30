package hsts.server.push;

import hsts.common.entity.User;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.server.dao.SubmissionDAO;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Signs people out after they stop doing anything (requirement 76).
 *
 * <p><i>"המערכת תנתק אוטומטית משתמש לאחר תקופת חוסר פעילות מוגדרת"</i> - the system
 * logs a user out automatically after a defined period of inactivity. SUC-1 says
 * the same.</p>
 *
 * <h2>A student inside an exam is never signed out</h2>
 *
 * <p>This is the part worth thinking about. "Activity" here means <em>a message
 * from her client</em>, and a girl reading a hard question sends nothing for as
 * long as she reads it. Signing her out mid-exam would be the single worst thing
 * this system could do to anybody.</p>
 *
 * <p>So an attempt in progress makes her exempt. She is not idle - she is working,
 * and the server knows it because there is a row saying so. Her exam still ends on
 * time: {@link ExamClockService} closes it at her deadline, and once it is closed
 * she becomes eligible again like anyone else.</p>
 *
 * <h2>Told, then disconnected</h2>
 *
 * <p>The push goes first and the connection is closed a moment later, so the client
 * can say why it is back at the login screen. Dropping the socket first would leave
 * her looking at "the connection was lost", which is a different thing and invites
 * her to blame the network.</p>
 */
public class InactivityService {

    /** How often idleness is checked. Not how long the timeout is. */
    private static final int SWEEP_SECONDS = 30;

    /** Long enough that the closed connection has certainly carried the push. */
    private static final long GRACE_MILLIS = 1500;

    private final SessionRegistry sessions;
    private final SubmissionDAO submissionDAO;
    private final Duration timeout;

    private Consumer<String> logSink = System.out::println;
    private ScheduledExecutorService sweeper;

    public InactivityService(SessionRegistry sessions, SubmissionDAO submissionDAO,
                             Duration timeout) {
        this.sessions = sessions;
        this.submissionDAO = submissionDAO;
        this.timeout = timeout;
    }

    public void setLogSink(Consumer<String> sink) {
        this.logSink = sink;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public synchronized void start() {
        if (sweeper != null) {
            return;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hsts-inactivity");
            t.setDaemon(true);        // must never keep the JVM alive
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweepSafely,
                SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
        logSink.accept("Inactivity logout started - " + timeout.toMinutes()
                     + " minutes, students inside an exam exempt.");
    }

    public synchronized void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
            sweeper = null;
        }
    }

    /**
     * One sweep, with everything caught.
     *
     * <p>A scheduled task that throws is cancelled for good and says nothing, so
     * the timeout would quietly stop working. One bad sweep costs one sweep.</p>
     */
    private void sweepSafely() {
        try {
            sweep(System.currentTimeMillis());
        } catch (Throwable t) {
            logSink.accept("Inactivity sweep failed: " + t);
        }
    }

    /**
     * Signs out everybody who has been idle too long.
     *
     * <p>Package-visible and taking the time as a parameter so a test can run one
     * sweep at a chosen moment instead of waiting half an hour.</p>
     *
     * @return the usernames signed out
     */
    public List<String> sweep(long now) {
        List<String> signedOut = new ArrayList<>();
        long limit = timeout.toMillis();

        for (SessionRegistry.Session session : sessions.getAllSessions()) {
            long idleFor = now - session.getLastActivityMillis();
            if (idleFor < limit) {
                continue;
            }
            User user = session.getUser();
            if (user == null) {
                continue;
            }
            if (isSittingAnExam(user.getUserId())) {
                continue;                 // working, not idle
            }

            long minutes = idleFor / 60_000;
            PushEvent event = new PushEvent(PushType.SESSION_TIMED_OUT, minutes,
                    "You were signed out after " + timeout.toMinutes()
                  + " minutes without activity. Please sign in again.");

            var connection = session.getConnection();
            try {
                connection.sendToClient(event);
            } catch (Exception e) {
                // Already gone. The session is cleared below regardless.
                logSink.accept("Could not warn " + user.getUsername()
                             + " before signing her out: " + e.getMessage());
            }
            sessions.logout(connection);
            signedOut.add(user.getUsername());
            logSink.accept("Signed out " + user.getUsername()
                         + " after " + minutes + " minutes idle (requirement 76).");

            closeAfterGrace(connection);
        }
        return signedOut;
    }

    /** True while she has an exam open - see the note on this class. */
    private boolean isSittingAnExam(String userId) {
        try {
            return submissionDAO.findAllInProgress().stream()
                    .anyMatch(attempt -> userId.equals(attempt.getStudentId()));
        } catch (SQLException e) {
            // Cannot tell, so assume she IS in an exam. Leaving somebody signed in
            // too long is a nuisance; throwing her out of an exam is a disaster.
            logSink.accept("Could not check whether " + userId
                         + " is sitting an exam, so leaving her signed in: "
                         + e.getMessage());
            return true;
        }
    }

    /** Closes the socket once the push has had time to leave. */
    private void closeAfterGrace(ocsf.server.ConnectionToClient connection) {
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(GRACE_MILLIS);
                connection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Already closed, which is the outcome we wanted anyway.
            }
        }, "hsts-inactivity-close");
        closer.setDaemon(true);
        closer.start();
    }
}
