package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.StudentExam;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.AttemptGrantRequest;
import hsts.common.protocol.Response;
import hsts.common.protocol.TimeChangeRequest;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.SubmissionDAO;
import hsts.server.dao.UserDAO;
import hsts.server.push.PushService;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-8 / מתווה scenario 7: watching a sitting and changing its time while it runs.
 *
 * <p>Requirement 47: "במקרים חריגים, בזמן ביצוע בחינה, המורה יכולה לשנות את הזמן
 * המוקצה לביצוע הנוכחי בלבד (שינוי זמני)" - and acceptance test 2.7 requires the
 * students' timers to move <em>by themselves</em> when she does.</p>
 *
 * <p>This is the sharpest demonstration of NFR 18 in the project: two machines,
 * nobody pressing Refresh, and a countdown that jumps.</p>
 */
public class LiveExamController {

    /** A single change of more than this is almost certainly a typo. */
    private static final int MAX_CHANGE_MINUTES = 180;

    private final ExecutionDAO executionDAO;
    private final SubmissionDAO submissionDAO;
    private final ExamDAO examDAO;
    private final UserDAO userDAO;
    private final PushService pushService;

    public LiveExamController(ExecutionDAO executionDAO, SubmissionDAO submissionDAO,
                              ExamDAO examDAO, UserDAO userDAO, PushService pushService) {
        this.executionDAO = executionDAO;
        this.submissionDAO = submissionDAO;
        this.examDAO = examDAO;
        this.userDAO = userDAO;
        this.pushService = pushService;
    }

    // -----------------------------------------------------------------
    //  Watching
    // -----------------------------------------------------------------

