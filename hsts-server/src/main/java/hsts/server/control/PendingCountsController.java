package hsts.server.control;

import hsts.common.entity.ExamExecution;
import hsts.common.entity.Student;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.PendingCounts;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.SubmissionDAO;
import hsts.server.dao.UserDAO;

import java.sql.SQLException;

/**
 * How many things are waiting for one user - the numbers in the menu badges.
 *
 * <h2>Only what she herself has to do</h2>
 *
 * <p>Every count here is a queue of <em>her</em> work. An exam she wrote that is
 * sitting with the coordinator does not appear on her badge, because there is
 * nothing for her to do about it; the exam's status says who it is waiting for,
 * and that is a different question. A badge that counted other people's work
 * would never reach nought and would be ignored within a day.</p>
 *
 * <h2>Counted the same way the screens list</h2>
 *
 * <p>Each number is produced by the same query or the same rule as the screen it
 * sits above. The coordinator's badge calls the very method her approval screen
 * calls; the student's "take an exam" badge applies the attempts arithmetic the
 * code screen applies. A badge that disagreed with the screen behind it would be
 * worse than no badge at all - the reader would stop trusting both.</p>
 */
public class PendingCountsController {

    private final ExamDAO examDAO = new ExamDAO();
    private final GradeDAO gradeDAO = new GradeDAO();
    private final ExecutionDAO executionDAO = new ExecutionDAO();
    private final SubmissionDAO submissionDAO = new SubmissionDAO();
    private final UserDAO userDAO = new UserDAO();
    private final hsts.server.dao.CourseDAO courseDAO = new hsts.server.dao.CourseDAO();

    /**
     * The counts for whoever is asking.
     *
     * <p>The principal gets zeros: she approves nothing and marks nothing
     * (system description §7.3), so a badge on her menu could only ever be noise.</p>
     */
    public Response countsFor(User user) {
        if (user == null) {
            return Response.error("Not signed in.");
        }
        try {
            int examsToApprove     = 0;
            int papersToApprove    = 0;
            int examsToSit         = 0;
            int newResults         = 0;
            int examsNewlyApproved = 0;

            // A coordinator is a teacher too, so both of the first two can apply to
            // the same person - and both are counted for her.
            if (user instanceof SubjectCoordinator coordinator) {
                examsToApprove = examDAO
                        .findPendingBySubject(coordinator.getCoordinatedSubjectCode())
                        .size();
            }
            if (user instanceof Teacher) {
                papersToApprove = gradeDAO.countAwaitingApprovalBy(user.getUserId());
                // News rather than work: her exam came back approved. On the menu
                // because the message sent at the moment of the decision only
                // reaches a teacher who happens to be signed in then.
                examsNewlyApproved = userDAO.countNewlyApprovedExams(user.getUserId());
            }
            if (user instanceof Student student) {
                examsToSit = countSittingsSheCanStart(student);
                newResults = userDAO.countUnreadResults(student.getUserId());
            }

            return Response.ok(new PendingCounts(examsToApprove, papersToApprove,
                    examsToSit, newResults, examsNewlyApproved), null);

        } catch (SQLException e) {
            return Response.error("Could not count what is waiting: " + e.getMessage());
        }
    }

    /**
     * Who this user is, in words, for the line under her name on the menu.
     *
     * <p>Answered here because the menu is this class's only reader, and because
     * the answer must come from the {@code course_teacher} table rather than from
     * the question bank's course list - that one is deliberately wider for a
     * coordinator (requirement 19) and would claim she teaches courses she does
     * not.</p>
     */
    public Response contextFor(User user) {
        if (user == null) {
            return Response.error("Not signed in.");
        }
        try {
            java.util.List<hsts.common.entity.Course> taught =
                    (user instanceof Teacher) ? courseDAO.findByTeacher(user.getUserId())
                                              : java.util.List.of();
            java.util.List<hsts.common.entity.Course> enrolled =
                    (user instanceof Student) ? courseDAO.findByStudent(user.getUserId())
                                              : java.util.List.of();

            String subjectCode = null;
            String subjectName = null;
            if (user instanceof SubjectCoordinator coordinator) {
                subjectCode = coordinator.getCoordinatedSubjectCode();
                for (hsts.common.entity.Subject subject : userDAO.findAllSubjects()) {
                    if (subject.getSubjectCode().equals(subjectCode)) {
                        subjectName = subject.getName();
                    }
                }
            }

            return Response.ok(new hsts.common.protocol.MenuContext(
                    taught, enrolled, subjectCode, subjectName), null);

        } catch (SQLException e) {
            return Response.error("Could not load your courses: " + e.getMessage());
        }
    }

    /**
     * Sittings open right now that this student may still go into.
     *
     * <p>Not simply "open sittings on her courses": one she has already sat, with no
     * attempt left, is finished as far as she is concerned and a badge pointing at
     * it would be a lie. The two numbers below are the same ones
     * {@code TakeExamController} uses to let her in or turn her away, so the badge
     * cannot promise something the next screen refuses.</p>
     */
    private int countSittingsSheCanStart(Student student) throws SQLException {
        int count = 0;
        for (ExamExecution sitting : executionDAO.findOpenForStudent(student.getUserId())) {
            int used = submissionDAO.countAttempts(sitting.getExecutionId(), student.getUserId());
            int allowed = submissionDAO.attemptsAllowed(sitting.getExecutionId(),
                    student.getUserId(), sitting.getMaxAttempts());
            if (used < allowed) {
                count++;
            }
        }
        return count;
    }
}
