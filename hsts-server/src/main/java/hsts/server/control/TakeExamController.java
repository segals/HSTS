package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.Student;
import hsts.common.entity.StudentAnswer;
import hsts.common.entity.StudentExam;
import hsts.common.entity.User;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.AnswerChoice;
import hsts.common.protocol.Response;
import hsts.common.protocol.StartExamRequest;
import hsts.common.util.ExecutionCode;
import hsts.common.util.IsraeliId;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.CodeAttemptDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.SubmissionDAO;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-7 / מתווה scenario 6: a student sitting an exam.
 *
 * <p>She types the code the teacher read out, then her identity number, and the
 * clock starts. She answers, and either submits or is closed by the clock.</p>
 *
 * <h2>Everything about time is decided here, on the server</h2>
 *
 * <p>The client displays a countdown but decides nothing. Her deadline is written
 * to the database when she starts and every later judgement - is she still
 * allowed to answer, has she run out, how long did she actually take - is made
 * against that stored value. Acceptance test 2.11 already required this, and it
 * also means the two laptops' clocks disagreeing cannot change anyone's grade.</p>
 */
public class TakeExamController {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm");

    private final ExecutionDAO executionDAO;
    private final SubmissionDAO submissionDAO;
    private final ExamDAO examDAO;
    private final CodeAttemptDAO codeAttemptDAO;

    public TakeExamController(ExecutionDAO executionDAO, SubmissionDAO submissionDAO,
                              ExamDAO examDAO, CodeAttemptDAO codeAttemptDAO) {
        this.executionDAO = executionDAO;
        this.submissionDAO = submissionDAO;
        this.examDAO = examDAO;
        this.codeAttemptDAO = codeAttemptDAO;
    }

    // -----------------------------------------------------------------
    //  Step 1 - the code
    // -----------------------------------------------------------------

