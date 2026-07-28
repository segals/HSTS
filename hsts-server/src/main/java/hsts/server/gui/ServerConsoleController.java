package hsts.server.gui;

import hsts.server.HSTSServer;
import hsts.server.dao.DBController;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

/**
 * Controller for the running-server console.
 *
 * <p>Shows what the server is bound to and a live log of client activity, so
 * that at the demo it is visible that the two laptops really are talking.</p>
 */
public class ServerConsoleController {

    @FXML private Label    detailsLabel;
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
}
