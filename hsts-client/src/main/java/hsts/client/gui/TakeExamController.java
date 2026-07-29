package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.Question;
import hsts.common.entity.StudentExam;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.AnswerChoice;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.StartExamRequest;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.fxml.FXML;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/**
 * SUC-7 / מתווה scenario 6: the student's exam screen.
 *
 * <p>Three steps in one window - the code, her identity, then the paper.</p>
 *
 * <p><b>The countdown is not calculated here.</b> The server sends the seconds
 * remaining once a second and this class only displays them. That is what
 * acceptance test 2.11 asks for, and it means a client that freezes, or one whose
 * clock is wrong, cannot change how long anybody gets.</p>
 */
public class TakeExamController extends GUIScreen {

    private static final String REQ_CODE   = "t.code";
    private static final String REQ_START  = "t.start";
    private static final String REQ_ANSWER = "t.answer";
    private static final String REQ_SUBMIT = "t.submit";

    @FXML private Label     titleLabel;
    @FXML private Label     subtitleLabel;
    @FXML private VBox      timerBox;
    @FXML private Label     timerLabel;
    @FXML private Button    backButton;

    @FXML private VBox      codePane;
    @FXML private TextField codeField;
    @FXML private Button    codeButton;

    @FXML private VBox      idPane;
    @FXML private Label     idExplainLabel;
    @FXML private TextField idField;
    @FXML private Button    startButton;
    @FXML private Button    backToCodeButton;

    @FXML private VBox      examPane;
    @FXML private Label     instructionsLabel;
    @FXML private VBox      questionBox;
    @FXML private Button    submitButton;
    @FXML private Label     progressLabel;

    @FXML private VBox      donePane;
    @FXML private Label     doneTitleLabel;
    @FXML private Label     doneDetailLabel;

    @FXML private Region    filler;
    @FXML private Label     statusLabel;

    /** The sitting whose code was accepted. */
    private ExamExecution execution;

    /** The paper she is sitting, once started. */
    private StudentExam attempt;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Your teacher will read out the code.");

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(reason ->
                showError(reason + "  Your answers were saved on the server as you chose them."));

