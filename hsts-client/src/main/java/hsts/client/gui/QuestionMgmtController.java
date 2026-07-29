package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.Course;
import hsts.common.entity.Question;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.client.HSTSApp;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SUC-2 / מתווה scenario 2: the question bank screen.
 *
 * <p>Add a question, edit one so the previous version stays, browse the bank,
 * and delete. All four items of that scenario are on this one screen.</p>
 *
 * <p>The screen validates as a courtesy - it is quicker to be told here than
 * after a round trip - but every rule is enforced again in
 * {@code QuestionController} on the server, which is the copy that counts.</p>
 */
public class QuestionMgmtController extends GUIScreen {

    // Correlation ids, so each reply is routed to the code that asked for it.
    private static final String REQ_COURSES  = "q.courses";
    private static final String REQ_LIST     = "q.list";
    private static final String REQ_TOPICS   = "q.topics";
    private static final String REQ_GET      = "q.get";
    private static final String REQ_ADD      = "q.add";
    private static final String REQ_EDIT     = "q.edit";
    private static final String REQ_DELETE   = "q.delete";
    private static final String REQ_VERSIONS = "q.versions";

    @FXML private Label            subtitleLabel;
    @FXML private ComboBox<Course> courseCombo;
    @FXML private Button           backButton;
    @FXML private Label            bankCountLabel;
    @FXML private ListView<Question> questionList;
    @FXML private Button           newButton;
    @FXML private Button           deleteButton;
    @FXML private Button           versionsButton;

    @FXML private Label     editorTitleLabel;
    @FXML private Label     versionLabel;
    @FXML private TextArea  textArea;
    @FXML private TextArea  instructionsArea;
    @FXML private ComboBox<String> topicCombo;
    @FXML private ComboBox<DifficultyLevel> difficultyCombo;

    @FXML private ToggleGroup correctGroup;
    @FXML private RadioButton correct1, correct2, correct3, correct4;
    @FXML private TextField   answer1, answer2, answer3, answer4;

    @FXML private Button    chooseImageButton;
    @FXML private Button    clearImageButton;
    @FXML private Label     imageLabel;
    @FXML private HBox      imageFrame;
    @FXML private ImageView imagePreview;

    @FXML private Button saveButton;
    @FXML private Button cancelButton;
    @FXML private Label  statusLabel;

    /** The question being edited, or null when composing a new one. */
    private Question editing;