    /**
     * Checks the code the teacher read out.
     *
     * <p>Deliberately does <b>not</b> return the questions. She has not identified
     * herself yet, and the paper is not handed over until she does.</p>
     */
    public Response validateCode(User user, String typedCode) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student sits an exam.");
        }

        try {
            // Requirement 39, before anything else: if she is locked out she is
            // locked out, and a lucky guess must not carry her past it.
            java.time.Duration locked = codeAttemptDAO.remainingLock(user.getUserId());
            if (locked != null) {
                return Response.error("Too many wrong codes. Try again in "
                        + describe(locked) + ".");
            }
        } catch (SQLException e) {
            return Response.error("Could not check your attempts: " + e.getMessage());
        }

        String code = ExecutionCode.normalise(typedCode);
        if (code == null) {
            // A badly-formed code is a typing slip, not a guess at somebody else's
            // sitting, so it does NOT count against her three.
            return Response.error(ExecutionCode.describeProblem(typedCode));
        }

        try {
            ExamExecution execution = executionDAO.findByCode(code);
            if (execution == null) {
                // Same wording as an inactive sitting, so the code cannot be used
                // to discover which codes exist (acceptance test 2.2).
                return Response.error(countWrongCode(user.getUserId(),
                        "That code is not valid, or the exam is not active now."));
            }

            Exam exam = examDAO.findByIdAndVersion(execution.getExamId(),
                                                   execution.getExamVersion());
            if (exam == null) {
                return Response.error("The exam behind that code could not be loaded.");
            }

            // Requirement 21: only students enrolled in the course.
            if (!student.isEnrolledIn(exam.getCourseCode())) {
                return Response.error("You are not enrolled in that course.");
            }

            LocalDateTime now = LocalDateTime.now();

            if (execution.isNotYetOpenAt(now)) {
                return Response.error("This exam opens at "
                        + WHEN.format(execution.getOpenTime()) + ".");
            }
            if (execution.hasClosedAt(now)) {
                // Acceptance test 2.10. The wording matches the מתווה: it is the
                // period for STARTING that has ended.
                return Response.error("The period for starting this exam ended at "
                        + WHEN.format(execution.getCloseTime()) + ".");
            }

            // Already sitting it? Send her straight back in rather than refusing.
            StudentExam running = submissionDAO.findInProgressFor(
                    execution.getExecutionId(), student.getUserId());
            if (running != null) {
                return Response.ok(execution,
                        "You are already sitting this exam. Enter your ID number to carry on "
                      + "where you left off - your time has kept running.");
            }

            // Acceptance test 2.8 and requirement 61: attempts are limited to the
            // sitting's number PLUS anything her teacher has granted her.
            int used = submissionDAO.countAttempts(execution.getExecutionId(),
                                                   student.getUserId());
            int allowed = submissionDAO.attemptsAllowed(execution.getExecutionId(),
                    student.getUserId(), execution.getMaxAttempts());
            if (used >= allowed) {
                int granted = allowed - execution.getMaxAttempts();
                return Response.error(allowed == 1
                        ? "You have already sat this exam. It cannot be taken again "
                          + "unless your teacher allows another attempt."
                        : "You have used all " + allowed + " of your attempts at this exam"
                          + (granted > 0 ? ", including " + granted + " your teacher "
                            + "allowed you" : "") + ".");
            }

            // Requirement 39: three CONSECUTIVE failures. Getting one right wipes
            // the slate, so a mistake this morning cannot combine with two this
            // afternoon to lock her out of an exam she is sitting.
            codeAttemptDAO.clear(user.getUserId());

            return Response.ok(execution, "Code accepted. Enter your ID number to begin.");

        } catch (SQLException e) {
            return Response.error("Could not check that code: " + e.getMessage());
        }
    }

    /**
     * Counts a wrong code and adds what it cost her to the message.
     *
     * <p>The wording tells her how many tries remain, or that she is now locked
     * out. Saying nothing would make the lock arrive from nowhere, and requirement
     * 39 is a deterrent - a deterrent has to be visible to deter.</p>
     *
     * <p>If the counting itself fails, the original refusal is returned unchanged.
     * A database problem must not turn "wrong code" into something confusing.</p>
     */
    private String countWrongCode(String studentId, String refusal) {
        try {
            int failures = codeAttemptDAO.recordFailure(studentId);
            if (failures >= CodeAttemptDAO.STRIKES) {
                return refusal + " That was " + CodeAttemptDAO.STRIKES
                     + " wrong codes, so you cannot try again for "
                     + CodeAttemptDAO.LOCK_FOR.toMinutes() + " minutes.";
            }
            int left = CodeAttemptDAO.STRIKES - failures;
            return refusal + " You have " + left + (left == 1 ? " try" : " tries")
                 + " left before a " + CodeAttemptDAO.LOCK_FOR.toMinutes()
                 + "-minute wait.";
        } catch (SQLException e) {
            System.err.println("Could not record a wrong code: " + e.getMessage());
            return refusal;
        }
    }

    /** "10 minutes", "1 minute", "under a minute" - never "PT9M59S". */
    private static String describe(java.time.Duration left) {
        long minutes = left.toMinutes();
        if (minutes < 1) {
            return "under a minute";
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    // -----------------------------------------------------------------
    //  Step 2 - identify, and start the clock
    // -----------------------------------------------------------------

    /**
     * Verifies her identity number and hands over the paper.
     *
     * <p>System description §4: the clock starts on this step. Her deadline is
     * written now as <em>start + minutes allowed</em>, which is what makes the
     * agreed rule work - the sitting's closing moment governs whether she may
     * begin, not when she must stop.</p>
     */
    public Response startExam(User user, StartExamRequest request) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student sits an exam.");
        }
        if (request == null) {
            return Response.error("Nothing was sent.");
        }

        String typed = request.getTypedIdNumber() == null
                ? "" : request.getTypedIdNumber().trim();

        // Format and check digit first, so an obvious typo gets a useful message
        // rather than "does not match".
        String idProblem = IsraeliId.describeProblem(typed);
        if (idProblem != null) {
            return Response.error(idProblem);
        }
        // Acceptance test 2.4: it must be HER number, not merely a valid one.
        if (!typed.equals(student.getUserId())) {
            return Response.error("That ID number does not match the account you are "
                                + "signed in to. Please try again.");
        }

        try {
            ExamExecution execution = executionDAO.findById(request.getExecutionId());
            if (execution == null) {
                return Response.error("That sitting no longer exists.");
            }

            Exam exam = examDAO.findByIdAndVersion(execution.getExamId(),
                                                   execution.getExamVersion());
            if (exam == null) {
                return Response.error("The exam could not be loaded.");
            }
            if (!student.isEnrolledIn(exam.getCourseCode())) {
                return Response.error("You are not enrolled in that course.");
            }

            LocalDateTime now = LocalDateTime.now();

            // Resuming: her clock never stopped, so give back what is left of it.
            StudentExam running = submissionDAO.findInProgressFor(
                    execution.getExecutionId(), student.getUserId());
            if (running != null) {
                return Response.ok(loadPaper(running, exam),
                        "Welcome back. Your time has kept running while you were away.");
            }

            // Starting fresh: the window must still be open.
            if (execution.isNotYetOpenAt(now)) {
                return Response.error("This exam opens at "
                        + WHEN.format(execution.getOpenTime()) + ".");
            }
            if (execution.hasClosedAt(now)) {
                return Response.error("The period for starting this exam ended at "
                        + WHEN.format(execution.getCloseTime()) + ".");
            }

            // The same sum as the code step, from the same place, so the two cannot
            // disagree about how many attempts she has.
            int used = submissionDAO.countAttempts(execution.getExecutionId(),
                                                   student.getUserId());
            if (used >= submissionDAO.attemptsAllowed(execution.getExecutionId(),
                    student.getUserId(), execution.getMaxAttempts())) {
                return Response.error("You have used all your attempts at this exam.");
            }

            StudentExam attempt = new StudentExam();
            attempt.setExecutionId(execution.getExecutionId());
            attempt.setStudentId(student.getUserId());
            attempt.setStudentName(student.getFullName());
            attempt.setAttemptNo(used + 1);
            attempt.setStartTime(now.withNano(0));
            // THE line that implements the agreed rule: her own full time,
            // measured from when she began, regardless of the window's close.
            attempt.setDeadline(now.withNano(0).plusMinutes(execution.getAllocatedDuration()));
            attempt.setStatus(SubmissionStatus.IN_PROGRESS);

            submissionDAO.insert(attempt);

            // Blank answers up front, so a question she never touches still exists
            // as a row and is marked wrong rather than silently missing.
            for (var eq : exam.getQuestions()) {
                submissionDAO.saveAnswer(attempt.getSubmissionId(),
                        eq.getQuestionId(), eq.getQuestionVersion(), null);
            }

            return Response.ok(loadPaper(attempt, exam),
                    "Good luck. You have " + execution.getAllocatedDuration()
                  + " minutes, until " + WHEN.format(attempt.getDeadline()) + ".");

        } catch (SQLException e) {
            return Response.error("Could not start the exam: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Step 3 - answering
    // -----------------------------------------------------------------

    /** Records one choice. Refused once her time is up. */
    public Response saveAnswer(User user, AnswerChoice choice) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student sits an exam.");
        }
        if (choice == null) {
            return Response.error("Nothing was sent.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(choice.getSubmissionId());
            String refusal = refuseIfNotHerLiveAttempt(student, attempt);
            if (refusal != null) {
                return Response.error(refusal);
            }

            Integer chosen = choice.getSelectedAnswerNo();
            if (chosen != null && (chosen < 1 || chosen > 4)) {
                return Response.error("An answer must be 1, 2, 3 or 4.");
            }

            submissionDAO.saveAnswer(choice.getSubmissionId(), choice.getQuestionId(),
                    choice.getQuestionVersion(), chosen);

            return Response.ok(attempt.secondsRemainingAt(LocalDateTime.now()), null);

        } catch (SQLException e) {
            return Response.error("Could not save that answer: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Step 4 - handing in
    // -----------------------------------------------------------------

    /** She pressed Submit. */
    public Response submitExam(User user, Integer submissionId) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student sits an exam.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            if (attempt == null) {
                return Response.error("That attempt does not exist.");
            }
            if (!attempt.getStudentId().equals(student.getUserId())) {
                return Response.error("That is not your exam.");
            }
            if (!attempt.isInProgress()) {
                // Reached when the clock closed it first, or she pressed twice.
                // The answers are loaded here too: a reply whose shape depends on
                // which branch produced it is a trap for every screen that reads it.
                attempt.setAnswers(submissionDAO.findAnswers(submissionId));
                return Response.ok(attempt, attempt.getStatus() == SubmissionStatus.TIMED_OUT
                        ? "Your time had already run out, so the exam was closed for you. "
                          + "Everything you had chosen was saved."
                        : "This exam has already been handed in.");
            }

            LocalDateTime now = LocalDateTime.now();

            // If her time ran out a moment ago, this is a time-out however hard she
            // pressed Submit. The stored deadline decides, not the arrival time.
            boolean expired = attempt.isExpiredAt(now);
            LocalDateTime endedAt = expired ? attempt.getDeadline() : now.withNano(0);
            SubmissionStatus status = expired
                    ? SubmissionStatus.TIMED_OUT : SubmissionStatus.FINISHED;

            boolean closedByUs = submissionDAO.finish(submissionId, status, endedAt,
                    minutesBetween(attempt.getStartTime(), endedAt));

            StudentExam finished = submissionDAO.findById(submissionId);
            // Load the answers back onto it. findById reads only the attempt row,
            // so without this the object handed back looks as though she answered
            // nothing - which is alarming on a screen that has just told her the
            // exam is over, and wrong for the results screen in milestone 10.
            finished.setAnswers(submissionDAO.findAnswers(submissionId));

            return Response.ok(finished, closedByUs
                    ? (expired
                        ? "Your time ran out, so the exam was closed for you. "
                          + "Everything you had chosen was saved."
                        : "Handed in. You took " + finished.getActualDuration() + " minutes.")
                    : "This exam was already closed.");

        } catch (SQLException e) {
            return Response.error("Could not hand in the exam: " + e.getMessage());
        }
    }

    /** Her attempt that is still running, so the client can resume after a restart. */
    public Response resumeInProgress(User user, Integer submissionId) {
        if (!(user instanceof Student student)) {
            return Response.error("Only a student sits an exam.");
        }
        try {
            StudentExam attempt = submissionDAO.findById(submissionId);
            String refusal = refuseIfNotHerLiveAttempt(student, attempt);
            if (refusal != null) {
                return Response.error(refusal);
            }
            ExamExecution execution = executionDAO.findById(attempt.getExecutionId());
            Exam exam = examDAO.findByIdAndVersion(execution.getExamId(),
                                                   execution.getExamVersion());
            return Response.ok(loadPaper(attempt, exam), null);
        } catch (SQLException e) {
            return Response.error("Could not reload the exam: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    /**
     * Fills an attempt with the paper and her answers so far.
     *
     * <p>The teacher's private notes are <b>not</b> copied in. System description
     * §3.2 calls them "ממל שלא נראה ע"י הנבחנת", and acceptance test 4.10 checks
     * they stay hidden - so they are simply never put on the object that travels
     * to a student's screen.</p>
     */
    private StudentExam loadPaper(StudentExam attempt, Exam exam) throws SQLException {
        attempt.setExamId(exam.getExamId());
        attempt.setCourseName(exam.getCourseName());
        attempt.setInstructionsForStudents(exam.getInstructionsForStudents());
        attempt.setQuestions(stripCorrectAnswers(exam));
        attempt.setAnswers(submissionDAO.findAnswers(attempt.getSubmissionId()));
        return attempt;
    }

    /**
     * Copies the paper with the correct answers removed.
     *
     * <p>The exam object carries {@code isCorrect} on every option, and the whole
     * thing would otherwise be serialised to the student's computer. Anyone able
     * to read the traffic - or a modified client - would have the answer key. So
     * the flags are cleared before the paper leaves the server.</p>
     */
    private List<hsts.common.entity.ExamQuestion> stripCorrectAnswers(Exam exam) {
        List<hsts.common.entity.ExamQuestion> safe = new ArrayList<>();
        for (var original : exam.getQuestions()) {
            var copy = new hsts.common.entity.ExamQuestion(original.getQuestionId(),
                    original.getQuestionVersion(), original.getPoints(), original.getOrder());

            var q = original.getQuestion();
            if (q != null) {
                var blank = new hsts.common.entity.Question();
                blank.setQuestionId(q.getQuestionId());
                blank.setVersion(q.getVersion());
                blank.setCourseCode(q.getCourseCode());
                blank.setText(q.getText());
                blank.setInstructions(q.getInstructions());
                blank.setTopic(q.getTopic());
                blank.setDifficulty(q.getDifficulty());
                blank.setImage(q.getImage());

                List<hsts.common.entity.Answer> options = new ArrayList<>();
                for (var a : q.getAnswers()) {
                    // isCorrect deliberately left false on every option.
                    options.add(new hsts.common.entity.Answer(a.getAnswerNo(), a.getText(), false));
                }
                blank.setAnswers(options);
                copy.setQuestion(blank);
            }
            safe.add(copy);
        }
        return safe;
    }

    private String refuseIfNotHerLiveAttempt(Student student, StudentExam attempt) {
        if (attempt == null) {
            return "That attempt does not exist.";
        }
        if (!attempt.getStudentId().equals(student.getUserId())) {
            // Requirement 57 in spirit: never another student's paper.
            return "That is not your exam.";
        }
        if (!attempt.isInProgress()) {
            return "This exam has already been handed in.";
        }
        if (attempt.isExpiredAt(LocalDateTime.now())) {
            return "Your time is up. The exam is closed.";
        }
        return null;
    }

    /** Whole minutes, rounded up, so a 30-second exam reads as 1 rather than 0. */
    static int minutesBetween(LocalDateTime from, LocalDateTime to) {
        long seconds = Math.max(0, Duration.between(from, to).toSeconds());
        return (int) ((seconds + 59) / 60);
    }
}