        showOnly(codePane);
        codeField.requestFocus();
    }

    /**
     * The server's clock, and the automatic close.
     *
     * <p>These arrive once a second while she is sitting the exam.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        switch (event.getType()) {
            case EXAM_TIME_TICK -> {
                if (event.getPayload() instanceof Long seconds) {
                    showRemaining(seconds);
                }
            }
            case EXAM_TIME_CHANGED -> {
                // Acceptance test 2.7: the countdown moves by itself, with nobody
                // pressing anything on this machine.
                if (event.getPayload() instanceof Long seconds) {
                    showRemaining(seconds);
                }
                showSuccess(event.getMessage());
            }
            case EXAM_AUTO_SUBMITTED -> {
                // Requirement 45 / acceptance test 2.6.
                lockPaper();
                showOnly(donePane);
                doneTitleLabel.setText("Time up");
                doneDetailLabel.setText(event.getMessage());
                showError(event.getMessage());
                timerLabel.setText("00:00");
            }
            default -> super.onPush(event);
        }
    }

    // -----------------------------------------------------------------
    //  Step 1 - the code
    // -----------------------------------------------------------------

    @FXML
    private void onCheckCode() {
        String code = codeField.getText();
        if (code == null || code.trim().isEmpty()) {
            showError("Enter the code your teacher read out.");
            return;
        }
        codeButton.setDisable(true);
        showMessage("Checking...");
        send(RequestType.TAKE_VALIDATE_CODE, code.trim(), REQ_CODE);
    }

    @FXML
    private void onBackToCode() {
        execution = null;
        showOnly(codePane);
        clearMessage();
        codeField.selectAll();
        codeField.requestFocus();
    }

    // -----------------------------------------------------------------
    //  Step 2 - identify
    // -----------------------------------------------------------------

    @FXML
    private void onStart() {
        if (execution == null) {
            showError("Enter the exam code first.");
            return;
        }
        String id = idField.getText();
        if (id == null || id.trim().isEmpty()) {
            showError("Enter your ID number.");
            return;
        }
        startButton.setDisable(true);
        showMessage("Starting...");
        send(RequestType.TAKE_START,
             new StartExamRequest(execution.getExecutionId(), id.trim()), REQ_START);
    }

    // -----------------------------------------------------------------
    //  Step 3 - answering and handing in
    // -----------------------------------------------------------------

    @FXML
    private void onSubmit() {
        if (attempt == null) {
            return;
        }
        int answered = countAnswered();
        int total = attempt.getQuestions().size();

        String question = (answered < total)
                // Acceptance test 2.12: warn, but let her decide.
                ? "You have answered " + answered + " of " + total + " questions.\n\n"
                  + "Unanswered questions are marked wrong. Hand in anyway?"
                : "Hand in the exam? You cannot change your answers afterwards.";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, question,
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Hand in");
        confirm.getDialogPane().setPrefWidth(420);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.YES) {
            return;
        }

        submitButton.setDisable(true);
        showMessage("Handing in...");
        send(RequestType.TAKE_SUBMIT, attempt.getSubmissionId(), REQ_SUBMIT);
    }

    @FXML
    private void onBack() {
        if (attempt != null && attempt.isInProgress()) {
            Alert confirm = new Alert(Alert.AlertType.WARNING,
                    "Your exam is still running and your time keeps counting down.\n\n"
                  + "Your answers are already saved, and you can come back with the same "
                  + "code. Leave this screen?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText("The exam is still running");
            confirm.getDialogPane().setPrefWidth(440);
            Optional<ButtonType> answer = confirm.showAndWait();
            if (answer.isEmpty() || answer.get() != ButtonType.YES) {
                return;
            }
        }
        switchTo("/fxml/MainMenu.fxml", true);
    }

    // -----------------------------------------------------------------
    //  Replies
    // -----------------------------------------------------------------

    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }

        if (!response.isOk()) {
            codeButton.setDisable(false);
            startButton.setDisable(false);
            submitButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_CODE -> {
                codeButton.setDisable(false);
                execution = (ExamExecution) response.getPayload();
                showOnly(idPane);
                idExplainLabel.setText("You have " + execution.getAllocatedDuration()
                        + " minutes once you start. Your time keeps running until it is used "
                        + "up, even if the exam window closes before then.");
                showMessage(response.getMessage());
                idField.requestFocus();
            }
            case REQ_START -> {
                startButton.setDisable(false);
                showPaper((StudentExam) response.getPayload());
                showSuccess(response.getMessage());
            }
            case REQ_ANSWER -> {
                // The reply carries the seconds left, which keeps the countdown
                // honest even if a tick was missed.
                if (response.getPayload() instanceof Long seconds) {
                    showRemaining(seconds);
                }
                updateProgress();
            }
            case REQ_SUBMIT -> {
                StudentExam finished = (StudentExam) response.getPayload();
                attempt = finished;
                lockPaper();
                showOnly(donePane);
                boolean timedOut = finished.getStatus() == SubmissionStatus.TIMED_OUT;
                doneTitleLabel.setText(timedOut ? "Time up" : "Handed in");
                doneDetailLabel.setText(response.getMessage()
                        + (finished.getActualDuration() == null ? ""
                           : "\n\nTime taken: " + finished.getActualDuration() + " minutes."));
                showSuccess(response.getMessage());
            }
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  The paper
    // -----------------------------------------------------------------

    private void showPaper(StudentExam paper) {
        this.attempt = paper;

        titleLabel.setText("Exam " + paper.getExamId());
        subtitleLabel.setText(paper.getCourseName() + "   ·   attempt " + paper.getAttemptNo());
        instructionsLabel.setText(paper.getInstructionsForStudents() == null
                || paper.getInstructionsForStudents().isBlank()
                ? "No special instructions." : paper.getInstructionsForStudents());

        timerBox.setVisible(true);
        timerBox.setManaged(true);

        questionBox.getChildren().clear();
        int number = 1;
        for (ExamQuestion eq : paper.getQuestions()) {
            questionBox.getChildren().add(buildQuestion(number++, eq));
        }

        showOnly(examPane);
        updateProgress();
    }

    /** One question with its four options. */
    private VBox buildQuestion(int number, ExamQuestion eq) {
        Question q = eq.getQuestion();

        Label heading = new Label(number + ".      " + eq.getPoints()
                + (eq.getPoints() == 1 ? " point" : " points"));
        heading.getStyleClass().add("section-title");
        heading.setWrapText(true);

        Label text = new Label(q == null ? eq.getQuestionId() : q.getText());
        text.setWrapText(true);
        text.getStyleClass().add("h3");

        VBox block = new VBox(6, heading, text);
        block.getStyleClass().add("card");

        if (q != null) {
            if (q.getInstructions() != null && !q.getInstructions().isBlank()) {
                Label hint = new Label(q.getInstructions());
                hint.setWrapText(true);
                hint.getStyleClass().add("caption");
                block.getChildren().add(hint);
            }
            if (q.hasImage()) {
                ImageView picture = new ImageView(new Image(new ByteArrayInputStream(q.getImage())));
                picture.setPreserveRatio(true);
                picture.setFitHeight(180);
                block.getChildren().add(picture);
            }

            ToggleGroup group = new ToggleGroup();
            Integer already = attempt.answerFor(eq.getQuestionId());

            for (Answer option : q.getAnswers()) {
                RadioButton button = new RadioButton(option.getAnswerNo() + ".   " + option.getText());
                button.setToggleGroup(group);
                button.setWrapText(true);
                button.setMaxWidth(Double.MAX_VALUE);
                button.setUserData(option.getAnswerNo());
                if (already != null && already == option.getAnswerNo()) {
                    button.setSelected(true);
                }
                block.getChildren().add(button);
            }

            // Saved the moment she chooses, not on submit. Requirement 45 keeps
            // whatever she had entered when the time runs out.
            group.selectedToggleProperty().addListener((obs, old, chosen) -> {
                if (chosen == null || attempt == null) {
                    return;
                }
                int chosenNo = (Integer) chosen.getUserData();
                rememberLocally(eq.getQuestionId(), chosenNo);
                send(RequestType.TAKE_SAVE_ANSWER,
                     new AnswerChoice(attempt.getSubmissionId(), eq.getQuestionId(),
                                      eq.getQuestionVersion(), chosenNo),
                     REQ_ANSWER);
            });
        }
        return block;
    }

    /** Keeps the local copy in step, so the progress count is right immediately. */
    private void rememberLocally(String questionId, int chosenNo) {
        for (var answer : attempt.getAnswers()) {
            if (answer.getQuestionId().equals(questionId)) {
                answer.setSelectedAnswerNo(chosenNo);
                return;
            }
        }
        attempt.getAnswers().add(
                new hsts.common.entity.StudentAnswer(questionId, 0, chosenNo));
    }

    private int countAnswered() {
        return attempt == null ? 0 : attempt.getAnsweredCount();
    }

    private void updateProgress() {
        if (attempt == null) {
            return;
        }
        progressLabel.setText(countAnswered() + " of " + attempt.getQuestions().size()
                            + " answered");
    }

    /** mm:ss, and red for the last minute. */
    private void showRemaining(long seconds) {
        long minutes = seconds / 60;
        long rest = seconds % 60;
        timerLabel.setText(String.format("%02d:%02d", minutes, rest));
        timerLabel.setStyle("-fx-font-size: 26px; -fx-font-weight: 600;"
                + (seconds <= 60 ? " -fx-text-fill: #c02626;" : ""));
    }

    /** Stops any further answering once the paper is closed. */
    private void lockPaper() {
        questionBox.setDisable(true);
        submitButton.setDisable(true);
    }

    private void showOnly(VBox pane) {
        for (VBox candidate : new VBox[]{codePane, idPane, examPane, donePane}) {
            boolean show = candidate == pane;
            candidate.setVisible(show);
            candidate.setManaged(show);
        }
        // The filler pushes short panes to the top; the paper needs the room itself.
        boolean paperShowing = pane == examPane;
        filler.setVisible(!paperShowing);
        filler.setManaged(!paperShowing);
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