    /** The picture currently attached, as raw bytes. Null when there is none. */
    private byte[] imageBytes;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);

        subtitleLabel.setText(
                "Add, edit and remove questions.  Editing keeps the previous version in the bank.");

        difficultyCombo.setItems(FXCollections.observableArrayList(DifficultyLevel.values()));

        courseCombo.valueProperty().addListener((obs, old, course) -> {
            if (course != null) {
                loadCourse(course.getCourseCode());
            }
        });

        questionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, question) -> {
                    if (question != null) {
                        requestFullQuestion(question);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        startNewQuestion();
        send(RequestType.COURSE_LIST_MINE, null, REQ_COURSES);
        showMessage("Loading your courses...");
    }

    // -----------------------------------------------------------------
    //  Sending
    // -----------------------------------------------------------------

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }

    private void loadCourse(String courseCode) {
        send(RequestType.QUESTION_LIST_BY_COURSE, courseCode, REQ_LIST);
        send(RequestType.QUESTION_TOPICS, courseCode, REQ_TOPICS);
    }

    private void requestFullQuestion(Question summary) {
        // The list arrives without pictures, to keep it small. Opening one asks
        // for the whole thing.
        send(RequestType.QUESTION_GET,
             new QuestionRef(summary.getQuestionId(), summary.getVersion()), REQ_GET);
    }

    // -----------------------------------------------------------------
    //  Buttons
    // -----------------------------------------------------------------

    @FXML
    private void onNew() {
        questionList.getSelectionModel().clearSelection();
        startNewQuestion();
        showMessage("Composing a new question.");
    }

    @FXML
    private void onSave() {
        Course course = courseCombo.getValue();
        if (course == null) {
            showError("Choose a course first.");
            return;
        }

        Question question = readForm();
        question.setCourseCode(course.getCourseCode());

        String problem = validateLocally(question);
        if (problem != null) {
            showError(problem);
            return;
        }

        saveButton.setDisable(true);
        if (editing == null) {
            showMessage("Adding the question...");
            send(RequestType.QUESTION_ADD, question, REQ_ADD);
        } else {
            question.setQuestionId(editing.getQuestionId());
            showMessage("Saving as a new version...");
            send(RequestType.QUESTION_EDIT, question, REQ_EDIT);
        }
    }

    @FXML
    private void onDelete() {
        Question selected = questionList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Select a question in the list first.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove question " + selected.getQuestionId() + " from the bank?\n\n"
              + "Exams that already contain it are not affected - they keep showing "
              + "the version they used.",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Delete question");
        Optional<ButtonType> answer = confirm.showAndWait();

        if (answer.isPresent() && answer.get() == ButtonType.YES) {
            send(RequestType.QUESTION_DELETE, selected.getQuestionId(), REQ_DELETE);
        }
    }

    @FXML
    private void onShowVersions() {
        Question selected = questionList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Select a question in the list first.");
            return;
        }
        send(RequestType.QUESTION_VERSIONS, new QuestionRef(selected.getQuestionId()), REQ_VERSIONS);
    }

    @FXML
    private void onChooseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a picture for this question");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif"));

        File file = chooser.showOpenDialog(hsts.client.HSTSApp.getPrimaryStage());
        if (file == null) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > 2 * 1024 * 1024) {
                showError("That picture is " + (bytes.length / 1024)
                        + " KB. The limit is 2048 KB.");
                return;
            }
            imageBytes = bytes;
            showImagePreview();
            showMessage("Picture attached (" + (bytes.length / 1024) + " KB). "
                      + "It is stored in the database, not as a file path.");
        } catch (Exception e) {
            showError("Could not read that file: " + e.getMessage());
        }
    }

    @FXML
    private void onClearImage() {
        imageBytes = null;
        showImagePreview();
        showMessage("Picture removed.");
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
            saveButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_COURSES -> {
                List<Course> courses = (List<Course>) response.getPayload();
                courseCombo.setItems(FXCollections.observableArrayList(courses));
                if (courses.isEmpty()) {
                    showError("You do not teach any courses, so there is no bank to manage.");
                } else {
                    courseCombo.getSelectionModel().selectFirst();
                }
            }
            case REQ_LIST -> {
                List<Question> questions = (List<Question>) response.getPayload();
                questionList.setItems(FXCollections.observableArrayList(questions));
                bankCountLabel.setText(questions.size() + " question(s) in this course");
                showMessage(response.getMessage());
            }
            case REQ_TOPICS -> {
                List<String> topics = (List<String>) response.getPayload();
                topicCombo.setItems(FXCollections.observableArrayList(topics));
            }
            case REQ_GET -> showQuestion((Question) response.getPayload());
            case REQ_ADD, REQ_EDIT -> {
                saveButton.setDisable(false);
                showSuccess(response.getMessage());
                refreshAfterChange();
            }
            case REQ_DELETE -> {
                showSuccess(response.getMessage());
                startNewQuestion();
                refreshAfterChange();
            }
            case REQ_VERSIONS -> showVersionHistory((List<Question>) response.getPayload());
            default -> { }
        }
    }

    private void refreshAfterChange() {
        Course course = courseCombo.getValue();
        if (course != null) {
            loadCourse(course.getCourseCode());
        }
    }

    /**
     * Opens the version-history window.
     *
     * <p>This is the evidence for מתווה scenario 2 item 2 - editing kept the old
     * copy. The window puts the selected older version beside the current one and
     * marks every field that differs.</p>
     *
     * <p>It is a separate window rather than a dialog because a dialog cannot be
     * left open while you look at the question underneath it, and comparing two
     * versions is exactly the moment you want to.</p>
     */
    private void showVersionHistory(List<Question> versions) {
        if (versions == null || versions.isEmpty()) {
            showError("No version history came back from the server.");
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/fxml/VersionHistory.fxml"));
            Scene scene = new Scene(loader.load());
            HSTSApp.applyStylesheet(scene);

            VersionHistoryController window = loader.getController();
            window.setVersions(versions);

            Stage stage = new Stage();
            stage.setTitle("Version history - question " + versions.get(0).getQuestionId());
            stage.setScene(scene);
            stage.initOwner(HSTSApp.getPrimaryStage());
            stage.show();

            showMessage(versions.size() == 1
                    ? "This question has only one version - it has not been edited yet."
                    : versions.size() + " versions stored. The older ones are still in the database.");
        } catch (Exception e) {
            showError("Could not open the version history: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  The form
    // -----------------------------------------------------------------

    private void startNewQuestion() {
        editing = null;
        imageBytes = null;

        editorTitleLabel.setText("New question");
        versionLabel.setText("It will be given the next free 5-digit id: "
                           + "3 digits for the question number, 2 for the course code.");
        textArea.clear();
        instructionsArea.clear();
        topicCombo.setValue(null);
        topicCombo.getEditor().clear();
        difficultyCombo.setValue(DifficultyLevel.MEDIUM);
        answer1.clear(); answer2.clear(); answer3.clear(); answer4.clear();
        correctGroup.selectToggle(null);
        showImagePreview();
    }

    private void showQuestion(Question q) {
        editing = q;
        imageBytes = q.getImage();

        editorTitleLabel.setText("Editing question " + q.getQuestionId());
        versionLabel.setText("Currently version " + q.getVersion()
                           + " - saving will create version " + (q.getVersion() + 1)
                           + " and keep this one in the bank.");

        textArea.setText(q.getText());
        instructionsArea.setText(q.getInstructions() == null ? "" : q.getInstructions());
        topicCombo.setValue(q.getTopic());
        difficultyCombo.setValue(q.getDifficulty());

        TextField[] fields = {answer1, answer2, answer3, answer4};
        RadioButton[] radios = {correct1, correct2, correct3, correct4};
        correctGroup.selectToggle(null);
        for (int i = 0; i < 4; i++) {
            fields[i].clear();
        }
        for (Answer a : q.getAnswers()) {
            int index = a.getAnswerNo() - 1;
            if (index >= 0 && index < 4) {
                fields[index].setText(a.getText());
                if (a.isCorrect()) {
                    correctGroup.selectToggle(radios[index]);
                }
            }
        }
        showImagePreview();
    }

    private Question readForm() {
        Question q = new Question();
        q.setText(textArea.getText().trim());
        q.setInstructions(blankToNull(instructionsArea.getText()));
        q.setTopic(readTopic());
        q.setDifficulty(difficultyCombo.getValue());
        q.setImage(imageBytes);

        TextField[] fields = {answer1, answer2, answer3, answer4};
        RadioButton[] radios = {correct1, correct2, correct3, correct4};
        List<Answer> answers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            answers.add(new Answer(i + 1, fields[i].getText().trim(),
                                   correctGroup.getSelectedToggle() == radios[i]));
        }
        q.setAnswers(answers);
        return q;
    }

    /** The combo is editable, so the topic may be a chosen one or a newly typed one. */
    private String readTopic() {
        String typed = topicCombo.getEditor().getText();
        if (typed != null && !typed.trim().isEmpty()) {
            return typed.trim();
        }
        return topicCombo.getValue() == null ? null : topicCombo.getValue().trim();
    }

    /** Same rules as the server, repeated here only so the user hears sooner. */
    private String validateLocally(Question q) {
        if (q.getText().isEmpty()) {
            return "The question text cannot be empty.";
        }
        if (q.getTopic() == null || q.getTopic().isEmpty()) {
            return "Choose or type a topic. Automatic exam building selects by topic.";
        }
        if (q.getDifficulty() == null) {
            return "Choose a difficulty level.";
        }
        for (Answer a : q.getAnswers()) {
            if (a.getText().isEmpty()) {
                // Deliberately does not name which one. Pointing at "answer 3"
                // is no more helpful than saying they must all be filled in -
                // the empty box is visible on screen - and it reads as nagging.
                return "All four answers must be filled in.";
            }
        }
        if (correctGroup.getSelectedToggle() == null) {
            return "Mark one answer as the correct one.";
        }
        return null;
    }

    private void showImagePreview() {
        boolean has = imageBytes != null && imageBytes.length > 0;
        clearImageButton.setDisable(!has);

        // managed as well as visible: an invisible node that is still "managed"
        // keeps its space in the layout, leaving an empty gap where the picture
        // would be.
        imageFrame.setVisible(has);
        imageFrame.setManaged(has);

        if (has) {
            imagePreview.setImage(new Image(new ByteArrayInputStream(imageBytes)));
            imageLabel.setText((imageBytes.length / 1024) + " KB  ·  stored in the database");
        } else {
            imagePreview.setImage(null);
            imageLabel.setText("no picture");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
