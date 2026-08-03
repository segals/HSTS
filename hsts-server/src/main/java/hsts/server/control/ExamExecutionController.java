package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.ExamReleaseRequest;
import hsts.common.protocol.Response;
import hsts.common.util.ExecutionCode;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * SUC-6 / מתווה scenario 5: taking an exam out of the drawer.
 *
 * <p>The teacher picks an approved exam, sets the moment it opens and the moment
 * it closes, and sets the 4-character code she will read out. מתווה scenario 5
 * groups all of that into one action, and this is that action.</p>
 *
 * <h2>Two decisions from planning that show up here</h2>
 *
 * <p><b>The teacher does this, not the coordinator.</b> The submitted class
 * diagram put {@code releaseFromDrawer} and {@code setExamDates} on
 * {@code ExamApprovalController}, but מתווה scenario 5 says plainly
 * "<b>המורה</b> מגדירה מועד... <b>המורה</b> מגדירה קוד ביצוע". The coordinator
 * approves; the teacher decides when her class sits it.</p>
 *
 * <p><b>The close time ends the exam for everybody</b> (requirement 45). Nobody
 * may start after it, and anybody still working when it arrives is handed in
 * automatically. That is enforced by the clock; here it only shapes the wording,
 * so that nobody sets a window expecting students to run past it.</p>
 */
public class ExamExecutionController {

    /** A whole school day is already generous for one sitting. */
    private static final int MAX_DURATION_MINUTES = 600;

    /** More than this many attempts is certainly a typo. */
    private static final int MAX_ATTEMPTS = 10;

    /** How many random codes to try before giving up on finding a free one. */
    private static final int CODE_SUGGESTION_TRIES = 40;

    /** Readable in a message. LocalDateTime.toString() prints nanoseconds. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm");

    private final ExecutionDAO executionDAO;
    private final ExamDAO examDAO;

    /** Only to remember when this teacher last looked at her release list. */
    private final hsts.server.dao.UserDAO userDAO = new hsts.server.dao.UserDAO();

    /**
     * Told (sitting, courseCode) whenever an exam is given to a class.
     *
     * <p>So the students of that course can be told without pressing anything. A
     * callback rather than a push service held here: releasing an exam has nothing
     * to do with who happens to be connected, and the exam clock is wired the same
     * way for the same reason.</p>
     */
    private java.util.function.BiConsumer<ExamExecution, String> onReleased = (x, c) -> { };

    public void setOnReleased(java.util.function.BiConsumer<ExamExecution, String> listener) {
        this.onReleased = (listener == null) ? (x, c) -> { } : listener;
    }

    public ExamExecutionController(ExecutionDAO executionDAO, ExamDAO examDAO) {
        this.executionDAO = executionDAO;
        this.examDAO = examDAO;
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    /**
     * The approved exam versions this teacher may release.
     *
     * <p>Requirement 35: an unapproved version cannot be given dates, so it does
     * not appear here at all. Offering it and then refusing would be worse than
     * not offering it.</p>
     */
    public Response listReleasable(User user) {
        if (!(user instanceof Teacher teacher)) {
            return Response.error("Only a teacher releases exams.");
        }
        try {
            List<Exam> releasable =
                    executionDAO.findReleasableForCourses(teacher.getTaughtCourseCodes());

            // Which of these are news to HER: exams she wrote, approved since she
            // last opened this list. The coordinator's decision is pushed to her at
            // the time, but that only reaches her if she is signed in at that
            // moment - and she usually is not. This is the part that waits.
            java.time.LocalDateTime seen = userDAO.approvalsSeenAt(user.getUserId());
            int fresh = 0;
            for (Exam exam : releasable) {
                boolean hers = user.getUserId().equals(exam.getAuthorId());
                boolean sinceSheLooked = exam.getApprovedAt() != null
                        && (seen == null || exam.getApprovedAt().isAfter(seen));
                exam.setNewlyApproved(hers && sinceSheLooked);
                if (exam.isNewlyApproved()) {
                    fresh++;
                }
            }
            // She is looking now, so the dots are spent. Written after the flags are
            // worked out, so this reply still carries them and the next does not.
            userDAO.markApprovalsSeen(user.getUserId());

            return Response.ok(releasable, releasable.isEmpty()
                    ? "No approved exams yet. An exam needs the coordinator's approval "
                      + "before it can be given to a class."
                    : releasable.size() + " approved exam version(s) ready to release."
                      + (fresh == 0 ? ""
                         : "  " + fresh + (fresh == 1 ? " of yours was" : " of yours were")
                           + " approved since you last looked."));
        } catch (SQLException e) {
            return Response.error("Could not load the approved exams: " + e.getMessage());
        }
    }

    /** Everything this teacher has released. She is the one who will mark them. */
    public Response listMyExecutions(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher has releases to list.");
        }
        try {
            List<ExamExecution> mine = executionDAO.findByTeacher(user.getUserId());
            return Response.ok(mine, mine.isEmpty()
                    ? "You have not released any exams yet."
                    : mine.size() + " release(s).");
        } catch (SQLException e) {
            return Response.error("Could not load your releases: " + e.getMessage());
        }
    }

