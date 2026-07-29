package hsts.server.control;

import hsts.common.entity.Exam;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.User;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.ExamDecision;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Response;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.UserDAO;
import hsts.server.push.PushService;

import java.sql.SQLException;
import java.util.List;

/**
 * SUC-5 / מתווה scenario 4: the subject coordinator approves or rejects an exam.
 *
 * <p>Requirement 30 - every exam needs her approval before anyone can sit it.
 * Requirement 31 - she may only decide on exams in the subject she coordinates.
 * Requirement 33 - a rejection reason is stored <em>and sent</em> to the teacher.</p>
 *
 * <h2>The "sent" in requirement 33</h2>
 *
 * <p>The wording is "סיבת הדחייה תישלח למורה ותישמר במערכת" - the reason is sent
 * to the teacher and saved. Saving it alone would satisfy the second half only,
 * and the teacher would have to think to go and look. Combined with NFR 18, which
 * forbids a manual refresh, that means the server has to speak first - so every
 * decision here is pushed to the author.</p>
 */
public class ExamApprovalController {

    private final ExamDAO examDAO;
    private final UserDAO userDAO;
    private final PushService pushService;

    public ExamApprovalController(ExamDAO examDAO, UserDAO userDAO, PushService pushService) {
        this.examDAO = examDAO;
        this.userDAO = userDAO;
        this.pushService = pushService;
    }

    /** Exams awaiting a decision in this coordinator's subject, and only hers. */
    public Response listPending(User user) {
        if (!(user instanceof SubjectCoordinator coordinator)) {
            return Response.error("Only a subject coordinator approves exams.");
        }
        String subject = coordinator.getCoordinatedSubjectCode();
        if (subject == null) {
            return Response.error("You are not assigned to coordinate a subject.");
        }
        try {
            List<Exam> pending = examDAO.findPendingBySubject(subject);
            return Response.ok(pending, pending.isEmpty()
                    ? "Nothing is waiting for your approval."
                    : pending.size() + " exam(s) waiting for your approval.");
        } catch (SQLException e) {
            return Response.error("Could not load the pending exams: " + e.getMessage());
        }
    }

    /** Approves an exam. After this it may be released to a class. */
    public Response approve(User user, ExamDecision decision) {
        return decide(user, decision, true);
    }

    /** Rejects an exam. The reason is mandatory and goes back to the author. */
    public Response reject(User user, ExamDecision decision) {
        return decide(user, decision, false);
    }

    private Response decide(User user, ExamDecision decision, boolean approving) {
        if (decision == null) {
            return Response.error("No decision was sent.");
        }
        if (!(user instanceof SubjectCoordinator coordinator)) {
            return Response.error("Only a subject coordinator approves exams.");
        }

        // Requirement 33: a rejection without a reason is not a rejection, it is
        // just a refusal the teacher cannot act on.
        String reason = decision.getReason();
        if (!approving && (reason == null || reason.trim().isEmpty())) {
            return Response.error(
                    "A rejection needs a reason. The teacher receives it and has to know "
                  + "what to change.");
        }

        try {
            Exam exam = examDAO.findByIdAndVersion(decision.getExamId(), decision.getVersion());
            if (exam == null) {
                return Response.error("No exam " + decision.getExamId()
                                    + " version " + decision.getVersion() + ".");
            }

            // Requirement 31: her subject only.
            if (!coordinator.coordinates(exam.getSubjectCode())) {
                return Response.error(
                        "That exam belongs to a subject you do not coordinate.");
            }

            // An exam that has already been decided must not be quietly decided
            // again - two coordinators, or a stale screen, could otherwise
            // overwrite each other's decision without either noticing.
            if (exam.getStatus() != ExamStatus.PENDING_APPROVAL) {
                return Response.error("Exam " + exam.getExamId() + " version "
                        + exam.getVersion() + " has already been "
                        + exam.getStatus().getDisplayName().toLowerCase() + ".");
            }

            ExamStatus newStatus = approving ? ExamStatus.APPROVED : ExamStatus.REJECTED;
            examDAO.updateStatus(exam.getExamId(), exam.getVersion(), newStatus,
                                 approving ? null : reason.trim(), user.getUserId());

            notifyAuthor(exam, coordinator, approving, reason);

            return Response.ok(exam.getExamId(), approving
                    ? "Exam " + exam.getExamId() + " approved. It can now be released to a class."
                    : "Exam " + exam.getExamId() + " rejected. The reason has been sent to "
                      + exam.getAuthorName() + ".");

        } catch (SQLException e) {
            return Response.error("Could not record the decision: " + e.getMessage());
        }
    }

    /**
     * Tells the author, if she is signed in.
     *
     * <p>Wrapped so that a push failure cannot undo a decision that is already
     * committed. The database is the record; the message is a courtesy.</p>
     */
    private void notifyAuthor(Exam exam, SubjectCoordinator decidedBy,
                              boolean approving, String reason) {
        try {
            User author = userDAO.findById(exam.getAuthorId());
            if (author == null) {
                return;
            }

            String message = approving
                    ? "Your exam " + exam.getExamId() + " was approved by "
                      + decidedBy.getFullName() + ". You can now release it to a class."
                    : "Your exam " + exam.getExamId() + " was rejected by "
                      + decidedBy.getFullName() + ".\nReason: " + reason.trim();

            pushService.toUsername(author.getUsername(), new PushEvent(
                    approving ? PushType.EXAM_APPROVED : PushType.EXAM_REJECTED,
                    exam.getExamId(),
                    message));
        } catch (SQLException e) {
            // Nothing to do: the decision stands, she will see it on her next visit.
            System.err.println("Could not notify the author of " + exam.getExamId()
                             + ": " + e.getMessage());
        }
    }

    /**
     * Tells the right coordinator that a new exam is waiting.
     *
     * <p>Called after an exam is saved, so a coordinator with the approval screen
     * open sees it appear without doing anything.</p>
     */
    public void notifyCoordinatorOfNewExam(Exam exam) {
        try {
            for (User user : userDAO.findAll()) {
                if (user instanceof SubjectCoordinator coordinator
                        && coordinator.coordinates(exam.getSubjectCode())) {
                    pushService.toUsername(coordinator.getUsername(), new PushEvent(
                            PushType.EXAM_AWAITING_APPROVAL,
                            exam.getExamId(),
                            "Exam " + exam.getExamId() + " by " + exam.getAuthorName()
                          + " is waiting for your approval."));
                }
            }
        } catch (SQLException e) {
            System.err.println("Could not notify a coordinator about " + exam.getExamId()
                             + ": " + e.getMessage());
        }
    }
}
