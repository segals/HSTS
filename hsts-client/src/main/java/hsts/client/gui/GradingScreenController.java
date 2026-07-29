package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Question;
import hsts.common.entity.QuestionFeedback;
import hsts.common.entity.StudentExam;
import hsts.common.protocol.CommentRequest;
import hsts.common.protocol.GradeChange;
import hsts.common.protocol.MarkedExam;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * SUC-9 / מתווה scenario 8: the teacher marking and publishing.
 *
 * <p>The mark is worked out automatically. She reads the paper, may change the
 * mark with a compulsory reason, may leave comments, and approves - and only then
 * does the student see anything.</p>
 */
public class GradingScreenController extends GUIScreen {

    private static final String REQ_SITTINGS = "g.sittings";
    private static final String REQ_LIST     = "g.list";
    private static final String REQ_GET      = "g.get";
    private static final String REQ_CHANGE   = "g.change";
    private static final String REQ_COMMENT  = "g.comment";
    private static final String REQ_QCOMMENT = "g.qcomment";
    private static final String REQ_APPROVE  = "g.approve";
    private static final String REQ_ALL      = "g.all";
    private static final String REQ_FACTOR   = "g.factor";
    private static final String REQ_STATS    = "g.stats";

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<ExamExecution> sittingList;
    @FXML private ListView<Grade> studentList;
    @FXML private Label  statsLabel;
    @FXML private Button approveAllButton;
    @FXML private Spinner<Integer> factorSpinner;
    @FXML private Button factorButton;

    @FXML private Label    paperTitleLabel;
    @FXML private Label    markLabel;
    @FXML private Label    paperMetaLabel;
    @FXML private VBox     questionBox;
    @FXML private Spinner<Integer> markSpinner;
    @FXML private TextField reasonField;
    @FXML private Button   changeButton;
    @FXML private TextArea generalCommentArea;
    @FXML private Button   commentButton;
    @FXML private Button   approveButton;
    @FXML private Label    statusLabel;

