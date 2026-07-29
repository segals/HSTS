package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Question;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.ResultsQuery;
import hsts.common.protocol.ResultsReport;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SUC-12 / מתווה scenario 11: the principal browsing questions, exams and results.
 *
 * <p>Requirement 62 gives her read-only access to all three, so all three are tabs
 * of one screen. There is no Save, Edit or Delete anywhere here - and behind the
 * screen there is no request that could write one, so the restriction does not
 * depend on this class remembering to leave a button out.</p>
 *
 * <p>She sees exams complete, including the author's private notes. Acceptance
 * test 4.10 forbids showing those to a <b>student</b>; requirement 62 says the
 * principal gets "כלל הנתונים" - all of it.</p>
 */
public class PrincipalBrowseController extends GUIScreen {

    private static final String REQ_QUESTIONS = "pb.questions";
    private static final String REQ_QUESTION_GET = "pb.question";
    private static final String REQ_EXAMS     = "pb.exams";
    private static final String REQ_EXAM_GET  = "pb.exam";
    private static final String REQ_SITTINGS  = "pb.sittings";
    private static final String REQ_RESULTS   = "pb.results";

    private static final String ALL_COURSES = "All courses";

    /** See {@code TeacherReportsController.SittingRow} - same reason. */
    private record SittingRow(ExamExecution sitting) {
        boolean isAllTogether() {
            return sitting == null;
        }
    }

    @FXML private Label   subtitleLabel;
    @FXML private Button  backButton;
    @FXML private TabPane tabs;
    @FXML private Label   statusLabel;

    // ---- questions ----
    @FXML private ComboBox<String>  questionCourseBox;
    @FXML private TextField         questionSearchField;
    @FXML private Label             questionCountLabel;
    @FXML private ListView<Question> questionList;
    @FXML private VBox              questionDetailBox;

    // ---- exams ----
    @FXML private TextField     examSearchField;
    @FXML private Label         examCountLabel;
    @FXML private ListView<Exam> examList;
    @FXML private VBox          examDetailBox;

    // ---- results ----
    @FXML private ListView<Exam>       resultExamList;
    @FXML private ListView<SittingRow> resultSittingList;
    @FXML private Label   resultTitleLabel;
    @FXML private Label   resultMetaLabel;
    @FXML private HBox    resultStatsRow;
    @FXML private HistogramView resultHistogram;
    @FXML private Label   resultNoteLabel;
    @FXML private TableView<Grade> resultTable;
    @FXML private TableColumn<Grade, String> rName;
    @FXML private TableColumn<Grade, String> rMark;
    @FXML private TableColumn<Grade, String> rAuto;
    @FXML private TableColumn<Grade, String> rCourse;
    @FXML private TableColumn<Grade, String> rDuration;
    @FXML private TableColumn<Grade, String> rStatus;

    /** Everything the server sent, before the search boxes narrow it. */
    private final List<Question> allQuestions = new ArrayList<>();
    private final List<Exam> allExams = new ArrayList<>();

