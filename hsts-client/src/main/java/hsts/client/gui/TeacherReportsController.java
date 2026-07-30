package hsts.client.gui;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.ResultsQuery;
import hsts.common.protocol.ResultsReport;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * SUC-11 / מתווה scenario 10: the teacher's results table and histogram.
 *
 * <p>She picks an exam she wrote, then a sitting - or the first row, which puts
 * every sitting together. The marks come back with their statistics in one reply,
 * so the table and the chart can never disagree about what they are showing.</p>
 *
 * <p><b>Requirement 59 made visible.</b> The sittings list names the teacher who
 * released each one. An exam she wrote and a colleague ran appears here with the
 * colleague's name against it, which is the requirement in one line on screen.</p>
 *
 * <p>Read-only. Nothing on this screen changes a mark.</p>
 */
public class TeacherReportsController extends GUIScreen {

    private static final String REQ_EXAMS    = "tr.exams";
    private static final String REQ_SITTINGS = "tr.sittings";
    private static final String REQ_RESULTS  = "tr.results";

    /**
     * One row of the sittings list.
     *
     * <p>A wrapper rather than a plain {@link ExamExecution} because the first row
     * means "all sittings together" and has no execution behind it. A null item
     * cannot carry that: the shared cell factory draws null as an empty row, and a
     * null selection is indistinguishable from nothing being selected at all.</p>
     */
    private record SittingRow(ExamExecution sitting) {
        boolean isAllTogether() {
            return sitting == null;
        }
    }

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<Exam> examList;
    @FXML private ListView<SittingRow> sittingList;

    @FXML private Label titleLabel;
    @FXML private Label metaLabel;
    @FXML private HBox  statsRow;
    @FXML private HistogramView histogram;
    @FXML private Label tableNoteLabel;
    @FXML private TableView<Grade> markTable;
    @FXML private TableColumn<Grade, String> nameColumn;
    @FXML private TableColumn<Grade, String> markColumn;
    @FXML private TableColumn<Grade, String> autoColumn;
    @FXML private TableColumn<Grade, String> changedColumn;
    @FXML private TableColumn<Grade, String> durationColumn;
    @FXML private TableColumn<Grade, String> statusColumn;
    @FXML private Label statusLabel;

