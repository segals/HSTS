package hsts.client.gui;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.protocol.ExamReleaseRequest;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.util.ExecutionCode;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SUC-6 / מתווה scenario 5: the release screen.
 *
 * <p>Pick an approved exam, choose when students may start, choose the code, and
 * release. The same exam may be released again later for another class - each
 * release is a separate sitting.</p>
 */
public class ExamReleaseController extends GUIScreen {

    private static final String REQ_RELEASABLE = "r.releasable";
    private static final String REQ_MINE       = "r.mine";
    private static final String REQ_CODE       = "r.code";
    private static final String REQ_RELEASE    = "r.release";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;

    @FXML private Label            releasableCountLabel;
    @FXML private ListView<Exam>   approvedList;

    @FXML private Label      chosenExamLabel;
    @FXML private Label      chosenExamMetaLabel;
    @FXML private DatePicker openDate;
    @FXML private Spinner<Integer> openHour;
    @FXML private Spinner<Integer> openMinute;
    @FXML private Button     nowButton;
    @FXML private DatePicker closeDate;
    @FXML private Spinner<Integer> closeHour;
    @FXML private Spinner<Integer> closeMinute;
    @FXML private TextField  codeField;
    @FXML private Button     generateButton;
    @FXML private Spinner<Integer> durationSpinner;
    @FXML private Spinner<Integer> attemptsSpinner;
    @FXML private Button     releaseButton;
    @FXML private Label      statusLabel;

    @FXML private ListView<ExamExecution> executionList;