    private Exam chosenResultExam;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Everything in the system, read-only (requirement 62). "
                            + "Nothing on this screen can change anything.");

        useWrappingCells(questionList, q ->
                q.getQuestionId() + "  ·  " + q.getTopic()
              + "  ·  " + q.getDifficulty().getDisplayName()
              + "\n" + q.getText());

        useWrappingCells(examList, e ->
                "Exam " + e.getExamId() + "  ·  " + e.getCourseName()
              + "\n" + e.getAuthorName() + "  ·  " + e.getStatus().getDisplayName()
              + "  ·  " + e.getQuestions().size() + " questions");

        useWrappingCells(resultExamList, e ->
                "Exam " + e.getExamId() + "  ·  " + e.getCourseName()
              + "\nwritten by " + e.getAuthorName());

        useWrappingCells(resultSittingList, row -> row.isAllTogether()
                ? "All sittings together\nEvery class that has sat this exam"
                : "Code " + row.sitting().getExecutionCode()
                  + "  ·  " + row.sitting().getOpenTime().toLocalDate()
                  + "\nreleased by " + row.sitting().getReleasedByName()
                  + "  ·  " + row.sitting().getNumStarted() + " sat it");

        setUpResultTable();

        // The list carries no answers - the server leaves them off deliberately -
        // so the full question is fetched when one is picked. Drawing the list's
        // copy would show a question with no options under it at all.
        questionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, q) -> {
                    if (q == null) {
                        clearQuestionDetail();
                    } else {
                        send(RequestType.PRINCIPAL_QUESTION_GET,
                             new QuestionRef(q.getQuestionId(), q.getVersion()), REQ_QUESTION_GET);
                    }
                });
        examList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, e) -> {
                    if (e != null) {
                        send(RequestType.PRINCIPAL_EXAM_GET,
                             new ExamRef(e.getExamId(), e.getVersion()), REQ_EXAM_GET);
                    }
                });
        resultExamList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, e) -> chooseResultExam(e));
        resultSittingList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, row) -> {
                    if (chosenResultExam != null && row != null) {
                        askForResults(row);
                    }
                });

        questionCourseBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, course) -> applyQuestionFilter());
        questionSearchField.textProperty()
                .addListener((obs, old, text) -> applyQuestionFilter());
        examSearchField.textProperty()
                .addListener((obs, old, text) -> applyExamFilter());

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        clearQuestionDetail();
        clearExamDetail();
        clearResults();

        send(RequestType.PRINCIPAL_QUESTIONS, null, REQ_QUESTIONS);
        send(RequestType.PRINCIPAL_EXAMS, null, REQ_EXAMS);
    }

    private void setUpResultTable() {
        rName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStudentName()));
        rMark.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? String.valueOf(c.getValue().getFinalGrade()) : "-"));
        rAuto.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getAutoGrade())));
        rCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCourseName()));
        rDuration.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getActualDuration() == null
                        ? "" : String.valueOf(c.getValue().getActualDuration())));
        rStatus.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? "approved" : "waiting for approval"));

        resultTable.setPlaceholder(new Label("Pick an exam on the left."));
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
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
            showError(response.getMessage());
            return;
        }
        switch (id) {
            case REQ_QUESTIONS -> {
                allQuestions.clear();
                allQuestions.addAll((List<Question>) response.getPayload());
                fillCourseBox();
                applyQuestionFilter();
            }
            case REQ_EXAMS -> {
                allExams.clear();
                allExams.addAll((List<Exam>) response.getPayload());
                applyExamFilter();

                // Only exams somebody has actually sat belong on the results tab;
                // an exam nobody has taken has no results to browse.
                resultExamList.setItems(FXCollections.observableArrayList(allExams));
            }
            case REQ_QUESTION_GET -> showQuestion((Question) response.getPayload());
            case REQ_EXAM_GET  -> showExam((Exam) response.getPayload());
            case REQ_SITTINGS  -> {
                List<ExamExecution> sittings = (List<ExamExecution>) response.getPayload();
                List<SittingRow> rows = new ArrayList<>();
                if (!sittings.isEmpty()) {
                    rows.add(new SittingRow(null));
                    sittings.forEach(x -> rows.add(new SittingRow(x)));
                }
                resultSittingList.setItems(FXCollections.observableArrayList(rows));
                showMessage(response.getMessage());
                if (!rows.isEmpty()) {
                    resultSittingList.getSelectionModel().select(0);
                } else {
                    clearResults();
                }
            }
            case REQ_RESULTS -> showResults((ResultsReport) response.getPayload(),
                                            response.getMessage());
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  Questions
    // -----------------------------------------------------------------

    private void fillCourseBox() {
        List<String> courses = new ArrayList<>();
        courses.add(ALL_COURSES);
        allQuestions.stream()
                .map(Question::getCourseCode)
                .distinct()
                .sorted()
                .forEach(courses::add);
        String kept = questionCourseBox.getValue();
        questionCourseBox.setItems(FXCollections.observableArrayList(courses));
        questionCourseBox.setValue(courses.contains(kept) ? kept : ALL_COURSES);
    }

    private void applyQuestionFilter() {
        String course = questionCourseBox.getValue();
        String search = text(questionSearchField);

        List<Question> shown = new ArrayList<>();
        for (Question q : allQuestions) {
            boolean courseMatches = course == null || ALL_COURSES.equals(course)
                                 || course.equals(q.getCourseCode());
            boolean textMatches = search.isEmpty()
                    || contains(q.getText(), search)
                    || contains(q.getTopic(), search)
                    || contains(q.getQuestionId(), search);
            if (courseMatches && textMatches) {
                shown.add(q);
            }
        }
        questionList.setItems(FXCollections.observableArrayList(shown));
        questionCountLabel.setText(shown.size() + " of " + allQuestions.size() + " shown");
        if (shown.isEmpty()) {
            clearQuestionDetail();
        }
    }

    private void clearQuestionDetail() {
        questionDetailBox.getChildren().setAll(caption("Pick a question on the left."));
    }

    private void showQuestion(Question q) {
        if (q == null) {
            clearQuestionDetail();
            return;
        }
        List<javafx.scene.Node> parts = new ArrayList<>();
        parts.add(heading(q.getText()));
        parts.add(caption(q.getQuestionId() + "  ·  version " + q.getVersion()
                + "  ·  course " + q.getCourseCode()
                + "  ·  " + q.getTopic()
                + "  ·  " + q.getDifficulty().getDisplayName()
                + "  ·  written by " + q.getAuthorName()));

        if (q.getInstructions() != null && !q.getInstructions().isBlank()) {
            parts.add(wrapped("Instructions:  " + q.getInstructions()));
        }

        for (Answer option : q.getAnswers()) {
            Label line = wrapped("   " + option.getAnswerNo() + ".  " + option.getText()
                    + (option.isCorrect() ? "     ← the correct answer" : ""));
            if (option.isCorrect()) {
                line.getStyleClass().add("status-success");
            }
            parts.add(line);
        }
        questionDetailBox.getChildren().setAll(parts);
    }

    // -----------------------------------------------------------------
    //  Exams
    // -----------------------------------------------------------------

    private void applyExamFilter() {
        String search = text(examSearchField);
        List<Exam> shown = new ArrayList<>();
        for (Exam e : allExams) {
            if (search.isEmpty()
                    || contains(e.getExamId(), search)
                    || contains(e.getCourseName(), search)
                    || contains(e.getAuthorName(), search)
                    || contains(e.getStatus().getDisplayName(), search)) {
                shown.add(e);
            }
        }
        examList.setItems(FXCollections.observableArrayList(shown));
        examCountLabel.setText(shown.size() + " of " + allExams.size() + " shown");
        if (shown.isEmpty()) {
            clearExamDetail();
        }
    }

    private void clearExamDetail() {
        examDetailBox.getChildren().setAll(caption("Pick an exam on the left."));
    }

    private void showExam(Exam exam) {
        List<javafx.scene.Node> parts = new ArrayList<>();
        parts.add(heading("Exam " + exam.getExamId() + "  ·  " + exam.getCourseName()));
        parts.add(caption("version " + exam.getVersion()
                + "  ·  written by " + exam.getAuthorName()
                + "  ·  " + exam.getDurationMinutes() + " minutes"
                + "  ·  " + exam.getStatus().getDisplayName()
                + "  ·  " + exam.getTotalPoints() + " points in total"));

        if (exam.getRejectionReason() != null && !exam.getRejectionReason().isBlank()) {
            Label why = wrapped("Rejected because:  " + exam.getRejectionReason());
            why.getStyleClass().add("status-error");
            parts.add(why);
        }
        if (exam.getInstructionsForStudents() != null
                && !exam.getInstructionsForStudents().isBlank()) {
            parts.add(wrapped("For the students:  " + exam.getInstructionsForStudents()));
        }
        // Requirement 62: she sees all the data. A student may not see this
        // (acceptance test 4.10); the principal may.
        if (exam.getNotesForTeacher() != null && !exam.getNotesForTeacher().isBlank()) {
            Label note = wrapped("The author's private note:  " + exam.getNotesForTeacher());
            note.getStyleClass().add("diff-changed");
            parts.add(note);
        }

        int number = 1;
        for (ExamQuestion eq : exam.getQuestions()) {
            Question q = eq.getQuestion();
            VBox block = new VBox(4);
            block.getStyleClass().add("card");
            block.getChildren().add(caption(number++ + ".      " + eq.getPoints()
                    + (eq.getPoints() == 1 ? " point" : " points")
                    + "      " + eq.getQuestionId() + " v" + eq.getQuestionVersion()));
            block.getChildren().add(wrapped(q == null ? "(question not loaded)" : q.getText()));
            if (q != null) {
                for (Answer option : q.getAnswers()) {
                    Label line = wrapped("   " + option.getAnswerNo() + ".  " + option.getText()
                            + (option.isCorrect() ? "     ← correct" : ""));
                    if (option.isCorrect()) {
                        line.getStyleClass().add("status-success");
                    }
                    block.getChildren().add(line);
                }
            }
            parts.add(block);
        }
        examDetailBox.getChildren().setAll(parts);
    }

    // -----------------------------------------------------------------
    //  Results
    // -----------------------------------------------------------------

    private void chooseResultExam(Exam exam) {
        chosenResultExam = exam;
        resultSittingList.setItems(FXCollections.observableArrayList());
        clearResults();
        if (exam != null) {
            send(RequestType.PRINCIPAL_SITTINGS, exam.getExamId(), REQ_SITTINGS);
        }
    }

    private void askForResults(SittingRow row) {
        ResultsQuery query = row.isAllTogether()
                ? ResultsQuery.wholeExam(chosenResultExam.getExamId(),
                                         chosenResultExam.getVersion())
                : ResultsQuery.sitting(chosenResultExam.getExamId(),
                                       chosenResultExam.getVersion(),
                                       row.sitting().getExecutionId());
        send(RequestType.PRINCIPAL_RESULTS, query, REQ_RESULTS);
    }

    private void clearResults() {
        resultTitleLabel.setText("Nothing chosen");
        resultMetaLabel.setText("Pick an exam, then a sitting.");
        resultStatsRow.getChildren().clear();
        resultHistogram.show(null);
        resultTable.setItems(FXCollections.observableArrayList());
        resultNoteLabel.setText("");
    }

    private void showResults(ResultsReport report, String note) {
        resultTitleLabel.setText(report.getTitle());
        resultMetaLabel.setText(report.getSubtitle());

        ExamStatistics stats = report.getStatistics();
        resultStatsRow.getChildren().setAll(
                statTile("Approved marks", String.valueOf(stats.getGradeCount())),
                statTile("Average", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getAverage())),
                statTile("Median", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getMedian())),
                statTile("Papers in total", String.valueOf(report.getGrades().size())));

        resultHistogram.show(stats);
        resultTable.setItems(FXCollections.observableArrayList(report.getGrades()));
        resultTable.setPlaceholder(new Label("Nobody has sat this yet."));

        long waiting = report.getUnapprovedCount();
        resultNoteLabel.setText(waiting == 0
                ? "every paper approved"
                : waiting + " still waiting for approval - not in the figures above");
        showMessage(note);
    }

    // -----------------------------------------------------------------

    private VBox statTile(String caption, String value) {
        Label number = new Label(value);
        number.setStyle("-fx-font-size: 22px; -fx-font-weight: 600;");
        Label name = new Label(caption);
        name.getStyleClass().add("caption");
        name.setWrapText(true);

        VBox tile = new VBox(2, number, name);
        tile.getStyleClass().add("card");
        tile.setMinWidth(130);
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("h3");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("caption");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String haystack, String needleLowerCase) {
        return haystack != null
            && haystack.toLowerCase(Locale.ROOT).contains(needleLowerCase);
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