    private Exam chosenExam;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Every exam you wrote, including the ones another teacher "
                            + "handed out (requirement 59). Read-only.");

        useWrappingCells(examList, e ->
                "Exam " + e.getExamId() + "  ·  " + e.getCourseName()
              + "\n" + e.getStatus().getDisplayName()
              + "  ·  " + e.getQuestions().size() + " questions"
              + "  ·  " + e.getDurationMinutes() + " min");

        useWrappingCells(sittingList, row -> row.isAllTogether()
                ? "All sittings together\nEvery class that has sat this exam"
                : "Code " + row.sitting().getExecutionCode()
                  + "  ·  " + row.sitting().getOpenTime().toLocalDate()
                  + "\nreleased by " + row.sitting().getReleasedByName()
                  + "  ·  " + row.sitting().getNumStarted() + " sat it");

        setUpTable();

        examList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, exam) -> chooseExam(exam));
        sittingList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, row) -> {
                    if (chosenExam != null && row != null) {
                        askForResults(row);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNothing();
        send(RequestType.TEACHER_REPORT_EXAMS, null, REQ_EXAMS);
    }

    /**
     * מתווה 10 asks for the marks "in a table". A real table, not a list: the
     * columns are what let a reader compare one student against another at a
     * glance, which is the whole point of asking for one.
     */
    private void setUpTable() {
        nameColumn.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getStudentName()));
        markColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? String.valueOf(c.getValue().getFinalGrade())
                                          : "-"));
        autoColumn.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getAutoGrade())));
        changedColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().wasChangedByHand()
                        ? (c.getValue().getManualChangeExplanation() == null
                                ? "yes" : c.getValue().getManualChangeExplanation())
                        : ""));
        durationColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getActualDuration() == null
                        ? "" : String.valueOf(c.getValue().getActualDuration())));
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? "approved" : "waiting for approval"));

        markTable.setPlaceholder(new Label("Pick an exam on the left."));
        markTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    // -----------------------------------------------------------------

    private void chooseExam(Exam exam) {
        chosenExam = exam;
        sittingList.setItems(FXCollections.observableArrayList());
        showNothing();
        if (exam == null) {
            return;
        }
        send(RequestType.TEACHER_REPORT_SITTINGS, exam.getExamId(), REQ_SITTINGS);
    }

    private void askForResults(SittingRow row) {
        ResultsQuery query = row.isAllTogether()
                ? ResultsQuery.wholeExam(chosenExam.getExamId(), chosenExam.getVersion())
                : ResultsQuery.sitting(chosenExam.getExamId(), chosenExam.getVersion(),
                                       row.sitting().getExecutionId());
        send(RequestType.TEACHER_REPORT_RESULTS, query, REQ_RESULTS);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    /**
     * A mark was approved, changed or factored somewhere. NFR 18.
     *
     * <p>Requirement 59 gives her the results of exams she wrote even when another
     * teacher ran them - so the marks on this screen can change without her doing
     * anything at all. The figures follow rather than going quietly stale.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() != PushType.RESULTS_CHANGED) {
            super.onPush(event);
            return;
        }
        showMessage(event.getMessage());
        send(RequestType.TEACHER_REPORT_EXAMS, null, REQ_EXAMS);
        SittingRow row = sittingList.getSelectionModel().getSelectedItem();
        if (chosenExam != null && row != null) {
            askForResults(row);          // redraw the table and the histogram
        }
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
            case REQ_EXAMS -> {
                List<Exam> exams = (List<Exam>) response.getPayload();
                examList.setItems(FXCollections.observableArrayList(exams));
                if (exams.isEmpty()) {
                    showMessage(response.getMessage());
                }
            }
            case REQ_SITTINGS -> {
                List<ExamExecution> sittings = (List<ExamExecution>) response.getPayload();
                List<SittingRow> rows = new ArrayList<>();
                if (!sittings.isEmpty()) {
                    rows.add(new SittingRow(null));  // "all sittings together"
                    sittings.forEach(x -> rows.add(new SittingRow(x)));
                }
                sittingList.setItems(FXCollections.observableArrayList(rows));
                showMessage(response.getMessage());
                if (!rows.isEmpty()) {
                    sittingList.getSelectionModel().select(0);
                }
            }
            case REQ_RESULTS -> showResults((ResultsReport) response.getPayload(),
                                            response.getMessage());
            default -> { }
        }
    }

    private void showNothing() {
        titleLabel.setText("Nothing chosen");
        metaLabel.setText("Pick one of your exams on the left.");
        statsRow.getChildren().clear();
        histogram.show(null);
        markTable.setItems(FXCollections.observableArrayList());
        tableNoteLabel.setText("");
    }

    private void showResults(ResultsReport report, String note) {
        titleLabel.setText(report.getTitle());
        metaLabel.setText(report.getSubtitle());

        ExamStatistics stats = report.getStatistics();
        statsRow.getChildren().setAll(
                statTile("Approved marks", String.valueOf(stats.getGradeCount())),
                statTile("Average", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getAverage())),
                statTile("Median", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getMedian())),
                statTile("Papers in total", String.valueOf(report.getGrades().size())));

        histogram.show(stats);
        markTable.setItems(FXCollections.observableArrayList(report.getGrades()));
        markTable.setPlaceholder(new Label("Nobody has sat this yet."));

        long waiting = report.getUnapprovedCount();
        tableNoteLabel.setText(waiting == 0
                ? "every paper approved"
                : waiting + " still waiting for approval - those are not in the figures above");
        showMessage(note);
    }

    /** One figure with its name under it - requirement 54's numbers, made readable. */
    private VBox statTile(String caption, String value) {
        Label number = new Label(value);
        number.setStyle("-fx-font-size: 22px; -fx-font-weight: 600;");
        Label name = new Label(caption);
        name.getStyleClass().add("caption");
        name.setWrapText(true);

        VBox tile = new VBox(2, number, name);
        tile.getStyleClass().add("card");
        tile.setMinWidth(130);
        HBox.setHgrow(tile, javafx.scene.layout.Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