    /**
     * The sittings this teacher released that are open right now, or still have
     * somebody working.
     *
     * <p>A sitting whose window has closed still belongs here while a student is
     * inside it - she keeps her full time, so the teacher must keep seeing her.</p>
     */
    public Response listRunningNow(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher watches a sitting.");
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<ExamExecution> live = new ArrayList<>();

            for (ExamExecution execution : executionDAO.findByTeacher(user.getUserId())) {
                boolean windowOpen = execution.isOpenAt(now);
                boolean somebodyInside = false;
                for (StudentExam attempt : submissionDAO.findByExecution(execution.getExecutionId())) {
                    if (attempt.isInProgress()) {
                        somebodyInside = true;
                        break;
                    }
                }
                if (windowOpen || somebodyInside) {
                    live.add(execution);
                }
            }
            return Response.ok(live, live.isEmpty()
                    ? "None of your exams is running at the moment."
                    : live.size() + " of your exams "
                      + (live.size() == 1 ? "is" : "are") + " running now.");
        } catch (SQLException e) {
            return Response.error("Could not load the running exams: " + e.getMessage());
        }
    }

    /** Who is sitting one execution, and how each of them is doing. */
    public Response getLiveStatus(User user, Integer executionId) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher watches a sitting.");
        }
        try {
            ExamExecution execution = executionDAO.findById(executionId);
            if (execution == null) {
                return Response.error("That sitting does not exist.");
            }
            // Whoever released it owns it - she is also the one who will mark it.
            if (!execution.getReleasedBy().equals(user.getUserId())) {
                return Response.error("You did not release that sitting.");
            }
            return Response.ok(submissionDAO.findByExecution(executionId), null);
        } catch (SQLException e) {
            return Response.error("Could not load the students: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Changing the time
    // -----------------------------------------------------------------

    /**
     * Adds (or removes) minutes for everybody currently sitting this exam.
     *
     * <p>A <em>delta</em>, not a new total, because students started at different
     * moments: moving each one's own deadline by the same amount is the only
     * change that treats them alike. A student who has not started yet simply gets
     * the new allowance when she does.</p>
     */
    /**
     * Requirement 61: the teacher opens one more attempt for one student.
     *
     * <p><i>"המורה יכולה להגדיר מספר נסיונות לבחינה. כדי לפתוח ניסיון נוסף המורה
     * צריכה לאשר זאת"</i> - she sets the number of attempts when she releases the
     * exam, and any attempt beyond that needs her approval. This is that approval.</p>
     *
     * <p>SUC-8 puts it here, with the other things a teacher does <em>while</em> a
     * sitting runs, and the rule about who may do it is the same one that governs
     * changing the time: the teacher who released it.</p>
     *
     * <p>Recorded as a row, not a counter, so who granted it and when survive - a
     * question that gets asked if a result is ever disputed.</p>
     */
    public Response grantExtraAttempt(User user, AttemptGrantRequest request) {
        if (request == null) {
            return Response.error("Nothing to grant.");
        }
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher can allow another attempt.");
        }
        try {
            ExamExecution execution = executionDAO.findById(request.getExecutionId());
            if (execution == null) {
                return Response.error("That sitting does not exist.");
            }
            // The same rule as changing the time: the teacher who released it.
            if (!execution.getReleasedBy().equals(user.getUserId())) {
                return Response.error("You did not release that sitting.");
            }

            // "Everybody" first, because it is a different job: one grant each for
            // every student who sat, reported as a count rather than a name.
            if (request.isEveryone()) {
                return grantToEveryone(user, execution, request.getReason());
            }

            User student = userDAO.findById(request.getStudentId());
            if (student == null) {
                return Response.error("That student does not exist.");
            }
            if (!(student instanceof hsts.common.entity.Student enrolled)) {
                return Response.error("Extra attempts are for students.");
            }

            // She must actually be on the course, or the extra attempt is useless -
            // requirement 21 would refuse her the code anyway.
            Exam exam = examDAO.findByIdAndVersion(execution.getExamId(),
                                                   execution.getExamVersion());
            if (exam != null && !enrolled.isEnrolledIn(exam.getCourseCode())) {
                return Response.error(student.getFullName()
                        + " is not enrolled in that course.");
            }

            int granted = submissionDAO.grantExtraAttempt(request.getExecutionId(),
                    request.getStudentId(), user.getUserId(), request.getReason());
            int used = submissionDAO.countAttempts(request.getExecutionId(),
                    request.getStudentId());
            int allowed = execution.getMaxAttempts() + granted;

            // NFR 18: she is told, rather than discovering it by trying again.
            pushService.toUsername(student.getUsername(), new PushEvent(
                    PushType.EXTRA_ATTEMPT_GRANTED, request.getExecutionId(),
                    user.getFullName() + " has allowed you another attempt at exam "
                  + execution.getExamId() + ". Enter the code again to start it."));

            return Response.ok(allowed, student.getFullName() + " may now sit this exam "
                    + allowed + " time(s) in all; she has used " + used + ".");

        } catch (SQLException e) {
            return Response.error("Could not grant the attempt: " + e.getMessage());
        }
    }

    /**
     * One more attempt for every student who sat this sitting.
     *
     * <p>For the case that is about a room rather than a person - the power failed,
     * the network went down. Granting twenty of them by hand invites missing one,
     * and a missed girl is the whole problem repeating itself.</p>
     *
     * <p>Everyone who <b>started</b>, which is the honest set: a girl who never
     * turned up has nothing to re-sit, and giving her an attempt she never had would
     * quietly change what "all your attempts" means for her later.</p>
     */
    private Response grantToEveryone(User user, ExamExecution execution, String reason)
            throws SQLException {
        List<String> studentIds = submissionDAO.findStudentIdsIn(execution.getExecutionId());
        if (studentIds.isEmpty()) {
            return Response.error("Nobody has sat this exam yet, so there is nothing "
                    + "to grant. Extra attempts are for students who have started.");
        }

        int granted = 0;
        for (String studentId : studentIds) {
            submissionDAO.grantExtraAttempt(execution.getExecutionId(), studentId,
                    user.getUserId(), reason);
            granted++;

            User student = userDAO.findById(studentId);
            if (student != null) {
                // NFR 18 again: each of them is told, not left to find out.
                pushService.toUsername(student.getUsername(), new PushEvent(
                        PushType.EXTRA_ATTEMPT_GRANTED, execution.getExecutionId(),
                        user.getFullName() + " has allowed another attempt at exam "
                      + execution.getExamId() + " for everyone who sat it. Enter the "
                      + "code again to start it."));
            }
        }
        return Response.ok(granted, "Another attempt allowed for all " + granted
                + " student(s) who sat this exam. Each has been told.");
    }

    public Response changeTime(User user, TimeChangeRequest request) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher can change the time.");
        }
        if (request == null || request.getDeltaMinutes() == 0) {
            return Response.error("Say how many minutes to add or take away.");
        }
        if (Math.abs(request.getDeltaMinutes()) > MAX_CHANGE_MINUTES) {
            return Response.error("A single change of more than " + MAX_CHANGE_MINUTES
                                + " minutes is almost certainly a mistake.");
        }

        try {
            ExamExecution execution = executionDAO.findById(request.getExecutionId());
            if (execution == null) {
                return Response.error("That sitting does not exist.");
            }
            if (!execution.getReleasedBy().equals(user.getUserId())) {
                return Response.error("You did not release that sitting.");
            }

            int delta = request.getDeltaMinutes();
            int newAllowance = execution.getAllocatedDuration() + delta;
            if (newAllowance <= 0) {
                return Response.error("That would leave no time at all.");
            }

            List<StudentExam> running = new ArrayList<>();
            for (StudentExam attempt : submissionDAO.findByExecution(request.getExecutionId())) {
                if (attempt.isInProgress()) {
                    running.add(attempt);
                }
            }

            // Taking time away could push somebody's deadline into the past, which
            // would end her exam the instant the next tick ran - with no warning
            // and no chance to hand in. Refused rather than done quietly.
            if (delta < 0) {
                LocalDateTime now = LocalDateTime.now();
                for (StudentExam attempt : running) {
                    if (!attempt.getDeadline().plusMinutes(delta).isAfter(now)) {
                        return Response.error("Taking " + (-delta) + " minutes away would end "
                                + attempt.getStudentName() + "'s exam immediately. "
                                + "Take away less.");
                    }
                }
            }

            // The sitting's allowance, for anyone who starts from now on.
            executionDAO.updateAllocatedDuration(execution.getExecutionId(), newAllowance);
            // And everybody already inside.
            int moved = submissionDAO.extendDeadlines(execution.getExecutionId(), delta);

            notifyStudents(running, delta);

            String wording = delta > 0
                    ? "Added " + delta + " minutes."
                    : "Took away " + (-delta) + " minutes.";
            return Response.ok(executionDAO.findById(execution.getExecutionId()),
                    wording + " " + (moved == 0
                        ? "Nobody is sitting it yet, so it applies to whoever starts."
                        : moved + (moved == 1 ? " student" : " students")
                          + " already sitting had their timer updated straight away.")
                  + " The exam itself is unchanged - this applies to this sitting only.");

        } catch (SQLException e) {
            return Response.error("Could not change the time: " + e.getMessage());
        }
    }

    /**
     * Tells each student her clock moved.
     *
     * <p>The new remaining seconds are read back from the database rather than
     * calculated here, so the number she is told is the number the clock will
     * enforce a second later.</p>
     */
    private void notifyStudents(List<StudentExam> running, int delta) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        for (StudentExam before : running) {
            StudentExam after = submissionDAO.findById(before.getSubmissionId());
            if (after == null) {
                continue;
            }
            String message = delta > 0
                    ? "Your teacher has added " + delta
                      + (delta == 1 ? " minute." : " minutes.")
                    : "Your teacher has reduced the time by " + (-delta)
                      + ((-delta) == 1 ? " minute." : " minutes.");

            pushService.toUsername(after.getStudentUsername(), new PushEvent(
                    PushType.EXAM_TIME_CHANGED,
                    after.secondsRemainingAt(now),
                    message));
        }
    }

    /**
     * Tells the teacher that her live view has changed.
     *
     * <p>Called when a student starts or hands in. NFR 18 forbids a Refresh button
     * on her screen just as much as on a student's.</p>
     */
    public void notifyTeacherOfActivity(int executionId, String what) {
        try {
            ExamExecution execution = executionDAO.findById(executionId);
            if (execution == null) {
                return;
            }
            User teacher = userDAO.findById(execution.getReleasedBy());
            if (teacher == null) {
                return;
            }
            pushService.toUsername(teacher.getUsername(), new PushEvent(
                    PushType.EXAM_LIVE_STATUS, executionId, what));
        } catch (SQLException e) {
            // A missed notification must never disturb the student's exam.
            System.err.println("Could not update the teacher's live view: " + e.getMessage());
        }
    }
}
