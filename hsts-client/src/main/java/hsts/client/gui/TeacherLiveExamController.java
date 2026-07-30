package hsts.client.gui;

import hsts.common.entity.ExamExecution;
import hsts.common.entity.StudentExam;
import hsts.common.enums.SubmissionStatus;
import hsts.common.protocol.AttemptGrantRequest;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.TimeChangeRequest;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SUC-8 / מתווה scenario 7: the teacher watching a sitting, and changing its time.
 *
 * <p>No Refresh button anywhere. Students starting and handing in arrive as
 * pushes, and when she changes the time every student's countdown moves at once -
 * which is acceptance test 2.7 and the clearest demonstration of NFR 18 in the
 * project.</p>
 */
public class TeacherLiveExamController extends GUIScreen {

    private static final String REQ_RUNNING = "l.running";
    private static final String REQ_GRANT  = "live.grant";
    private static final String REQ_STATUS  = "l.status";
    private static final String REQ_CHANGE  = "l.change";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<ExamExecution> executionList;

    @FXML private Label  chosenLabel;
    @FXML private Label  chosenMetaLabel;
    @FXML private Spinner<Integer> minutesSpinner;
    @FXML private Button addButton;
    @FXML private Button removeButton;
    @FXML private Label  countsLabel;
    @FXML private ListView<StudentExam> studentList;
    @FXML private Button grantAttemptButton;
    @FXML private Label  statusLabel;

    private ExamExecution chosen;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("This screen updates by itself as students start and hand in.");

        minutesSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 180, 15, 5));

        useWrappingCells(executionList, x ->
                "Code " + x.getExecutionCode() + "  ·  exam " + x.getExamId()
              + "\n" + x.getCourseName() + "  ·  " + x.getAllocatedDuration() + " min allowed"
              + (x.isDurationExtended()
                    ? "  (was " + x.getOriginalDuration() + ")" : "")
              + "\nwindow " + CLOCK.format(x.getOpenTime()) + " – "
                            + CLOCK.format(x.getCloseTime()));

        useWrappingCells(studentList, this::describeStudent);

        executionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, execution) -> choose(execution));

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        choose(null);
        loadRunning();
    }

    /**
     * Somebody started or handed in.
     *
     * <p>Overridden so the student list actually reloads. A message over a list
     * still showing the old state would be worse than a Refresh button.</p>
     */
    /**
     * Requirement 61: open one more attempt for the student she has picked.
     *
     * <p>Asks for a reason, because a grant with no note is impossible to account
     * for weeks later - but does not insist on one. Nothing in the requirement
     * demands it, and refusing to help a girl whose machine died because her
     * teacher could not think of wording would be absurd.</p>
     */
    @FXML
    private void onGrantAttempt() {
        StudentExam student = studentList.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            showError("Choose a sitting first.");
            return;
        }
        if (student == null) {
            showError("Choose the student in the list below first.");
            return;
        }

        TextInputDialog ask = new TextInputDialog();
        ask.initOwner(hsts.client.HSTSApp.getPrimaryStage());
        ask.setTitle("Allow another attempt");
        ask.setHeaderText("Let " + student.getStudentName() + " sit this exam again?");
        ask.setContentText("Why (optional):");
        ask.getDialogPane().setMinWidth(430);

        ask.showAndWait().ifPresent(reason ->
                send(RequestType.LIVE_GRANT_ATTEMPT,
                     new AttemptGrantRequest(chosen.getExecutionId(),
                             student.getStudentId(), reason), REQ_GRANT));
    }

    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() == PushType.EXAM_LIVE_STATUS) {
            showMessage(event.getMessage());
            loadRunning();
            if (chosen != null) {
                send(RequestType.LIVE_STATUS, chosen.getExecutionId(), REQ_STATUS);
            }
        } else {
            super.onPush(event);
        }
    }

    private void loadRunning() {
        send(RequestType.LIVE_RUNNING_NOW, null, REQ_RUNNING);
    }

    // -----------------------------------------------------------------
    //  Changing the time
    // -----------------------------------------------------------------

    @FXML
    private void onAddTime() {
        changeTime(minutesSpinner.getValue());
    }

    @FXML
    private void onRemoveTime() {
        changeTime(-minutesSpinner.getValue());
    }

    private void changeTime(int delta) {
        if (chosen == null) {
            showError("Choose a sitting from the list first.");
            return;
        }
        addButton.setDisable(true);
        removeButton.setDisable(true);
        showMessage(delta > 0 ? "Adding " + delta + " minutes..."
                              : "Taking " + (-delta) + " minutes away...");
        send(RequestType.LIVE_CHANGE_TIME,
             new TimeChangeRequest(chosen.getExecutionId(), delta), REQ_CHANGE);
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
            addButton.setDisable(false);
            removeButton.setDisable(false);
            showError(response.getMessage());
            return;
        }

        switch (id) {
            case REQ_RUNNING -> {
                List<ExamExecution> running = (List<ExamExecution>) response.getPayload();
                int keepId = (chosen == null) ? -1 : chosen.getExecutionId();
                executionList.setItems(FXCollections.observableArrayList(running));
                // Keep her selection across a reload; losing it every time a
                // student pressed a button would make the screen unusable.
                for (ExamExecution execution : running) {
                    if (execution.getExecutionId() == keepId) {
                        executionList.getSelectionModel().select(execution);
                        chosen = execution;
                        showChosenDetails();
                        break;
                    }
                }
                if (running.isEmpty()) {
                    choose(null);
                    showMessage(response.getMessage());
                }
            }
            case REQ_GRANT -> {
                showSuccess(response.getMessage());
                if (chosen != null) {
                    send(RequestType.LIVE_STATUS, chosen.getExecutionId(), REQ_STATUS);
                }
            }
            case REQ_STATUS -> {
                List<StudentExam> students = (List<StudentExam>) response.getPayload();
                studentList.setItems(FXCollections.observableArrayList(students));
                updateCounts(students);
            }
            case REQ_CHANGE -> {
                addButton.setDisable(false);
                removeButton.setDisable(false);
                chosen = (ExamExecution) response.getPayload();
                showSuccess(response.getMessage());
                showChosenDetails();
                loadRunning();
                send(RequestType.LIVE_STATUS, chosen.getExecutionId(), REQ_STATUS);
            }
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    private void choose(ExamExecution execution) {
        chosen = execution;
        boolean none = execution == null;
        addButton.setDisable(none);
        removeButton.setDisable(none);
        studentList.setItems(FXCollections.observableArrayList());
        countsLabel.setText("");

        if (none) {
            chosenLabel.setText("No sitting chosen");
            chosenMetaLabel.setText("Pick one on the left to watch it.");
            return;
        }
        showChosenDetails();
        send(RequestType.LIVE_STATUS, execution.getExecutionId(), REQ_STATUS);
    }

    private void showChosenDetails() {
        if (chosen == null) {
            return;
        }
        chosenLabel.setText("Code " + chosen.getExecutionCode()
                          + "   ·   exam " + chosen.getExamId()
                          + " v" + chosen.getExamVersion());
        chosenMetaLabel.setText(chosen.getCourseName()
                + "   ·   " + chosen.getAllocatedDuration() + " minutes allowed"
                + (chosen.isDurationExtended()
                    ? "   ·   changed from " + chosen.getOriginalDuration() : "")
                + "   ·   window " + CLOCK.format(chosen.getOpenTime())
                + " – " + CLOCK.format(chosen.getCloseTime()));
    }

    /** One student's line: where she is, and how long she has left. */
    private String describeStudent(StudentExam attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append(attempt.getStudentName())
          .append("  ·  attempt ").append(attempt.getAttemptNo())
          .append("\n").append(attempt.getStatus().getDisplayName());

        if (attempt.isInProgress()) {
            long seconds = attempt.secondsRemainingAt(LocalDateTime.now());
            sb.append("  ·  ").append(seconds / 60).append(" min left")
              .append("  ·  started ").append(CLOCK.format(attempt.getStartTime()));
        } else if (attempt.getActualDuration() != null) {
            sb.append("  ·  took ").append(attempt.getActualDuration()).append(" min");
        }
        return sb.toString();
    }

    /** Requirement 48: started, finished by themselves, ran out of time. */
    private void updateCounts(List<StudentExam> students) {
        int started = students.size();
        int finished = 0;
        int timedOut = 0;
        int inside = 0;
        for (StudentExam attempt : students) {
            if (attempt.getStatus() == SubmissionStatus.FINISHED) {
                finished++;
            } else if (attempt.getStatus() == SubmissionStatus.TIMED_OUT) {
                timedOut++;
            } else {
                inside++;
            }
        }
        countsLabel.setText(started + " started  ·  " + inside + " still working  ·  "
                          + finished + " finished  ·  " + timedOut + " ran out of time");
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }

    /** Unused here, but keeps the import honest if the layout changes. */
    @SuppressWarnings("unused")
    private static long minutes(Duration d) {
        return d.toMinutes();
    }
}
