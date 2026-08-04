package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.Response;
import hsts.common.protocol.TodoItem;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.ExecutionDAO;
import hsts.server.dao.GradeDAO;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What one member of staff still has to do.
 *
 * <h2>Why this is not the badge counts again</h2>
 *
 * <p>The badges answer "how many" for one menu entry each. A to-do list answers a
 * different question - "what should I do next" - and that includes things no badge
 * shows: an exam of hers approved but never given to a class, one the coordinator
 * has not looked at yet, one that came back rejected and is sitting there.</p>
 *
 * <h2>Two kinds of line</h2>
 *
 * <p>Work that is hers, and work she is waiting on somebody else for. Both belong
 * here: "2 exams with the coordinator" is not something she can act on, but it is
 * the answer to "where is my exam", and leaving it off would send her looking.
 * {@link TodoItem#isMine()} tells them apart.</p>
 *
 * <p>Empty lines are left out entirely. A list of six rows all saying "0" is a list
 * nobody reads twice.</p>
 */
public class TodoController {

    private final ExamDAO examDAO = new ExamDAO();
    private final ExecutionDAO executionDAO = new ExecutionDAO();
    private final GradeDAO gradeDAO = new GradeDAO();

    public Response listFor(User user) {
        if (!(user instanceof Teacher teacher)) {
            // A student's work is her exams, which are already the whole of her menu;
            // the principal changes nothing at all (system description §7.3).
            return Response.error("Only a teacher or a subject coordinator has a to-do list.");
        }
        try {
            List<TodoItem> items = new ArrayList<>();

            // ---- as a coordinator ----
            if (user instanceof SubjectCoordinator coordinator) {
                int waiting = examDAO
                        .findPendingBySubject(coordinator.getCoordinatedSubjectCode()).size();
                if (waiting > 0) {
                    items.add(new TodoItem(
                            plural(waiting, "exam") + " waiting for your approval",
                            "Written by teachers in the subject you coordinate. "
                          + "Nobody can sit them until you decide.",
                            waiting, true, "/fxml/ExamApproval.fxml"));
                }
            }

            // ---- her own exams ----
            List<Exam> hers = examDAO.findCurrentByAuthor(user.getUserId());
            int pending = 0, rejected = 0, approvedNotOut = 0;
            for (Exam exam : hers) {
                if (exam.getStatus() == ExamStatus.PENDING_APPROVAL) {
                    pending++;
                } else if (exam.getStatus() == ExamStatus.REJECTED) {
                    rejected++;
                } else if (exam.getStatus() == ExamStatus.APPROVED
                        && executionDAO.findByExam(exam.getExamId()).isEmpty()) {
                    approvedNotOut++;
                }
            }

            if (rejected > 0) {
                items.add(new TodoItem(
                        plural(rejected, "exam") + " came back rejected",
                        "Your coordinator gave a reason on each. Edit it and it goes "
                      + "back to her as a new version.",
                        rejected, true, "/fxml/ExamBuilder.fxml"));
            }
            if (approvedNotOut > 0 && !teacher.getTaughtCourseCodes().isEmpty()) {
                items.add(new TodoItem(
                        plural(approvedNotOut, "approved exam") + " never given to a class",
                        "Approved and sitting in the drawer. Release one to set a date, "
                      + "a code and how long the class gets.",
                        approvedNotOut, true, "/fxml/ExamRelease.fxml"));
            }

            // ---- marking ----
            int papers = gradeDAO.countAwaitingApprovalBy(user.getUserId());
            if (papers > 0) {
                items.add(new TodoItem(
                        plural(papers, "paper") + " to mark and approve",
                        "Handed in on sittings you released. Nothing reaches a student "
                      + "until you approve it.",
                        papers, true, "/fxml/Grading.fxml"));
            }

            // ---- waiting on somebody else ----
            if (pending > 0) {
                items.add(new TodoItem(
                        plural(pending, "exam") + " with the coordinator",
                        "Waiting for Subject Coordinator approval. There is nothing to "
                      + "do until she decides.",
                        pending, false, "/fxml/ExamBuilder.fxml"));
            }

            return Response.ok(items, items.isEmpty()
                    ? "Nothing waiting. Everything of yours is approved, released and marked."
                    : items.size() + " thing(s) on your list.");

        } catch (SQLException e) {
            return Response.error("Could not work out your list: " + e.getMessage());
        }
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
