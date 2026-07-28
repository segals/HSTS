package hsts.client.gui;

import hsts.client.net.ClientController;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/**
 * MILESTONE 1 ONLY - scaffolding, deleted in milestone 2.
 *
 * <p>Proves the whole chain works before any feature is written: the client
 * reaches the server over TCP/IP, the server reaches MySQL, the answer comes
 * back, and a salted-hash login check runs against a real row.</p>
 */
public class SkeletonCheckController {

    /** Correlation ids, so a reply can be matched to the button that caused it. */
    private static final String REQ_PING  = "ping";
    private static final String REQ_LOGIN = "login";

    @FXML private Label         connectionLabel;
    @FXML private Button        pingButton;
    @FXML private Label         pingResultLabel;
    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button        loginButton;
    @FXML private Label         loginResultLabel;

    private final ClientController client = ClientController.getInstance();

    @FXML
    private void initialize() {
        connectionLabel.setText("Connected to " + client.describeConnection());

        usernameField.setText("teacher1");

        // Register as the observer of server replies. ClientController has
        // already moved them onto the JavaFX thread, so this code can touch
        // controls directly.
        client.setResponseHandler(this::onServerResponse);
        client.setConnectionLostHandler(reason -> {
            connectionLabel.setText(reason);
            connectionLabel.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
            pingButton.setDisable(true);
            loginButton.setDisable(true);
        });
    }

    @FXML
    private void onPing() {
        pingResultLabel.setText("waiting for the server...");
        send(new Request(RequestType.PING, null, REQ_PING), pingResultLabel);
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            fail(loginResultLabel, "Enter both a username and a password.");
            return;
        }

        loginResultLabel.setText("checking...");
        send(new Request(RequestType.LOGIN, new Credentials(username, password), REQ_LOGIN),
             loginResultLabel);
    }

    private void send(Request request, Label target) {
        try {
            client.send(request);
        } catch (Exception e) {
            fail(target, "Could not send: " + e.getMessage());
        }
    }

    /** Called for every reply, already on the JavaFX thread. */
    private void onServerResponse(Response response) {
        Label target = REQ_LOGIN.equals(response.getRequestId())
                     ? loginResultLabel
                     : pingResultLabel;

        if (response.isOk()) {
            String detail = response.getPayload() == null ? "" : String.valueOf(response.getPayload());
            succeed(target, response.getMessage() + (detail.isEmpty() ? "" : "\n" + detail));
        } else {
            fail(target, response.getMessage());
        }
    }

    private void succeed(Label label, String text) {
        label.setStyle("-fx-text-fill: #1b5e20;");
        label.setText("OK  -  " + text);
    }

    private void fail(Label label, String text) {
        label.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
        label.setText("FAILED  -  " + text);
    }
}
