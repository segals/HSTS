package hsts.client.gui;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.Question;
import hsts.common.protocol.ExamDecision;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * SUC-5 / מתווה scenario 4: the coordinator's approval screen.
 *
 * <p>She reads an exam, then approves it or rejects it with a reason. The reason
 * is required, because requirement 33 has it sent to the teacher - and a
 * rejection the teacher cannot act on is not much of a rejection.</p>
 *
 * <p>There is no Refresh button. New exams arrive by push, which is what NFR 18
 * asks for.</p>
 */
public class ExamApprovalScreenController extends GUIScreen {

    private static final String REQ_PENDING = "a.pending";
    private static final String REQ_GET     = "a.get";
    private static final String REQ_DECIDE  = "a.decide";

    @FXML private Label    subtitleLabel;
    @FXML private Button   backButton;
    @FXML private Label    pendingCountLabel;
    @FXML private ListView<Exam> pendingList;

    @FXML private Label    examTitleLabel;
    @FXML private Label    examMetaLabel;
    @FXML private Label    instructionsLabel;
    @FXML private Label    teacherNotesLabel;
    @FXML private VBox     questionBox;
    @FXML private TextArea reasonArea;
    @FXML private Button   approveButton;
    @FXML private Button   rejectButton;
    @FXML private Label    statusLabel;

    /** The exam currently on screen, loaded in full. */
    private Exam reviewing;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Exams in the subject you coordinate. "
                            + "A rejection needs a reason - it is sent to the teacher.");

        useWrappingCells(pendingList, e ->
                e.getExamId() + "  ·  v" + e.getVersion() + "  ·  " + e.getCourseName()
              + "\n" + e.getAuthorName() + "  ·  " + e.getQuestionCount() + " questions  ·  "
              + e.getDurationMinutes() + " min");

        pendingList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, exam) -> {
                    if (exam != null) {
                        send(RequestType.EXAM_GET,
                             new ExamRef(exam.getExamId(), exam.getVersion()), REQ_GET);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNothingSelected();
        loadPending();
    }

    /**
     * A new exam arrived while this screen was open.
     *
     * <p>Overridden so the list actually reloads rather than only showing a
     * message - "no manual refresh" means the data updates, not just the text.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() == PushType.EXAM_AWAITING_APPROVAL) {
            showMessage(event.getMessage());
            loadPending();
        } else {
            super.onPush(event);
        }
    }

    private void loadPending() {
        send(RequestType.EXAM_PENDING_FOR_COORDINATOR, null, REQ_PENDING);
    }

    // -----------------------------------------------------------------
    //  Decisions
    // -----------------------------------------------------------------

    @FXML
    private void onApprove() {
        if (reviewing == null) {
            showError("Select an exam from the list first.");
            return;
        }
        setButtonsDisabled(true);
        showMessage("Approving " + reviewing.getExamId() + "...");
        send(RequestType.EXAM_APPROVE,
             new ExamDecision(reviewing.getExamId(), reviewing.getVersion(), null), REQ_DECIDE);
    }

    @FXML
    private void onReject() {
        if (reviewing == null) {
            showError("Select an exam from the list first.");
            return;
        }
        String reason = reasonArea.getText();
        if (reason == null || reason.trim().isEmpty()) {
            // Checked here as a courtesy; the server checks it again, and that is
            // the check that counts.
            showError("Type a reason before rejecting. The teacher receives it and "
                    + "needs to know what to change.");
            reasonArea.requestFocus();
            return;
        }
        setButtonsDisabled(true);
        showMessage("Rejecting " + reviewing.getExamId() + "...");
        send(RequestType.EXAM_REJECT,
             new ExamDecision(reviewing.getExamId(), reviewing.getVersion(), reason.trim()),
             REQ_DECIDE);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml", true);
    }

    // -----------------------------------------------------------------
    //  Replies
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }
        if (!response.isOk()) {
            setButtonsDisabled(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_PENDING -> {
                List<Exam> pending = (List<Exam>) response.getPayload();
                pendingList.setItems(FXCollections.observableArrayList(pending));
                pendingCountLabel.setText(pending.size() + " waiting");
                if (pending.isEmpty()) {
                    showNothingSelected();
                }
            }
            case REQ_GET -> showExam((Exam) response.getPayload());
            case REQ_DECIDE -> {
                setButtonsDisabled(false);
                showSuccess(response.getMessage());
                reasonArea.clear();
                showNothingSelected();
                loadPending();
            }
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    private void showNothingSelected() {
        reviewing = null;
        examTitleLabel.setText("No exam selected");
        examMetaLabel.setText("Choose one from the list to read it.");
        instructionsLabel.setText("—");
        teacherNotesLabel.setText("—");
        questionBox.getChildren().clear();
        setButtonsDisabled(true);
    }

    private void showExam(Exam exam) {
        reviewing = exam;

        examTitleLabel.setText("Exam " + exam.getExamId() + "   ·   version " + exam.getVersion());
        examMetaLabel.setText(exam.getCourseName() + "   ·   written by " + exam.getAuthorName()
                + "   ·   " + exam.getQuestionCount() + " questions   ·   "
                + exam.getDurationMinutes() + " minutes   ·   "
                + exam.getTotalPoints() + " points");

        instructionsLabel.setText(blankToDash(exam.getInstructionsForStudents()));
        teacherNotesLabel.setText(blankToDash(exam.getNotesForTeacher()));

        questionBox.getChildren().clear();
        int number = 1;
        for (ExamQuestion eq : exam.getQuestions()) {
            questionBox.getChildren().add(buildQuestionBlock(number++, eq));
        }
        setButtonsDisabled(false);
    }

    /**
     * One question laid out for reading.
     *
     * <p>The correct answer is marked. She is checking the paper is fit to sit,
     * which is impossible without seeing which answer is meant to be right.</p>
     */
    private VBox buildQuestionBlock(int number, ExamQuestion eq) {
        Question q = eq.getQuestion();

        Label heading = new Label(number + ".   " + eq.getPoints() + " points"
                + (q == null ? "" : "   ·   " + q.getTopic()
                                  + "   ·   " + q.getDifficulty().getDisplayName()));
        heading.getStyleClass().add("caption");

        Label text = new Label(q == null ? eq.getQuestionId() : q.getText());
        text.setWrapText(true);
        text.getStyleClass().add("h3");

        VBox block = new VBox(3, heading, text);
        block.getStyleClass().add("diff-same");

        if (q != null) {
            for (var answer : q.getAnswers()) {
                Label option = new Label("   " + answer.getAnswerNo() + ".  " + answer.getText()
                        + (answer.isCorrect() ? "     ✓ correct" : ""));
                option.setWrapText(true);
                if (answer.isCorrect()) {
                    option.getStyleClass().add("status-success");
                }
                block.getChildren().add(option);
            }
        }
        return block;
    }

    private void setButtonsDisabled(boolean disabled) {
        approveButton.setDisable(disabled);
        rejectButton.setDisable(disabled);
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}
