package hsts.server.gui;

import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import hsts.server.seed.SeedRunner;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.util.Optional;

/**
 * Controller for the running-server console.
 *
 * <p>Shows what the server is bound to and a live log of client activity, so
 * that at the demo it is visible that the two laptops really are talking.</p>
 */
public class ServerConsoleController {

    @FXML private Label    detailsLabel;
    @FXML private Button   resetDataButton;
    @FXML private TextArea logArea;

    @FXML
    private void initialize() {
        HSTSServer server = HSTSServer.getInstance();
        DBController db   = DBController.getInstance();

        String mysqlVersion;
        try {
            mysqlVersion = db.getServerVersion();
        } catch (Exception e) {
            mysqlVersion = "unknown";
        }

        detailsLabel.setText(
                "Listening on port " + server.getPort()
              + "\nDatabase: " + db.getDescribedUrl()
              + "\nMySQL version: " + mysqlVersion
              + "\n" + ServerStartupController.getSeedSummary());

        // From here on, every server log line lands in this text area.
        //
        // Log lines arrive on OCSF's networking threads, and JavaFX only allows
        // its own thread to touch a control. Platform.runLater hands the update
        // over to the JavaFX thread. Without it this works most of the time and
        // then crashes at random - which is exactly the kind of bug that shows
        // up during a demo and nowhere else.
        server.setLogSink(line -> Platform.runLater(() -> {
            logArea.appendText(line + "\n");
        }));

        append("Server console ready.");
        append("Waiting for clients...");
    }

    private void append(String line) {
        logArea.appendText(line + "\n");
    }

    /**
     * Wipes the database and seeds it again.
     *
     * <p>Useful twice: after a run of the automated tests, which leave debris in
     * the question bank, and shortly before the demo, because the seeder computes
     * every date relative to when it runs - so re-seeding puts the "open right
     * now" exam back into the present.</p>
     *
     * <p>It destroys data, so it asks first and says plainly what will go. The
     * confirmation defaults to No.</p>
     */
    @FXML
    private void onResetData() {
        Alert confirm = new Alert(Alert.AlertType.WARNING,
                "This deletes EVERY row in the database - questions, exams, results, "
              + "bot history - and seeds the test data again from scratch.\n\n"
              + "Any client currently signed in should log out first.\n\n"
              + "Continue?",
                ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Reset all test data");
        confirm.getDialogPane().setPrefWidth(460);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.YES) {
            append("Reset cancelled.");
            return;
        }

        resetDataButton.setDisable(true);
        append("Resetting test data...");

        // On a background thread: truncating and re-seeding takes a moment, and
        // doing it on the JavaFX thread would freeze the window.
        Task<String> reset = new Task<>() {
            @Override
            protected String call() throws Exception {
                return SeedRunner.resetAndSeed();
            }
        };
        reset.setOnSucceeded(e -> {
            append(reset.getValue());
            resetDataButton.setDisable(false);
        });
        reset.setOnFailed(e -> {
            Throwable cause = reset.getException();
            append("Reset FAILED: " + (cause == null ? "unknown" : cause.getMessage()));
            resetDataButton.setDisable(false);
        });

        Thread thread = new Thread(reset, "hsts-reset-data");
        thread.setDaemon(true);
        thread.start();
    }
}
