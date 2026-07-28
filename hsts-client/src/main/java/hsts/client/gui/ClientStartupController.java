package hsts.client.gui;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/**
 * Controller for the client's startup window: asks where the server is, and
 * connects to it.
 *
 * <p>Required by מתווה item 15 - the connection is established through a GUI, so
 * the same jar can be pointed at any server on the network without rebuilding.</p>
 */
public class ClientStartupController extends GUIScreen {

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private Button    connectButton;
    @FXML private Label     statusLabel;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);

        // Sensible defaults for testing both jars on one laptop. On the two-laptop
        // setup the address is the server laptop's LAN address.
        hostField.setText("localhost");
        portField.setText("5555");

        showMessage("Enter the address of the machine running G1_Server.jar.");
    }

    @FXML
    private void onConnect() {
        final String host = hostField.getText().trim();
        final int port;

        if (host.isEmpty()) {
            showError("Enter the server address.");
            return;
        }
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            showError("The port must be a whole number.");
            return;
        }
        if (port < 1 || port > 65535) {
            showError("The port must be between 1 and 65535.");
            return;
        }

        connectButton.setDisable(true);
        showMessage("Connecting to " + host + ":" + port + " ...");

        // Opening a socket to an unreachable host blocks until it times out.
        // On the JavaFX thread that would freeze the window, so it runs on a
        // background thread.
        Task<Void> connect = new Task<>() {
            @Override
            protected Void call() throws Exception {
                controller.connect(host, port);
                return null;
            }
        };

        connect.setOnSucceeded(e -> switchTo("/fxml/Login.fxml", false));

        connect.setOnFailed(e -> {
            connectButton.setDisable(false);
            showError(describe(connect.getException(), host, port));
        });

        Thread thread = new Thread(connect, "hsts-client-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Turns the usual socket failures into advice rather than a stack trace. */
    private String describe(Throwable cause, String host, int port) {
        if (cause == null) {
            return "Could not connect.";
        }
        String name = cause.getClass().getSimpleName();

        if (name.equals("ConnectException")) {
            return "Nothing is listening on " + host + ":" + port
                 + ". Check that the server is running, and that Windows Firewall "
                 + "allows inbound TCP on that port on the server machine.";
        }
        if (name.equals("UnknownHostException")) {
            return "The address \"" + host + "\" could not be found.";
        }
        if (name.equals("SocketTimeoutException") || name.equals("NoRouteToHostException")) {
            return "No answer from " + host + ". Are both machines on the same network?";
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
