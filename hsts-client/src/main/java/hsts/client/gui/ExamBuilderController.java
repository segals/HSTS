package hsts.client.gui;

import hsts.client.HSTSApp;
import hsts.common.entity.Course;
import hsts.common.entity.Exam;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.Question;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.ExamBuildCriteria;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.QuestionQuota;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * SUC-3 and SUC-4 / מתווה scenario 3: the exam-building screen.
 *
 * <p>Two ways in - pick questions by hand, or describe what is wanted and let the
 * system choose. Both produce a <em>draft</em>, which the teacher then adjusts
 * before saving. Nothing reaches the database until Save, which is what lets the
 * server refuse an impossible automatic request without leaving anything behind.</p>
 */
public class ExamBuilderController extends GUIScreen {

    private static final String REQ_COURSES  = "e.courses";
    private static final String REQ_MY_EXAMS = "e.mine";
    private static final String REQ_BANK     = "e.bank";
    private static final String REQ_TOPICS   = "e.topics";
    private static final String REQ_BUILD    = "e.build";
    private static final String REQ_SAVE     = "e.save";
    private static final String REQ_EDIT     = "e.edit";
    private static final String REQ_GET      = "e.get";
    private static final String REQ_VERSIONS = "e.versions";

    @FXML private Label            subtitleLabel;
    @FXML private ComboBox<Course> courseCombo;
    @FXML private Button           backButton;

    @FXML private ListView<Exam> examList;
    @FXML private Button         newExamButton;
    @FXML private Button         versionsButton;

    @FXML private ToggleGroup modeGroup;
    @FXML private RadioButton manualRadio;
    @FXML private RadioButton automaticRadio;
    @FXML private VBox        manualPane;
    @FXML private VBox        automaticPane;
    @FXML private VBox pickerHolder;

    /** Tick boxes and filters, in place of the old ctrl-click list. */
    private final QuestionPicker picker = new QuestionPicker();
    @FXML private VBox   quotaBox;
    @FXML private Button addQuotaButton;
    @FXML private Label  quotaTotalLabel;
    @FXML private Button buildButton;

    @FXML private Label    examTitleLabel;
    @FXML private Label    examVersionLabel;
    @FXML private Label    totalPointsLabel;
    @FXML private VBox     questionRows;
    @FXML private Button   evenPointsButton;
    @FXML private Button   removeQuestionButton;
    @FXML private TextField examNameField;
    @FXML private Spinner<Integer> durationSpinner;
    @FXML private TextArea studentInstructions;
    @FXML private TextArea teacherNotes;
    @FXML private Button   saveButton;
    @FXML private Button   discardButton;
    @FXML private Label    statusLabel;

    /** The exam being composed. Null id means it has never been saved. */
    private Exam draft;

    /** Topics of the selected course, offered in the quota lines. */
    private List<String> topics = new ArrayList<>();

