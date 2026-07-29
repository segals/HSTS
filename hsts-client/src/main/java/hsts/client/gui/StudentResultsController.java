package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.Grade;
import hsts.common.entity.Question;
import hsts.common.entity.QuestionFeedback;
import hsts.common.entity.StudentExam;
import hsts.common.protocol.MarkedExam;
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
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * SUC-10 / מתווה scenario 9: the student's own results.
 *
 * <p>She sees her mark, her paper with the wrong questions marked and the right
 * answer shown, and any comments her teacher left. Nothing about the class - no
 * average, no median, no deciles (requirement 55), and the server would refuse to
 * send them to her even if this screen asked.</p>
 */
public class StudentResultsController extends GUIScreen {

    private static final String REQ_LIST = "sr.list";
    private static final String REQ_ONE  = "sr.one";

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<Grade> resultList;

    @FXML private Label examTitleLabel;
    @FXML private Label gradeLabel;
    @FXML private Label examMetaLabel;
    @FXML private VBox  generalCommentBox;
    @FXML private Label generalCommentLabel;
    @FXML private VBox  questionBox;
    @FXML private Label statusLabel;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Your own results. This list updates by itself when a mark is approved.");

        useWrappingCells(resultList, g ->
                "Exam " + g.getExamId() + "  ·  " + g.getCourseName()
              + "\n" + (g.isApproved()
                    ? "Mark: " + g.getFinalGrade()
                    : "Waiting for your teacher to approve it"));

        resultList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, grade) -> {
                    if (grade != null) {
                        open(grade);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNothing();
        send(RequestType.RESULTS_MINE, null, REQ_LIST);
    }

    /** A mark was approved or changed while she was looking. */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() == PushType.GRADE_APPROVED) {
            showSuccess(event.getMessage());
            send(RequestType.RESULTS_MINE, null, REQ_LIST);
        } else {
            super.onPush(event);
        }
    }

    private void open(Grade grade) {
        if (!grade.isApproved()) {
            showNothing();
            examTitleLabel.setText("Exam " + grade.getExamId());
            examMetaLabel.setText(grade.getCourseName());
            showMessage("Your teacher has not approved this exam yet. "
                      + "The mark will appear here once she has.");
            return;
        }
        send(RequestType.RESULTS_MARKED_EXAM, grade.getSubmissionId(), REQ_ONE);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml", true);
    }

    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }
        if (!response.isOk()) {
            showError(response.getMessage());
            return;
        }
        switch (id) {
            case REQ_LIST -> {
                List<Grade> results = (List<Grade>) response.getPayload();
                resultList.setItems(FXCollections.observableArrayList(results));
                if (results.isEmpty()) {
                    showMessage(response.getMessage());
                }
            }
            case REQ_ONE -> showMarkedExam((MarkedExam) response.getPayload());
            default -> { }
        }
    }

    private void showNothing() {
        examTitleLabel.setText("No exam chosen");
        examMetaLabel.setText("Pick one on the left.");
        gradeLabel.setText("");
        generalCommentBox.setVisible(false);
        generalCommentBox.setManaged(false);
        questionBox.getChildren().clear();
    }

    private void showMarkedExam(MarkedExam marked) {
        StudentExam attempt = marked.getAttempt();
        Grade grade = marked.getGrade();

        examTitleLabel.setText("Exam " + attempt.getExamId());
        gradeLabel.setText(String.valueOf(grade.getFinalGrade()));
        gradeLabel.setStyle("-fx-font-size: 30px; -fx-font-weight: 600;"
                + (grade.getFinalGrade() >= 55 ? " -fx-text-fill: #0f7a58;"
                                               : " -fx-text-fill: #c02626;"));

        // Acceptance test 4.11: she is shown how long she actually took.
        examMetaLabel.setText(attempt.getCourseName()
                + (attempt.getActualDuration() == null ? ""
                   : "   ·   you took " + attempt.getActualDuration() + " minutes")
                + "   ·   " + attempt.getStatus().getDisplayName()
                + (grade.getFactor() != 0
                   ? "   ·   includes a factor of " + (grade.getFactor() > 0 ? "+" : "")
                     + grade.getFactor() : ""));

        boolean hasGeneral = grade.getTeacherGeneralComment() != null
                          && !grade.getTeacherGeneralComment().isBlank();
        generalCommentBox.setVisible(hasGeneral);
        generalCommentBox.setManaged(hasGeneral);
        if (hasGeneral) {
            generalCommentLabel.setText(grade.getTeacherGeneralComment());
        }

        questionBox.getChildren().clear();
        int number = 1;
        for (ExamQuestion eq : attempt.getQuestions()) {
            questionBox.getChildren().add(buildMarkedQuestion(number++, eq, attempt, grade));
        }
        clearMessage();
    }

    /**
     * One question as she sees it afterwards.
     *
     * <p>Acceptance test 4.3: the answer she chose is marked, and the correct one
     * is shown in a different colour. Acceptance test 4.4: the teacher's comment
     * appears beside the question it belongs to.</p>
     */
    private VBox buildMarkedQuestion(int number, ExamQuestion eq,
                                     StudentExam attempt, Grade grade) {
        Question q = eq.getQuestion();
        Integer chosen = attempt.answerFor(eq.getQuestionId());
        QuestionFeedback feedback = grade.feedbackFor(eq.getQuestionId());
        boolean wrong = feedback != null && feedback.isWrong();

        Label heading = new Label(number + ".      " + eq.getPoints()
                + (eq.getPoints() == 1 ? " point" : " points")
                + "      " + (wrong ? "✗  not correct" : "✓  correct"));
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
                String mark = "";
                if (isHers && option.isCorrect()) {
                    mark = "     ← your answer, correct";
                } else if (isHers) {
                    mark = "     ← your answer";
                } else if (option.isCorrect()) {
                    mark = "     ← the correct answer";
                }

                Label line = new Label("   " + option.getAnswerNo() + ".  "
                                     + option.getText() + mark);
                line.setWrapText(true);
                if (option.isCorrect()) {
                    line.getStyleClass().add("status-success");
                } else if (isHers) {
                    line.getStyleClass().add("status-error");
                }
                block.getChildren().add(line);
            }

            if (chosen == null) {
                Label blank = new Label("   You did not answer this question.");
                blank.setWrapText(true);
                blank.getStyleClass().add("caption");
                block.getChildren().add(blank);
            }
        }

        if (feedback != null && feedback.hasComment()) {
            Label comment = new Label("Your teacher wrote:  " + feedback.getComment());
            comment.setWrapText(true);
            comment.getStyleClass().add("diff-changed");
            comment.setMaxWidth(Double.MAX_VALUE);
            block.getChildren().add(comment);
        }
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