    private ExamExecution sitting;
    private MarkedExam openPaper;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Marks are worked out automatically. Nothing reaches a student "
                            + "until you approve it.");

        markSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 0));
        factorSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(-100, 100, 5));

        // The same exam may be handed out many times - a different class, a re-sit -
        // and each hand-out is its own sitting with its own code and its own
        // students. So several rows sharing one exam number is normal, and the code
        // is what tells them apart. It leads.
        useWrappingCells(sittingList, x ->
                "Code " + x.getExecutionCode() + "  ·  exam " + x.getExamId()
              + "\n" + x.getCourseName()
              + "\n" + describeSitting(x));

        useWrappingCells(studentList, g ->
                g.getStudentName()
              + "\n" + (g.isMarked()
                    ? g.getFinalGrade() + (g.wasChangedByHand()
                          ? "  (system said " + g.getAutoGrade() + ")" : "")
                      + "  ·  " + (g.isApproved() ? "approved" : "not approved yet")
                    : "still sitting  ·  nothing to mark yet"));

        sittingList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, execution) -> chooseSitting(execution));
        studentList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, grade) -> {
                    if (grade == null) {
                        return;
                    }
                    if (!grade.isMarked()) {
                        // Acceptance test 3.11. Checked here so she gets an answer
                        // without a round trip; the server refuses it too, and that
                        // is the check that counts.
                        showNoPaper();
                        showMessage(grade.getStudentName() + " is still sitting this exam. "
                                  + "It can be marked once she has handed in.");
                        return;
                    }
                    send(RequestType.GRADING_GET, grade.getSubmissionId(), REQ_GET);
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNoPaper();
        send(RequestType.GRADING_SITTINGS, null, REQ_SITTINGS);
    }

    // -----------------------------------------------------------------
    //  Actions
    // -----------------------------------------------------------------

    @FXML
    private void onChangeMark() {
        if (openPaper == null) {
            showError("Open a paper first.");
            return;
        }
        String reason = reasonField.getText();
        if (reason == null || reason.trim().isEmpty()) {
            // Checked here for speed; the server refuses it too, and that is the
            // check that counts (requirement 52).
            showError("Changing a mark by hand needs a reason. It is kept with the mark.");
            reasonField.requestFocus();
            return;
        }
        send(RequestType.GRADING_CHANGE, GradeChange.forOne(
                openPaper.getAttempt().getSubmissionId(),
                markSpinner.getValue(), reason.trim()), REQ_CHANGE);
    }

    @FXML
    private void onSaveComment() {
        if (openPaper == null) {
            showError("Open a paper first.");
            return;
        }
        send(RequestType.GRADING_GENERAL_COMMENT, CommentRequest.general(
                openPaper.getAttempt().getSubmissionId(),
                generalCommentArea.getText()), REQ_COMMENT);
    }

    @FXML
    private void onApprove() {
        if (openPaper == null) {
            showError("Open a paper first.");
            return;
        }
        approveButton.setDisable(true);
        send(RequestType.GRADING_APPROVE, openPaper.getAttempt().getSubmissionId(), REQ_APPROVE);
    }

    @FXML
    private void onApproveAll() {
        if (sitting == null) {
            showError("Choose a sitting first.");
            return;
        }
        send(RequestType.GRADING_APPROVE_ALL, sitting.getExecutionId(), REQ_ALL);
    }

    @FXML
    private void onFactor() {
        if (sitting == null) {
            showError("Choose a sitting first.");
            return;
        }
        send(RequestType.GRADING_FACTOR,
             GradeChange.factor(sitting.getExecutionId(), factorSpinner.getValue()), REQ_FACTOR);
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
            approveButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_SITTINGS -> {
                List<ExamExecution> sittings = (List<ExamExecution>) response.getPayload();
                sittingList.setItems(FXCollections.observableArrayList(sittings));
                if (sittings.isEmpty()) {
                    showMessage(response.getMessage());
                }
            }
            case REQ_LIST -> {
                List<Grade> grades = (List<Grade>) response.getPayload();
                int keep = (openPaper == null) ? -1 : openPaper.getAttempt().getSubmissionId();
                studentList.setItems(FXCollections.observableArrayList(grades));
                for (Grade g : grades) {
                    if (g.getSubmissionId() == keep) {
                        studentList.getSelectionModel().select(g);
                        break;
                    }
                }
                if (openPaper == null) {
                    // Says how many are ready and how many are still sitting, so an
                    // empty-looking list is never a mystery.
                    showMessage(response.getMessage());
                }
            }
            case REQ_GET -> showPaper((MarkedExam) response.getPayload());
            case REQ_CHANGE, REQ_COMMENT, REQ_QCOMMENT -> {
                showSuccess(response.getMessage());
                refreshCurrent();
            }
            case REQ_APPROVE -> {
                approveButton.setDisable(false);
                showSuccess(response.getMessage());
                refreshCurrent();
            }
            case REQ_ALL, REQ_FACTOR -> {
                showSuccess(response.getMessage());
                refreshCurrent();
            }
            case REQ_STATS -> showStatistics((ExamStatistics) response.getPayload());
            default -> { }
        }
    }

    private void refreshCurrent() {
        if (sitting != null) {
            send(RequestType.GRADING_LIST, sitting.getExecutionId(), REQ_LIST);
            send(RequestType.GRADING_STATISTICS, sitting.getExecutionId(), REQ_STATS);
        }
        if (openPaper != null) {
            send(RequestType.GRADING_GET, openPaper.getAttempt().getSubmissionId(), REQ_GET);
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    /**
     * "3 sat it · 2 handed in · 1 still sitting".
     *
     * <p>One number was not enough. It counted everybody who started, while the
     * student list showed only papers handed in, so the two disagreed and there was
     * nothing on screen to explain the gap.</p>
     */
    private static String describeSitting(ExamExecution x) {
        int started = x.getNumStarted();
        int sitting = x.getNumUnfinished();
        int handedIn = started - sitting;
        if (started == 0) {
            return "nobody has started it";
        }
        return started + " sat it  ·  " + handedIn + " handed in"
             + (sitting > 0 ? "  ·  " + sitting + " still sitting" : "");
    }

    private void chooseSitting(ExamExecution execution) {
        sitting = execution;
        showNoPaper();
        studentList.setItems(FXCollections.observableArrayList());
        statsLabel.setText("");
        if (execution == null) {
            return;
        }
        send(RequestType.GRADING_LIST, execution.getExecutionId(), REQ_LIST);
        send(RequestType.GRADING_STATISTICS, execution.getExecutionId(), REQ_STATS);
    }

    /** Requirement 54, and requirement 55 - this stays on the teacher's screen. */
    private void showStatistics(ExamStatistics stats) {
        if (stats == null || stats.getGradeCount() == 0) {
            statsLabel.setText("no approved marks yet");
            return;
        }
        statsLabel.setText(String.format("avg %.1f  ·  median %.1f  ·  %d approved",
                stats.getAverage(), stats.getMedian(), stats.getGradeCount()));
    }

    private void showNoPaper() {
        openPaper = null;
        paperTitleLabel.setText("No paper open");
        paperMetaLabel.setText("Pick a sitting, then a student.");
        markLabel.setText("");
        questionBox.getChildren().clear();
        generalCommentArea.clear();
        reasonField.clear();
    }

    private void showPaper(MarkedExam marked) {
        openPaper = marked;
        StudentExam attempt = marked.getAttempt();
        Grade grade = marked.getGrade();

        paperTitleLabel.setText(attempt.getStudentName());
        markLabel.setText(String.valueOf(grade.getFinalGrade()));
        paperMetaLabel.setText("Exam " + attempt.getExamId()
                + "   ·   " + attempt.getStatus().getDisplayName()
                + (attempt.getActualDuration() == null ? ""
                   : "   ·   took " + attempt.getActualDuration() + " min")
                + "   ·   system marked it " + grade.getAutoGrade()
                + (grade.isApproved() ? "   ·   APPROVED" : "   ·   not approved yet")
                + (grade.wasChangedByHand() && grade.getManualChangeExplanation() != null
                   ? "\nChanged by hand: " + grade.getManualChangeExplanation() : ""));

        markSpinner.getValueFactory().setValue(grade.getFinalGrade());
        generalCommentArea.setText(grade.getTeacherGeneralComment() == null
                ? "" : grade.getTeacherGeneralComment());
        reasonField.clear();

        questionBox.getChildren().clear();
        int number = 1;
        for (ExamQuestion eq : attempt.getQuestions()) {
            questionBox.getChildren().add(buildQuestion(number++, eq, attempt, grade));
        }
        clearMessage();
    }

    /** One question, with her answer marked and a box for a comment on it. */
    private VBox buildQuestion(int number, ExamQuestion eq, StudentExam attempt, Grade grade) {
        Question q = eq.getQuestion();
        Integer chosen = attempt.answerFor(eq.getQuestionId());
        QuestionFeedback feedback = grade.feedbackFor(eq.getQuestionId());
        boolean wrong = feedback != null && feedback.isWrong();

        // Acceptance test 3.9: wrong answers stand out, so she can find them fast.
        Label heading = new Label(number + ".      " + eq.getPoints()
                + (eq.getPoints() == 1 ? " point" : " points")
                + "      " + (wrong ? "✗  wrong" : "✓  correct"));
        heading.setWrapText(true);
        heading.getStyleClass().add(wrong ? "status-error" : "status-success");

        Label text = new Label(q == null ? eq.getQuestionId() : q.getText());
        text.setWrapText(true);
        text.getStyleClass().add("h3");

        VBox block = new VBox(6, heading, text);
        block.getStyleClass().add("card");

        if (q != null) {
            for (Answer option : q.getAnswers()) {
                boolean isHers = chosen != null && chosen == option.getAnswerNo();
                String note = "";
                if (isHers && option.isCorrect()) {
                    note = "     ← her answer, correct";
                } else if (isHers) {
                    note = "     ← her answer";
                } else if (option.isCorrect()) {
                    note = "     ← correct";
                }
                Label line = new Label("   " + option.getAnswerNo() + ".  "
                                     + option.getText() + note);
                line.setWrapText(true);
                if (option.isCorrect()) {
                    line.getStyleClass().add("status-success");
                } else if (isHers) {
                    line.getStyleClass().add("status-error");
                }
                block.getChildren().add(line);
            }
            if (chosen == null) {
                Label blank = new Label("   She left this blank.");
                blank.setWrapText(true);
                blank.getStyleClass().add("caption");
                block.getChildren().add(blank);
            }
        }

        TextField comment = new TextField(
                feedback != null && feedback.getComment() != null ? feedback.getComment() : "");
        comment.setPromptText("A comment for her on this question (optional)");
        HBox.setHgrow(comment, Priority.ALWAYS);
        comment.setMaxWidth(Double.MAX_VALUE);

        Button save = new Button("Save");
        save.setOnAction(e -> send(RequestType.GRADING_QUESTION_COMMENT,
                new CommentRequest(attempt.getSubmissionId(), eq.getQuestionId(),
                        eq.getQuestionVersion(), comment.getText()), REQ_QCOMMENT));

        block.getChildren().add(new HBox(8, comment, save));
        return block;
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
