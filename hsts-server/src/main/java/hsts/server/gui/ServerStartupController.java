package hsts.server.gui;

import hsts.server.HSTSServer;
import hsts.server.ServerApp;
import hsts.server.config.ConfigFile;
import hsts.server.dao.DBController;
import hsts.server.seed.SeedRunner;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.util.Properties;

/**
 * Controller for the server's startup window.
 *
 * <p>Collects the listening port and the database details, connects, prepares
 * the schema, and starts OCSF listening. On success the window is replaced by
 * the console screen.</p>
 */
public class ServerStartupController {

    @FXML private TextField     serverPortField;
    @FXML private TextField     dbHostField;
    @FXML private TextField     dbPortField;
    @FXML private TextField     dbNameField;
    @FXML private TextField     dbUserField;
    @FXML private PasswordField dbPasswordField;
    @FXML private CheckBox      saveSettingsBox;
    @FXML private Button        startButton;
    @FXML private Label         statusLabel;

    /** What the seeder did, carried across to the console screen. */
    private static String seedSummary = "";

    public static String getSeedSummary() {
        return seedSummary;
    }

    /** Called automatically by the FXML loader once the fields are injected. */
    @FXML
    private void initialize() {
        Properties saved = ConfigFile.load();

        serverPortField.setText(ConfigFile.get(saved, ConfigFile.KEY_SERVER_PORT,
                String.valueOf(HSTSServer.DEFAULT_PORT)));
        dbHostField.setText(ConfigFile.get(saved, ConfigFile.KEY_MYSQL_HOST, "localhost"));
        dbPortField.setText(ConfigFile.get(saved, ConfigFile.KEY_MYSQL_PORT, "3306"));
        dbNameField.setText(ConfigFile.get(saved, ConfigFile.KEY_MYSQL_DATABASE, "hsts"));
        dbUserField.setText(ConfigFile.get(saved, ConfigFile.KEY_MYSQL_USER, "root"));
        dbPasswordField.setText(ConfigFile.get(saved, ConfigFile.KEY_MYSQL_PASSWORD, ""));

        if (ConfigFile.exists()) {
            info("Loaded settings from " + ConfigFile.path());
        } else {
            info("No settings file yet - it will be created at " + ConfigFile.path());
        }
    }

    @FXML
    private void onStart() {
        final int serverPort;
        final int dbPort;
        try {
            serverPort = Integer.parseInt(serverPortField.getText().trim());
            dbPort     = Integer.parseInt(dbPortField.getText().trim());
        } catch (NumberFormatException e) {
            error("Ports must be whole numbers.");
            return;
        }
        if (serverPort < 1 || serverPort > 65535 || dbPort < 1 || dbPort > 65535) {
            error("Ports must be between 1 and 65535.");
            return;
        }

        final String host     = dbHostField.getText().trim();
        final String database = dbNameField.getText().trim();
        final String user     = dbUserField.getText().trim();
        final String password = dbPasswordField.getText();

        if (host.isEmpty() || database.isEmpty() || user.isEmpty()) {
            error("Host, database and user cannot be empty.");
            return;
        }

        startButton.setDisable(true);
        info("Connecting to MySQL...");

        // Connecting can block for several seconds if the host is wrong. Doing it
        // on the JavaFX thread would freeze the window and look like a crash, so
        // it runs on a background thread and reports back via Platform.runLater.
        Task<Void> startup = new Task<>() {
            @Override
            protected Void call() throws Exception {
                DBController db = DBController.getInstance();
                db.connect(host, dbPort, database, user, password);

                updateMessage("Creating tables if needed...");
                db.initialiseSchema();

                updateMessage("Checking test data...");
                seedSummary = SeedRunner.seedIfEmpty();

                HSTSServer server = HSTSServer.getInstance();
                server.setPort(serverPort);
                server.listen();
                return null;
            }
        };

        startup.messageProperty().addListener((obs, old, text) -> info(text));

        startup.setOnSucceeded(e -> {
            if (saveSettingsBox.isSelected()) {
                saveSettings(serverPort, host, dbPort, database, user, password);
            }
            openConsole();
        });

        startup.setOnFailed(e -> {
            Throwable cause = startup.getException();
            startButton.setDisable(false);
            error("Could not start: " + describe(cause));
        });

        Thread thread = new Thread(startup, "hsts-server-startup");
        thread.setDaemon(true);
        thread.start();
    }

    /** Turns the usual failures into something a human can act on. */
    private String describe(Throwable cause) {
        if (cause == null) {
            return "unknown error";
        }
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();

        if (message.contains("Access denied")) {
            return "MySQL refused the username or password.";
        }
        if (message.contains("Communications link failure") || message.contains("Connection refused")) {
            return "No MySQL server answered at that host and port. Is the MySQL service running?";
        }
        if (message.contains("Address already in use")) {
            return "Port " + serverPortField.getText().trim()
                 + " is already in use. Another copy of the server may still be running.";
        }
        return message;
    }

    private void saveSettings(int serverPort, String host, int dbPort,
                              String database, String user, String password) {
        try {
            Properties props = new Properties();
            props.setProperty(ConfigFile.KEY_SERVER_PORT,    String.valueOf(serverPort));
            props.setProperty(ConfigFile.KEY_MYSQL_HOST,     host);
            props.setProperty(ConfigFile.KEY_MYSQL_PORT,     String.valueOf(dbPort));
            props.setProperty(ConfigFile.KEY_MYSQL_DATABASE, database);
            props.setProperty(ConfigFile.KEY_MYSQL_USER,     user);
            props.setProperty(ConfigFile.KEY_MYSQL_PASSWORD, password);
            // ConfigFile.save merges, so the Gemini key already in the file survives.
            ConfigFile.save(props);
        } catch (Exception e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }

    private void openConsole() {
        try {
            ServerApp.getPrimaryStage().setScene(ServerApp.loadScene("/fxml/ServerConsole.fxml"));
            ServerApp.getPrimaryStage().setResizable(true);
        } catch (Exception e) {
            error("Server started, but the console screen failed to open: " + e.getMessage());
        }
    }

    private void info(String text) {
        Platform.runLater(() -> {
            statusLabel.setStyle("-fx-text-fill: #444444;");
            statusLabel.setText(text);
        });
    }

    private void error(String text) {
        Platform.runLater(() -> {
            statusLabel.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
            statusLabel.setText(text);
        });
    }
}