    /**
     * A random code that is not already in use.
     *
     * <p>Offered because a teacher inventing her own is how two sittings end up
     * sharing a code. It is only a suggestion - she may type her own.</p>
     */
    public Response suggestCode(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher releases exams.");
        }
        try {
            for (int attempt = 0; attempt < CODE_SUGGESTION_TRIES; attempt++) {
                String candidate = ExecutionCode.generate();
                if (!executionDAO.isCodeTaken(candidate)) {
                    return Response.ok(candidate, null);
                }
            }
            return Response.error("Could not find a free code. Please type one yourself.");
        } catch (SQLException e) {
            return Response.error("Could not check the codes: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Releasing
    // -----------------------------------------------------------------

    /**
     * Takes an approved exam out of the drawer.
     *
     * <p>Requirement 36 allows this many times over for the same exam - a second
     * class, a re-sit, a different date - and each one is a separate execution
     * with its own code, window and results.</p>
     */
    public Response release(User user, ExamReleaseRequest request) {
        if (request == null) {
            return Response.error("Nothing was sent.");
        }
        if (!(user instanceof Teacher teacher)) {
            return Response.error("Only a teacher releases exams.");
        }

        try {
            Exam exam = examDAO.findByIdAndVersion(request.getExamId(), request.getExamVersion());
            if (exam == null) {
                return Response.error("No exam " + request.getExamId()
                                    + " version " + request.getExamVersion() + ".");
            }

            // Requirement 20 / decision 4: a teacher of the course may release it,
            // even if a colleague wrote it. Whoever releases it will mark it.
            if (!teacher.teaches(exam.getCourseCode())) {
                return Response.error("You do not teach that course, so you cannot "
                                    + "give its exams to a class.");
            }

            // Requirement 35: approved versions only.
            if (exam.getStatus() != ExamStatus.APPROVED) {
                return Response.error("Exam " + exam.getExamId() + " version "
                        + exam.getVersion() + " is " + exam.getStatus().getDisplayName().toLowerCase()
                        + ". Only an approved version can be given to a class.");
            }

            String problem = validate(request);
            if (problem != null) {
                return Response.error(problem);
            }

            String code = ExecutionCode.normalise(request.getExecutionCode());
            if (executionDAO.isCodeTaken(code)) {
                return Response.error("The code " + code + " is already in use by another "
                        + "sitting. Choose a different one, or press Generate.");
            }

            // Truncate to whole seconds before storing.
            //
            // A LocalDateTime carries nanoseconds; a MySQL DATETIME column without
            // a fractional-seconds precision does not, and rounds them away on
            // insert. Left alone, the object handed back to the client would not
            // match the row - the confirmation message would name a moment a
            // fraction of a second different from the one actually saved, and any
            // later comparison against the stored value could disagree.
            LocalDateTime open = request.getOpenTime().truncatedTo(ChronoUnit.SECONDS);
            LocalDateTime close = request.getCloseTime().truncatedTo(ChronoUnit.SECONDS);

            ExamExecution execution = new ExamExecution();
            execution.setExamId(exam.getExamId());
            execution.setExamVersion(exam.getVersion());
            execution.setExecutionCode(code);
            execution.setOpenTime(open);
            execution.setCloseTime(close);
            execution.setAllocatedDuration(request.getAllocatedDuration());
            // Recorded now so a later live change is visibly a change.
            execution.setOriginalDuration(request.getAllocatedDuration());
            execution.setMaxAttempts(request.getMaxAttempts());
            execution.setReleasedBy(user.getUserId());
            execution.setReleasedByName(user.getFullName());
            execution.setCreatedAt(LocalDateTime.now());

            executionDAO.insert(execution);

            // The class can be told at once, without anybody pressing anything.
            // A callback rather than a push service of its own: this controller has
            // no business knowing about sessions, and the clock already works this
            // way for the same reason.
            execution.setCourseName(exam.getCourseName());
            onReleased.accept(execution, exam.getCourseCode());

            return Response.ok(execution,
                    "Exam " + exam.getExamId() + " released with code " + code
                  + ". Students may start between " + WHEN.format(open)
                  + " and " + WHEN.format(close) + ", and each gets "
                  + request.getAllocatedDuration() + " minutes from when she begins.");

        } catch (SQLException e) {
            // The unique constraint on the code is the last line of defence: two
            // teachers releasing the same code at the same instant would both pass
            // the isCodeTaken check and one insert would fail here.
            if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                return Response.error("That code was taken a moment ago by somebody else. "
                                    + "Press Generate and try again.");
            }
            return Response.error("Could not release the exam: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------

    /** The first problem, worded so the teacher can act on it, or null if fine. */
    private String validate(ExamReleaseRequest request) {

        String codeProblem = ExecutionCode.describeProblem(request.getExecutionCode());
        if (codeProblem != null) {
            return codeProblem;
        }

        if (request.getOpenTime() == null || request.getCloseTime() == null) {
            return "Set both an opening and a closing moment.";
        }
        if (!request.getCloseTime().isAfter(request.getOpenTime())) {
            return "The closing moment must be after the opening moment.";
        }

        // A window that has already closed can never be started, so releasing into
        // one is always a mistake - usually a date typed in the wrong month.
        if (!request.getCloseTime().isAfter(LocalDateTime.now())) {
            return "That window has already closed. Students can only start while it is open, "
                 + "so nobody would be able to sit this exam.";
        }

        if (request.getAllocatedDuration() <= 0) {
            return "The time allowed must be a positive number of minutes.";
        }
        if (request.getAllocatedDuration() > MAX_DURATION_MINUTES) {
            return "The time allowed cannot exceed " + MAX_DURATION_MINUTES + " minutes ("
                 + (MAX_DURATION_MINUTES / 60) + " hours).";
        }

        if (request.getMaxAttempts() < 1) {
            return "Allow at least one attempt.";
        }
        if (request.getMaxAttempts() > MAX_ATTEMPTS) {
            return "More than " + MAX_ATTEMPTS + " attempts is almost certainly a mistake.";
        }

        // Not an error: a short window is a legitimate way to make everyone start
        // together. But a window shorter than the exam guarantees that a student
        // starting late works past its close, which surprises teachers who read the
        // window as "the exam is over at 12:00" - so it is worth saying out loud.
        long windowMinutes = Duration.between(request.getOpenTime(),
                                              request.getCloseTime()).toMinutes();
        if (windowMinutes < 1) {
            return "The window must be at least a minute long.";
        }

        return null;
    }
}
