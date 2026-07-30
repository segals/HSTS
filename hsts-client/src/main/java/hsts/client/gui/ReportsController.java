package hsts.client.gui;

import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Report;
import hsts.common.entity.ReportLine;
import hsts.common.enums.ReportType;
import hsts.common.protocol.ReportRequest;
import hsts.common.protocol.ReportSubject;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * SUC-11 / SUC-12 / מתווה scenario 12: the statistical reports.
 *
 * <h2>This screen does not know what the reports are</h2>
 *
 * <p>It asks the server which reports the signed-in user may run, asks the chosen
 * report what it can be run about, and draws whatever comes back. There is no list
 * of report names in this class and no {@code if (byStudent)} anywhere in it.</p>
 *
 * <p>That is deliberate, and it is requirement 64 - <i>"הפקת דו"חות חדשים תדרוש
 * עבודת פיתוח מינימלית"</i> - applied to the client. A fourth report is one enum
 * value and one strategy class on the server, and <b>nothing here at all</b>.</p>
 *
 * <p>The one thing that varies by report is the highlight: the by-student report
 * marks her own result against the class. Even that is driven by the data - a
 * {@link ReportLine} either carries a highlight or it does not - rather than by
 * this class knowing which report is on screen.</p>
 */
public class ReportsController extends GUIScreen {

    private static final String REQ_TYPES    = "rp.types";
    private static final String REQ_SUBJECTS = "rp.subjects";
    private static final String REQ_REPORT   = "rp.report";

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<ReportType> typeList;
    @FXML private Label  subjectHeadingLabel;
    @FXML private ListView<ReportSubject> subjectList;

    @FXML private Label titleLabel;
    @FXML private Label descriptionLabel;
    @FXML private HBox  statsRow;
    @FXML private Label histogramScopeLabel;
    @FXML private HistogramView histogram;
    @FXML private TableView<ReportLine> lineTable;
    @FXML private TableColumn<ReportLine, String> examColumn;
    @FXML private TableColumn<ReportLine, String> detailColumn;
    @FXML private TableColumn<ReportLine, String> countColumn;
    @FXML private TableColumn<ReportLine, String> averageColumn;
    @FXML private TableColumn<ReportLine, String> medianColumn;
    @FXML private TableColumn<ReportLine, String> markColumn;
    @FXML private TableColumn<ReportLine, String> gapColumn;
    @FXML private Label statusLabel;

    private ReportType chosenType;
    private Report currentReport;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Average, median and the decile spread, compared across "
                            + "several exams at once.");

        useWrappingCells(typeList, t -> t.getDisplayName() + "\n" + t.getDescription());
        useWrappingCells(subjectList, s -> s.getName() + "\n" + s.getDetail());

        setUpTable();

        typeList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, type) -> chooseType(type));
        subjectList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, subject) -> {
                    if (chosenType != null && subject != null) {
                        send(RequestType.REPORT_GENERATE,
                             new ReportRequest(chosenType, subject.getKey()), REQ_REPORT);
                    }
                });

        // Clicking a row plots that exam alone; clicking away plots everything.
        lineTable.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, line) -> plot(line));

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNothing();
        send(RequestType.REPORT_TYPES, null, REQ_TYPES);
    }

    private void setUpTable() {
        examColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLabel()));
        detailColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetail()));
        countColumn.setCellValueFactory(c -> new SimpleStringProperty(
                String.valueOf(c.getValue().getStatistics().getGradeCount())));
        averageColumn.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%.1f", c.getValue().getStatistics().getAverage())));
        medianColumn.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%.1f", c.getValue().getStatistics().getMedian())));

        // Data-driven, not report-driven: a line either has a highlight or it does
        // not, and this class never asks which report produced it.
        markColumn.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().hasHighlight() ? String.valueOf(c.getValue().getHighlight()) : ""));
        gapColumn.setCellValueFactory(c -> {
            Double gap = c.getValue().getDifferenceFromAverage();
            return new SimpleStringProperty(gap == null ? ""
                    : String.format("%s%.1f", gap >= 0 ? "+" : "", gap));
        });

        lineTable.setPlaceholder(new Label("Choose a report on the left."));
        lineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    // -----------------------------------------------------------------

    private void chooseType(ReportType type) {
        chosenType = type;
        subjectList.setItems(FXCollections.observableArrayList());
        showNothing();
        if (type == null) {
            return;
        }
        subjectHeadingLabel.setText("WHICH " + type.getSubjectNoun().toUpperCase());
        send(RequestType.REPORT_SUBJECTS, type, REQ_SUBJECTS);
    }

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
            case REQ_TYPES -> {
                List<ReportType> types = (List<ReportType>) response.getPayload();
                typeList.setItems(FXCollections.observableArrayList(types));
                showMessage(response.getMessage());
                if (types.size() == 1) {
                    // A teacher has exactly one report. Making her click it first
                    // would be a step with no choice in it.
                    typeList.getSelectionModel().select(0);
                }
            }
            case REQ_SUBJECTS -> {
                List<ReportSubject> subjects = (List<ReportSubject>) response.getPayload();
                subjectList.setItems(FXCollections.observableArrayList(subjects));
                showMessage(response.getMessage());
                if (subjects.size() == 1) {
                    subjectList.getSelectionModel().select(0);
                }
            }
            case REQ_REPORT -> showReport((Report) response.getPayload(), response.getMessage());
            default -> { }
        }
    }

    private void showNothing() {
        currentReport = null;
        titleLabel.setText("No report yet");
        descriptionLabel.setText("Pick a report, then what it should be about.");
        statsRow.getChildren().clear();
        histogram.show(null);
        histogramScopeLabel.setText("");
        lineTable.setItems(FXCollections.observableArrayList());
    }

    private void showReport(Report report, String note) {
        currentReport = report;
        titleLabel.setText(report.getTitle());
        descriptionLabel.setText(report.getDescription());

        ExamStatistics overall = report.getOverall();
        statsRow.getChildren().setAll(
                statTile("Exams compared", String.valueOf(report.getLines().size())),
                statTile("Marks in all", String.valueOf(overall.getGradeCount())),
                statTile("Average", overall.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", overall.getAverage())),
                statTile("Median", overall.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", overall.getMedian())));

        lineTable.setItems(FXCollections.observableArrayList(report.getLines()));
        lineTable.setPlaceholder(new Label("Nothing to compare - no approved marks yet."));
        lineTable.getSelectionModel().clearSelection();
        plot(null);
        showMessage(note);
    }

    /** Plots one exam, or everything together when nothing is selected. */
    private void plot(ReportLine line) {
        if (currentReport == null) {
            histogram.show(null);
            histogramScopeLabel.setText("");
            return;
        }
        if (line == null) {
            histogram.show(currentReport.getOverall());
            histogramScopeLabel.setText("all "
                    + currentReport.getLines().size() + " exam(s) together");
        } else {
            histogram.show(line.getStatistics());
            histogramScopeLabel.setText(line.getLabel());
        }
    }

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

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
