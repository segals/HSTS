package hsts.server.control;

import hsts.common.entity.Course;
import hsts.common.entity.Exam;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.Question;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.ExamBuildCriteria;
import hsts.common.protocol.Response;
import hsts.server.control.strategy.AutomaticBuildStrategy;
import hsts.server.control.strategy.ExamBuildStrategy;
import hsts.server.control.strategy.InsufficientQuestionsException;
import hsts.server.control.strategy.ManualBuildStrategy;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.QuestionDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-3 and SUC-4: building an exam, by hand or automatically.
 *
 * <p>Covers מתווה scenario 3 - choose the questions either way, set the duration,
 * set the points, add instructions for the students and hidden notes for the
 * teacher, and edit an existing exam so that the previous one stays in the bank.</p>
 *
 * <h2>Where the Strategy pattern earns its place</h2>
 *
 * <p>{@link #buildDraft} picks a strategy once, then calls it. There is no
 * {@code if (automatic)} in the rest of this class: everything after the questions
 * are chosen - the id, the points, the duration, the validation, the saving - is
 * identical whichever way they were chosen. That is precisely the shape the
 * pattern is for, and it is why a third way of building would be one new class
 * and no edits here.</p>
 */
public class ExamBuilderController {

    /** Sanity bound on the duration. A whole school day is already generous. */
    private static final int MAX_DURATION_MINUTES = 600;

    private final ExamDAO examDAO;
    private final QuestionDAO questionDAO;
    private final CourseDAO courseDAO;

    public ExamBuilderController(ExamDAO examDAO, QuestionDAO questionDAO, CourseDAO courseDAO) {
        this.examDAO = examDAO;
        this.questionDAO = questionDAO;
        this.courseDAO = courseDAO;
    }

    /**
     * Chooses the questions and returns a draft exam. <b>Nothing is saved.</b>
     *
     * <p>The teacher gets the draft back, adjusts points, duration and
     * instructions, and only then saves. That two-step flow is what the submitted
     * sequence diagram for SUC-3 shows, and it is what makes requirement 29
     * possible to honour: if the bank cannot meet the criteria, the refusal
     * happens before anything exists.</p>
     */
    public Response buildDraft(User user, ExamBuildCriteria criteria) {
        if (criteria == null) {
            return Response.error("No criteria were sent.");
        }
        String refusal = refuseIfCannotUseCourse(user, criteria.getCourseCode());
        if (refusal != null) {
            return Response.error(refusal);
        }

        try {
            Course course = courseDAO.findById(criteria.getCourseCode());
            if (course == null) {
                return Response.error("No course with code " + criteria.getCourseCode() + ".");
            }

            List<Question> pool = questionDAO.findCurrentByCourse(criteria.getCourseCode());
            if (pool.isEmpty()) {
                // מתווה scenario 3, branch ב: an empty bank is its own message,
                // and the teacher is pointed at what to do about it.
                return Response.error(
                        "There are no questions in this course yet. Add some to the "
                      + "question bank before building an exam.");
            }

            // ---- Strategy: chosen once, then simply used ----
            ExamBuildStrategy strategy = criteria.isAutomatic()
                    ? new AutomaticBuildStrategy()
                    : new ManualBuildStrategy();

            List<Question> chosen;
            try {
                chosen = strategy.selectQuestions(criteria, pool);
            } catch (InsufficientQuestionsException e) {
                // Requirement 29 - say so, and create nothing.
                return Response.error(e.getMessage());
            }

            Exam draft = new Exam();
            draft.setCourseCode(course.getCourseCode());
            draft.setSubjectCode(course.getSubjectCode());
            draft.setCourseName(course.getName());
            draft.setAuthorId(user.getUserId());
            draft.setAuthorName(user.getFullName());
            draft.setDurationMinutes(60);

            List<ExamQuestion> examQuestions = new ArrayList<>();
            int order = 1;
            for (Question question : chosen) {
                // The version is pinned here, at build time. If the question is
                // edited afterwards, this exam keeps the wording it was built from.
                examQuestions.add(new ExamQuestion(
                        question.getQuestionId(), question.getVersion(), 0, order++));
                examQuestions.get(examQuestions.size() - 1).setQuestion(question);
            }
            draft.setQuestions(examQuestions);
            draft.distributePointsEvenly();

            return Response.ok(draft,
                    "Draft built " + strategy.getName() + ": " + chosen.size()
                  + " questions, " + Exam.REQUIRED_TOTAL_POINTS
                  + " points shared between them. Nothing is saved yet.");

        } catch (SQLException e) {
            return Response.error("Could not build the exam: " + e.getMessage());
        }
    }

    /** Saves a new exam. It goes straight to the coordinator's queue. */
    public Response saveExam(User user, Exam exam) {
        if (exam == null) {
            return Response.error("No exam was sent.");
        }
        String refusal = refuseIfCannotUseCourse(user, exam.getCourseCode());
        if (refusal != null) {
            return Response.error(refusal);
        }
        String invalid = validate(exam);
        if (invalid != null) {
            return Response.error(invalid);
        }

        try {
            Course course = courseDAO.findById(exam.getCourseCode());
            if (course == null) {
                return Response.error("No course with code " + exam.getCourseCode() + ".");
            }
            exam.setName(exam.getName().trim());
            exam.setSubjectCode(course.getSubjectCode());
            exam.setAuthorId(user.getUserId());
            exam.setExamId(examDAO.generateNextExamId(course.getCourseCode(),
                                                      course.getSubjectCode()));
            renumber(exam);
            examDAO.insert(exam);

            return Response.ok(exam,
                    "\"" + exam.getName() + "\" saved as exam " + exam.getExamId()
                  + " and sent to the subject coordinator for approval.");
        } catch (SQLException e) {
            return Response.error("Could not save the exam: " + e.getMessage());
        }
    }

    /**
     * Saves an edit as a new version.
     *
     * <p>מתווה scenario 3 item 5: "ניתן לערוך מבחן קיים. המבחן הקודם נשאר במאגר".</p>
     */
    public Response editExam(User user, Exam edited) {
        if (edited == null || edited.getExamId() == null) {
            return Response.error("No exam was sent.");
        }
        try {
            Exam existing = examDAO.findById(edited.getExamId());
            if (existing == null) {
                return Response.error("No exam with id " + edited.getExamId() + ".");
            }
            if (!existing.getAuthorId().equals(user.getUserId())) {
                return Response.error("Only the teacher who wrote an exam may edit it.");
            }
            String refusal = refuseIfCannotUseCourse(user, existing.getCourseCode());
            if (refusal != null) {
                return Response.error(refusal);
            }

            // Course and subject are baked into the 6-digit id, so they cannot
            // change. Filled in from the stored exam BEFORE validation, because
            // they are not the client's to supply.
            edited.setCourseCode(existing.getCourseCode());
            edited.setSubjectCode(existing.getSubjectCode());
            edited.setAuthorId(user.getUserId());
            if (edited.getName() != null) {
                edited.setName(edited.getName().trim());
            }

            String invalid = validate(edited);
            if (invalid != null) {
                return Response.error(invalid);
            }

            // The same rule as a question: an exact copy of the version before it
            // records nothing, and a second press of Save is the ordinary way to
            // make one. It matters more here than on a question, because a new
            // exam version has to be approved all over again - so an accidental
            // one would put a working exam back in front of the coordinator.
            renumber(edited);
            if (isTheSame(existing, edited)) {
                return Response.error("No changes were made. \"" + existing.getName()
                        + "\" is still version " + existing.getVersion()
                        + ", and it keeps the approval it already has.");
            }

            int newVersion = examDAO.createNewVersion(edited);

            return Response.ok(edited,
                    "Saved as version " + newVersion + ". Version " + (newVersion - 1)
                  + " is still in the bank. The new version needs approval again.");
        } catch (SQLException e) {
            return Response.error("Could not save the edit: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    public Response listMyExams(User user) {
        if (!(user instanceof Teacher)) {
            return Response.error("Only a teacher has exams to list.");
        }
        try {
            List<Exam> exams = examDAO.findCurrentByAuthor(user.getUserId());
            return Response.ok(exams, exams.isEmpty()
                    ? "You have not written any exams yet."
                    : exams.size() + " exam(s).");
        } catch (SQLException e) {
            return Response.error("Could not load your exams: " + e.getMessage());
        }
    }

    public Response getExam(User user, String examId, int version) {
        try {
            Exam exam = (version > 0)
                    ? examDAO.findByIdAndVersion(examId, version)
                    : examDAO.findById(examId);
            if (exam == null) {
                return Response.error("No exam with id " + examId + ".");
            }
            String refusal = refuseIfCannotViewExam(user, exam);
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(exam, null);
        } catch (SQLException e) {
            return Response.error("Could not load the exam: " + e.getMessage());
        }
    }

    public Response listVersions(User user, String examId) {
        try {
            List<Exam> versions = examDAO.findAllVersionsWithQuestions(examId);
            if (versions.isEmpty()) {
                return Response.error("No exam with id " + examId + ".");
            }
            String refusal = refuseIfCannotViewExam(user, versions.get(0));
            if (refusal != null) {
                return Response.error(refusal);
            }
            return Response.ok(versions, versions.size() + " version(s) stored.");
        } catch (SQLException e) {
            return Response.error("Could not load the version history: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  Validation
    // -----------------------------------------------------------------

    /**
     * Checks one exam, returning the first problem in words the user can act on.
     *
     * <p>The 100-point rule is the interesting one. It appears in מתווה scenario 3
     * note 3 and in acceptance test 1.5, but in <b>no numbered requirement</b> -
     * a gap found while reading the documents in phase 0. It is enforced here
     * because the מתווה is the acceptance bar.</p>
     */
    /** The longest name that still fits a list row without pushing the number off it. */
    private static final int MAX_NAME_LENGTH = 120;

    /**
     * Whether an edit would store an exact copy of the version already there.
     *
     * <p>Everything the author can change: the name, the duration, both sets of
     * instructions, and the questions with their marks in the order they appear.
     * The id, the version, the author and the approval state are not hers to
     * change and say nothing about whether the exam did.</p>
     */
    private static boolean isTheSame(Exam before, Exam after) {
        if (!same(before.getName(), after.getName())
                || before.getDurationMinutes() != after.getDurationMinutes()
                || !same(before.getInstructionsForStudents(),
                         after.getInstructionsForStudents())
                || !same(before.getNotesForTeacher(), after.getNotesForTeacher())) {
            return false;
        }
        List<hsts.common.entity.ExamQuestion> wasThere = before.getQuestions();
        List<hsts.common.entity.ExamQuestion> isThere  = after.getQuestions();
        if (wasThere == null || isThere == null || wasThere.size() != isThere.size()) {
            return wasThere == isThere;
        }
        for (int i = 0; i < wasThere.size(); i++) {
            hsts.common.entity.ExamQuestion a = wasThere.get(i);
            hsts.common.entity.ExamQuestion b = isThere.get(i);
            if (!same(a.getQuestionId(), b.getQuestionId())
                    || a.getQuestionVersion() != b.getQuestionVersion()
                    || a.getPoints() != b.getPoints()) {
                return false;
            }
        }
        return true;
    }

    /** Blank, empty and absent all count as the same value. */
    private static boolean same(String a, String b) {
        String left  = (a == null) ? "" : a.trim();
        String right = (b == null) ? "" : b.trim();
        return left.equals(right);
    }

    private String validate(Exam exam) {
        // Compulsory, at the customer's request. The 6-digit number is unique and
        // never changes, but nobody remembers which exam "020101" is - and a name
        // that may be left blank is a name half the exams will not have.
        if (exam.getName() == null || exam.getName().isBlank()) {
            return "Give the exam a name. Something a colleague would recognise, "
                 + "like \"Plane Geometry mid-term\".";
        }
        if (exam.getName().trim().length() > MAX_NAME_LENGTH) {
            return "That name is too long. Keep it under " + MAX_NAME_LENGTH
                 + " characters so it fits on a list.";
        }
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            // Acceptance test 1.4.
            return "An exam must contain at least one question.";
        }
        if (exam.getDurationMinutes() <= 0) {
            // Acceptance test 1.8.
            return "The exam duration must be a positive number of minutes.";
        }
        if (exam.getDurationMinutes() > MAX_DURATION_MINUTES) {
            return "An exam cannot last longer than " + MAX_DURATION_MINUTES
                 + " minutes (" + (MAX_DURATION_MINUTES / 60) + " hours).";
        }

        for (ExamQuestion eq : exam.getQuestions()) {
            if (eq.getPoints() <= 0) {
                return "Every question must be worth at least one point.";
            }
        }

        int total = exam.getTotalPoints();
        if (total != Exam.REQUIRED_TOTAL_POINTS) {
            // Acceptance test 1.5.
            return "The points must add up to exactly " + Exam.REQUIRED_TOTAL_POINTS
                 + ". They currently add up to " + total
                 + " (" + (total > Exam.REQUIRED_TOTAL_POINTS ? "+" : "")
                 + (total - Exam.REQUIRED_TOTAL_POINTS) + ").";
        }

        // A question twice in one exam would be marked twice and confuse the student.
        List<String> seen = new ArrayList<>();
        for (ExamQuestion eq : exam.getQuestions()) {
            if (seen.contains(eq.getQuestionId())) {
                return "Question " + eq.getQuestionId() + " appears twice in this exam.";
            }
            seen.add(eq.getQuestionId());
        }
        return null;
    }

    /** Makes the display order 1..n and contiguous, whatever the client sent. */
    private void renumber(Exam exam) {
        int order = 1;
        for (ExamQuestion eq : exam.getQuestions()) {
            eq.setOrder(order++);
        }
    }

    /**
     * May this user <em>read</em> this exam?
     *
     * <p>Wider than {@link #refuseIfCannotUseCourse}, and it has to be. Requirement
     * 31 puts a subject coordinator in charge of approving every exam in her
     * subject - but she does not necessarily <em>teach</em> the course it belongs
     * to. Coordinator 1 coordinates Mathematics and teaches Algebra; an exam for
     * Plane Geometry is hers to approve and not hers to teach.</p>
     *
     * <p>Checking only "do you teach this course" therefore let her see an exam in
     * her approval queue and refused to open it - visible in the queue, impossible
     * to read, and so impossible to decide on.</p>
     *
     * <p>The coordinator test comes first because {@code SubjectCoordinator}
     * extends {@code Teacher}: the teacher branch would otherwise catch her and
     * reject her on the course she does not teach.</p>
     */
    private String refuseIfCannotViewExam(User user, Exam exam) {
        if (user instanceof SubjectCoordinator coordinator
                && coordinator.coordinates(exam.getSubjectCode())) {
            return null;
        }
        if (user instanceof Teacher teacher && teacher.teaches(exam.getCourseCode())) {
            return null;
        }
        return "You may only view exams for courses you teach, or exams in a subject "
             + "you coordinate.";
    }

    /** Requirement 20: a teacher builds exams only for courses she teaches. */
    private String refuseIfCannotUseCourse(User user, String courseCode) {
        if (courseCode == null || courseCode.isBlank()) {
            return "No course was chosen.";
        }
        if (!(user instanceof Teacher teacher)) {
            return "Only a teacher can build exams.";
        }
        if (!teacher.teaches(courseCode)) {
            return "You do not teach that course, so you cannot build exams for it.";
        }
        return null;
    }
}