    /** Which question row is selected, for the Remove button. */
    private int selectedRow = -1;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Points must add up to exactly 100. "
                            + "Editing an exam keeps the previous version.");

        durationSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 600, 60));

        // Ticks rather than ctrl-click: a selection you can see, that survives
        // filtering, and that needs no instructions. See QuestionPicker.
        pickerHolder.getChildren().add(picker);
        VBox.setVgrow(picker, Priority.ALWAYS);

        useWrappingCells(examList, e ->
                e.describe() + "  ·  v" + e.getVersion() + "  ·  " + e.getStatus().getDisplayName()
              + "\n" + e.getQuestionCount() + (e.getQuestionCount() == 1 ? " question" : " questions")
              + "  ·  " + e.getDurationMinutes() + " min");

        modeGroup.selectedToggleProperty().addListener((obs, old, mode) -> {
            boolean automatic = mode == automaticRadio;
            manualPane.setVisible(!automatic);
            manualPane.setManaged(!automatic);
            automaticPane.setVisible(automatic);
            automaticPane.setManaged(automatic);
            if (automatic && quotaBox.getChildren().isEmpty()) {
                onAddQuota();
            }
        });

        courseCombo.valueProperty().addListener((obs, old, course) -> {
            if (course != null) {
                send(RequestType.QUESTION_LIST_BY_COURSE, course.getCourseCode(), REQ_BANK);
                send(RequestType.QUESTION_TOPICS, course.getCourseCode(), REQ_TOPICS);
            }
        });

        examList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, exam) -> {
                    if (exam != null) {
                        send(RequestType.EXAM_GET, new ExamRef(exam.getExamId(), exam.getVersion()),
                             REQ_GET);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        startNewExam();
        send(RequestType.COURSE_LIST_MINE, null, REQ_COURSES);
        send(RequestType.EXAM_LIST_MINE, null, REQ_MY_EXAMS);
    }

    /**
     * The coordinator decided on one of this teacher's exams.
     *
     * <p>Overridden so the exam list reloads and shows the new status, not just a
     * message. NFR 18 means the data updates by itself - a message saying
     * something changed, over a list still showing the old state, would be worse
     * than a Refresh button.</p>
     *
     * <p>This is the visible proof of server push at the demo: the coordinator
     * rejects on one machine, and this screen changes on the other without anyone
     * touching it.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        super.onPush(event);
        if (event.getType() == PushType.EXAM_APPROVED
                || event.getType() == PushType.EXAM_REJECTED) {
            send(RequestType.EXAM_LIST_MINE, null, REQ_MY_EXAMS);
        }
    }

    // -----------------------------------------------------------------
    //  Buttons
    // -----------------------------------------------------------------

    @FXML
    private void onNewExam() {
        examList.getSelectionModel().clearSelection();
        startNewExam();
        showMessage("Composing a new exam. Choose questions, then Build draft.");
    }

    @FXML
    private void onAddQuota() {
        quotaBox.getChildren().add(new QuotaRow(topics).node());
        updateQuotaTotal();
    }

    @FXML
    private void onBuild() {
        Course course = courseCombo.getValue();
        if (course == null) {
            showError("Choose a course first.");
            return;
        }

        ExamBuildCriteria criteria;
        if (automaticRadio.isSelected()) {
            List<QuestionQuota> quotas = new ArrayList<>();
            for (javafx.scene.Node node : quotaBox.getChildren()) {
                QuotaRow row = (QuotaRow) node.getUserData();
                QuestionQuota quota = row.toQuota();
                if (quota != null) {
                    quotas.add(quota);
                }
            }
            if (quotas.isEmpty()) {
                showError("Add at least one line saying how many questions you want.");
                return;
            }
            criteria = ExamBuildCriteria.automatic(course.getCourseCode(), quotas);
        } else {
            List<String> ids = picker.getChosenIds();
            if (ids.isEmpty()) {
                showError("Tick at least one question from the bank.");
                return;
            }
            criteria = ExamBuildCriteria.manual(course.getCourseCode(), ids);
        }

        buildButton.setDisable(true);
        showMessage("Building...");
        send(RequestType.EXAM_BUILD_DRAFT, criteria, REQ_BUILD);
    }

    @FXML
    private void onDistributeEvenly() {
        if (draft == null || draft.getQuestions().isEmpty()) {
            return;
        }
        draft.distributePointsEvenly();
        renderQuestionRows();
        showMessage("Points shared evenly. Total is now " + draft.getTotalPoints() + ".");
    }

    @FXML
    private void onRemoveQuestion() {
        if (draft == null || selectedRow < 0 || selectedRow >= draft.getQuestions().size()) {
            showError("Click a question in the list on the right first.");
            return;
        }
        draft.getQuestions().remove(selectedRow);
        selectedRow = -1;
        renderQuestionRows();
        showMessage("Question removed. Remember the points must still add up to 100.");
    }

    @FXML
    private void onSave() {
        if (draft == null || draft.getQuestions().isEmpty()) {
            showError("Build a draft first.");
            return;
        }
        readFormIntoDraft();

        if (draft.getName() == null || draft.getName().isBlank()) {
            showError("Give the exam a name first - something a colleague would "
                    + "recognise, like \"Plane Geometry mid-term\".");
            examNameField.requestFocus();
            return;
        }

        int total = draft.getTotalPoints();
        if (total != Exam.REQUIRED_TOTAL_POINTS) {
            showError("The points add up to " + total + ", not "
                    + Exam.REQUIRED_TOTAL_POINTS + ". Adjust them, or use "
                    + "\"Share points evenly\".");
            return;
        }

        saveButton.setDisable(true);
        if (draft.getExamId() == null) {
            showMessage("Saving...");
            send(RequestType.EXAM_SAVE, draft, REQ_SAVE);
        } else {
            showMessage("Saving as a new version...");
            send(RequestType.EXAM_EDIT, draft, REQ_EDIT);
        }
    }

    @FXML
    private void onShowVersions() {
        Exam selected = examList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Select an exam in the list first.");
            return;
        }
        send(RequestType.EXAM_VERSIONS, new ExamRef(selected.getExamId()), REQ_VERSIONS);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
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
            buildButton.setDisable(false);
            saveButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_COURSES -> {
                List<Course> courses = (List<Course>) response.getPayload();
                courseCombo.setItems(FXCollections.observableArrayList(courses));
                if (courses.isEmpty()) {
                    showError("You do not teach any courses, so there are no exams to build.");
                } else {
                    courseCombo.getSelectionModel().selectFirst();
                }
            }
            case REQ_MY_EXAMS -> examList.setItems(
                    FXCollections.observableArrayList((List<Exam>) response.getPayload()));
            case REQ_BANK -> picker.setQuestions((List<Question>) response.getPayload());
            case REQ_TOPICS -> topics = (List<String>) response.getPayload();
            case REQ_BUILD -> {
                buildButton.setDisable(false);
                showDraft((Exam) response.getPayload(), false);
                showSuccess(response.getMessage());
            }
            case REQ_GET -> showDraft((Exam) response.getPayload(), true);
            case REQ_SAVE, REQ_EDIT -> {
                saveButton.setDisable(false);
                showSuccess(response.getMessage());
                send(RequestType.EXAM_LIST_MINE, null, REQ_MY_EXAMS);
                showDraft((Exam) response.getPayload(), true);
            }
            case REQ_VERSIONS -> showExamVersions((List<Exam>) response.getPayload());
            default -> { }
        }
    }

    /**
     * Opens the version-history window.
     *
     * <p>This was a block of text in a dialog. It proved the older versions were
     * still there and left the reader to find the difference between two
     * paragraphs herself - and on an exam the difference is usually one question
     * out of ten, which is exactly the kind of thing reading two lists side by
     * side does not find. It is now the same side-by-side comparison a question
     * has, with the paper itself as one of the fields.</p>
     *
     * <p>A window rather than a dialog, so it can be left open while she looks at
     * the exam underneath it - which is the moment anybody wants to.</p>
     */
    private void showExamVersions(List<Exam> versions) {
        if (versions == null || versions.isEmpty()) {
            showError("No version history came back from the server.");
            return;
        }
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/fxml/ExamVersionHistory.fxml"));
            javafx.scene.Scene scene = new javafx.scene.Scene(loader.load());
            hsts.client.HSTSApp.applyStylesheet(scene);

            ExamVersionHistoryController window = loader.getController();
            window.setVersions(versions);

            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setTitle("Version history - " + versions.get(0).describe());
            stage.setScene(scene);
            stage.initOwner(hsts.client.HSTSApp.getPrimaryStage());
            stage.show();
            fitToScreen(stage);

            showMessage(versions.size() == 1
                    ? "This exam has only one version - it has not been edited yet."
                    : versions.size() + " versions stored. The older ones are still "
                      + "in the database, with the questions they had at the time.");
        } catch (Exception e) {
            showError("Could not open the version history: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    //  The draft
    // -----------------------------------------------------------------

    private void startNewExam() {
        draft = null;
        selectedRow = -1;
        examTitleLabel.setText("New exam");
        examVersionLabel.setText("It will be given the next free 6-digit id: "
                               + "2 digits exam, 2 course, 2 subject.");
        questionRows.getChildren().clear();
        examNameField.clear();
        studentInstructions.clear();
        teacherNotes.clear();
        durationSpinner.getValueFactory().setValue(60);
        updateTotalLabel();
    }

    private void showDraft(Exam exam, boolean saved) {
        draft = exam;
        selectedRow = -1;

        if (saved && exam.getExamId() != null) {
            examTitleLabel.setText("Exam " + exam.getExamId() + "   ·   "
                                 + exam.getStatus().getDisplayName());
            examVersionLabel.setText("Version " + exam.getVersion()
                    + " - saving will create version " + (exam.getVersion() + 1)
                    + ", keep this one, and send the new one for approval again.");
        } else {
            examTitleLabel.setText("Draft exam - not saved yet");
            examVersionLabel.setText("Adjust the points and duration, then Save.");
        }

        examNameField.setText(exam.getName() == null ? "" : exam.getName());
        studentInstructions.setText(exam.getInstructionsForStudents() == null
                ? "" : exam.getInstructionsForStudents());
        teacherNotes.setText(exam.getNotesForTeacher() == null
                ? "" : exam.getNotesForTeacher());
        durationSpinner.getValueFactory().setValue(
                exam.getDurationMinutes() > 0 ? exam.getDurationMinutes() : 60);

        renderQuestionRows();
    }

    /** One row per question: its text, and a spinner for its points. */
    private void renderQuestionRows() {
        questionRows.getChildren().clear();
        if (draft == null) {
            updateTotalLabel();
            return;
        }

        List<ExamQuestion> questions = draft.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            final int index = i;
            ExamQuestion eq = questions.get(i);

            Label text = new Label((i + 1) + ".  " + describe(eq));
            text.setWrapText(true);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(Double.MAX_VALUE);

            Spinner<Integer> points = new Spinner<>(1, 100, eq.getPoints());
            points.setPrefWidth(80);
            points.valueProperty().addListener((obs, old, value) -> {
                eq.setPoints(value);
                updateTotalLabel();
            });

            HBox row = new HBox(10, text, points);
            row.setStyle("-fx-padding: 6 8 6 8;");
            row.setOnMouseClicked(e -> {
                selectedRow = index;
                highlightSelectedRow();
            });
            questionRows.getChildren().add(row);
        }
        highlightSelectedRow();
        updateTotalLabel();
    }

    private void highlightSelectedRow() {
        for (int i = 0; i < questionRows.getChildren().size(); i++) {
            questionRows.getChildren().get(i).getStyleClass()
                    .removeAll("diff-changed", "diff-same");
            questionRows.getChildren().get(i).getStyleClass()
                    .add(i == selectedRow ? "diff-changed" : "diff-same");
        }
    }

    private String describe(ExamQuestion eq) {
        Question q = eq.getQuestion();
        if (q == null) {
            return eq.getQuestionId() + "  (v" + eq.getQuestionVersion() + ")";
        }
        // Not shortened: the row's label wraps, so the whole question is readable.
        // Cutting it at 80 characters put "..." in the middle of the text the
        // teacher was reading to decide how many points it is worth.
        return q.getText().replace('\n', ' ')
             + "      [" + q.getQuestionId() + " v" + eq.getQuestionVersion()
             + ", " + q.getTopic() + ", " + q.getDifficulty().getDisplayName() + "]";
    }

    /** Live total, so the 100-point rule is visible while editing rather than at Save. */
    private void updateTotalLabel() {
        int total = (draft == null) ? 0 : draft.getTotalPoints();
        int count = (draft == null) ? 0 : draft.getQuestionCount();
        totalPointsLabel.setText(count + " questions  ·  total " + total + " / "
                               + Exam.REQUIRED_TOTAL_POINTS);
        totalPointsLabel.getStyleClass().removeAll("status-success", "status-error");
        if (count > 0) {
            totalPointsLabel.getStyleClass().add(
                    total == Exam.REQUIRED_TOTAL_POINTS ? "status-success" : "status-error");
        }
    }

    private void readFormIntoDraft() {
        // Compulsory. The server refuses a blank one too - this only saves her a
        // round trip and puts the cursor where the problem is.
        draft.setName(examNameField.getText() == null ? "" : examNameField.getText().trim());
        draft.setDurationMinutes(durationSpinner.getValue());
        draft.setInstructionsForStudents(blankToNull(studentInstructions.getText()));
        draft.setNotesForTeacher(blankToNull(teacherNotes.getText()));
    }

    private void updateQuotaTotal() {
        int total = 0;
        for (javafx.scene.Node node : quotaBox.getChildren()) {
            QuotaRow row = (QuotaRow) node.getUserData();
            QuestionQuota quota = row.toQuota();
            if (quota != null) {
                total += quota.getCount();
            }
        }
        quotaTotalLabel.setText("total: " + total + " question(s)");
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    // -----------------------------------------------------------------
    //  One line of an automatic request
    // -----------------------------------------------------------------

    /**
     * A single "how many of what" line: topic, difficulty, count.
     *
     * <p>Both the topic and the difficulty may be left blank, which the server
     * reads as "any". That matters for a teacher who cares only about the mix of
     * difficulty and not about topics.</p>
     */
    private final class QuotaRow {

        /**
         * A flow, not a row.
         *
         * <p>Reported from the screen with a picture: the panel was narrow and the
         * line read "any topic | any level | 5" with the count box half there and
         * the × gone off the edge - a button you cannot see is a line you cannot
         * remove. Four things side by side need about 420 points and the panel is
         * allowed to be 330, so at some width they cannot all fit however small
         * their minimums are made. A flow pane puts what will not fit on the next
         * line instead of off the end.</p>
         */
        private final javafx.scene.layout.FlowPane box;
        private final ComboBox<String> topic = new ComboBox<>();
        private final ComboBox<DifficultyLevel> difficulty = new ComboBox<>();
        private final Spinner<Integer> count = new Spinner<>(1, 100, 5);

        QuotaRow(List<String> availableTopics) {
            // The topic box grows with the panel. At a fixed width it showed
            // "any tc" and the difficulty box showed "...", which told the
            // teacher nothing about what she had actually chosen.
            topic.setEditable(true);
            topic.setPromptText("any topic");
            topic.setItems(FXCollections.observableArrayList(availableTopics));
            topic.setMinWidth(140);
            topic.setPrefWidth(160);

            difficulty.setPromptText("any level");
            difficulty.setItems(FXCollections.observableArrayList(DifficultyLevel.values()));
            difficulty.setMinWidth(125);
            difficulty.setPrefWidth(125);

            count.setPrefWidth(88);
            count.setMinWidth(88);
            count.valueProperty().addListener((o, a, b) -> updateQuotaTotal());

            // The row has to exist before the remove button's action can refer to
            // it - a lambda may only capture a field that is already assigned.
            box = new javafx.scene.layout.FlowPane(6, 6, topic, difficulty, count);
            box.setUserData(this);

            Button remove = new Button("×");
            remove.getStyleClass().add("quiet");
            remove.setOnAction(e -> {
                quotaBox.getChildren().remove(box);
                updateQuotaTotal();
            });
            box.getChildren().add(remove);
        }

        javafx.scene.layout.FlowPane node() {
            return box;
        }

        /** Null when this line asks for nothing. */
        QuestionQuota toQuota() {
            Integer howMany = count.getValue();
            if (howMany == null || howMany <= 0) {
                return null;
            }
            String topicText = topic.getEditor().getText();
            if (topicText == null || topicText.isBlank()) {
                topicText = topic.getValue();
            }
            return new QuestionQuota(
                    (topicText == null || topicText.isBlank()) ? null : topicText.trim(),
                    difficulty.getValue(),
                    howMany);
        }
    }
}