    /** The approved exam version chosen on the left, or null. */
    private Exam chosen;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Only approved exams can be released. "
                            + "The same exam may be released more than once.");

        openHour.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 23, 9));
        openMinute.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 59, 0, 5));
        closeHour.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 23, 12));
        closeMinute.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 59, 0, 5));
        durationSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 600, 60, 5));
        attemptsSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));

        openDate.setValue(LocalDate.now());
        closeDate.setValue(LocalDate.now());

        // The dot marks an exam of HERS approved since she last opened this screen.
        // The badge on the menu counts the same exams; this says which they are.
        useWrappingCells(approvedList, e ->
                e.describe() + "  ·  v" + e.getVersion() + "  ·  " + e.getCourseName()
              + "\nby " + e.getAuthorName() + "  ·  " + e.getDurationMinutes() + " min"
              + (e.isNewlyApproved() ? "\nApproved since you last looked." : ""),
                Exam::isNewlyApproved);

        useWrappingCells(executionList, x ->
                "Code " + x.getExecutionCode() + "  ·  " + x.describeExam()
                        + " v" + x.getExamVersion()
              + "\n" + x.getOpenTime().format(WHEN) + "  →  " + x.getCloseTime().format(WHEN)
              + "\n" + x.getAllocatedDuration() + " min  ·  "
                     + attemptsText(x.getMaxAttempts()) + "  ·  " + describeState(x));

        approvedList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, exam) -> chooseExam(exam));

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        chooseExam(null);
        reload();
        send(RequestType.EXECUTION_SUGGEST_CODE, null, REQ_CODE);
    }

    private void reload() {
        send(RequestType.EXECUTION_RELEASABLE_EXAMS, null, REQ_RELEASABLE);
        send(RequestType.EXECUTION_LIST_MINE, null, REQ_MINE);
    }

    // -----------------------------------------------------------------
    //  Buttons
    // -----------------------------------------------------------------

    @FXML
    private void onSetNow() {
        LocalDateTime now = LocalDateTime.now();
        openDate.setValue(now.toLocalDate());
        openHour.getValueFactory().setValue(now.getHour());
        openMinute.getValueFactory().setValue(now.getMinute());

        // ...and an end an hour later, because a sitting that opens now and closes
        // at whatever the boxes happened to hold is either already over or open for
        // days. An hour is a sensible default for a class sitting down now; she can
        // still type anything she likes over it.
        LocalDateTime end = now.plusHours(1);
        closeDate.setValue(end.toLocalDate());
        closeHour.getValueFactory().setValue(end.getHour());
        closeMinute.getValueFactory().setValue(end.getMinute());

        showMessage("Opens now and closes at " + String.format("%02d:%02d", end.getHour(),
                end.getMinute()) + ". Change either if you need to.");
    }

    @FXML
    private void onGenerate() {
        send(RequestType.EXECUTION_SUGGEST_CODE, null, REQ_CODE);
    }

    @FXML
    private void onRelease() {
        if (chosen == null) {
            showError("Choose an approved exam from the list on the left.");
            return;
        }

        LocalDateTime open = readMoment(openDate, openHour, openMinute);
        LocalDateTime close = readMoment(closeDate, closeHour, closeMinute);
        if (open == null || close == null) {
            showError("Set both dates.");
            return;
        }

        // Checked here for speed; the server checks all of it again, and that is
        // the check that decides.
        String codeProblem = ExecutionCode.describeProblem(codeField.getText());
        if (codeProblem != null) {
            showError(codeProblem);
            codeField.requestFocus();
            return;
        }
        if (!close.isAfter(open)) {
            showError("The closing moment must be after the opening moment.");
            return;
        }

        releaseButton.setDisable(true);
        showMessage("Releasing...");
        send(RequestType.EXECUTION_RELEASE,
             new ExamReleaseRequest(chosen.getExamId(), chosen.getVersion(), open, close,
                                    codeField.getText().trim(),
                                    durationSpinner.getValue(), attemptsSpinner.getValue()),
             REQ_RELEASE);
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
            releaseButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_RELEASABLE -> {
                List<Exam> approved = (List<Exam>) response.getPayload();
                approvedList.setItems(FXCollections.observableArrayList(approved));
                releasableCountLabel.setText(approved.size() + " ready");
                if (approved.isEmpty()) {
                    showMessage(response.getMessage());
                }
            }
            case REQ_MINE -> executionList.setItems(
                    FXCollections.observableArrayList((List<ExamExecution>) response.getPayload()));
            case REQ_CODE -> codeField.setText((String) response.getPayload());
            case REQ_RELEASE -> {
                releaseButton.setDisable(false);
                ExamExecution released = (ExamExecution) response.getPayload();
                showSuccess(response.getMessage());
                reload();
                // A fresh code, so releasing again for another class does not
                // silently reuse the one just handed out.
                send(RequestType.EXECUTION_SUGGEST_CODE, null, REQ_CODE);
                if (released != null) {
                    chosenExamMetaLabel.setText("Last released with code "
                            + released.getExecutionCode() + ".");
                }
            }
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    private void chooseExam(Exam exam) {
        chosen = exam;
        if (exam == null) {
            chosenExamLabel.setText("No exam chosen");
            chosenExamMetaLabel.setText("Pick an approved exam on the left.");
            releaseButton.setDisable(true);
            return;
        }
        chosenExamLabel.setText("Exam " + exam.getExamId() + "   ·   version " + exam.getVersion());
        chosenExamMetaLabel.setText(exam.getCourseName() + "   ·   written by "
                + exam.getAuthorName() + "   ·   " + exam.getDurationMinutes()
                + " minutes as written");
        // Default the allotted time to what the exam itself says, which is what
        // the teacher almost always wants.
        durationSpinner.getValueFactory().setValue(exam.getDurationMinutes());
        releaseButton.setDisable(false);
        clearMessage();
    }

    private LocalDateTime readMoment(DatePicker date, Spinner<Integer> hour, Spinner<Integer> minute) {
        LocalDate day = date.getValue();
        if (day == null) {
            return null;
        }
        return LocalDateTime.of(day, LocalTime.of(hour.getValue(), minute.getValue()));
    }

    /** Past, running, or still to come - at a glance. */
    private String describeState(ExamExecution execution) {
        LocalDateTime now = LocalDateTime.now();
        if (execution.isNotYetOpenAt(now)) {
            return "opens later";
        }
        if (execution.hasClosedAt(now)) {
            return "closed to new starts";
        }
        return "OPEN NOW";
    }

    private static String attemptsText(int attempts) {
        return attempts == 1 ? "1 attempt" : attempts + " attempts";
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
