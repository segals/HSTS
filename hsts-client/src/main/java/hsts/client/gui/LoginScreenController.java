package hsts.client.gui;

import hsts.client.HSTSApp;
import hsts.client.net.ClientController;
import hsts.common.entity.User;
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
 * SUC-1: the login screen.
 *
 * <p>The screen collects a username and a password and sends them. It decides
 * nothing - whether the credentials are good, and whether that user is already
 * logged in elsewhere, are both answered by the server. A client is only a
 * program on someone else's computer, and the server never trusts it.</p>
 */
public class LoginScreenController {

    private static final String REQ_LOGIN = "login";

    @FXML private Label         serverLabel;
    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button        loginButton;
    @FXML private Label         statusLabel;

    private final ClientController client = ClientController.getInstance();

    @FXML
    private void initialize() {
        serverLabel.setText("Connected to " + client.describeConnection());
        client.setResponseHandler(this::onServerResponse);
        client.setConnectionLostHandler(reason -> {
            loginButton.setDisable(true);
            error(reason + " Restart the client to reconnect.");
        });
        usernameField.requestFocus();
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            error("Enter both a username and a password.");
            return;
        }

        loginButton.setDisable(true);
        info("Checking...");

        try {
            client.send(new Request(RequestType.LOGIN,
                    new Credentials(username, password), REQ_LOGIN));
        } catch (Exception e) {
            loginButton.setDisable(false);
            error("Could not reach the server: " + e.getMessage());
        }
    }

    /** Already moved onto the JavaFX thread by ClientController. */
    private void onServerResponse(Response response) {
        if (!REQ_LOGIN.equals(response.getRequestId())) {
            return;
        }

        loginButton.setDisable(false);

        if (!response.isOk()) {
            passwordField.clear();
            error(response.getMessage());
            return;
        }

        if (!(response.getPayload() instanceof User user)) {
            error("The server accepted the login but sent no user details.");
            return;
        }

        client.setCurrentUser(user);
        try {
            HSTSApp.getPrimaryStage().setScene(HSTSApp.loadScene("/fxml/MainMenu.fxml"));
            HSTSApp.getPrimaryStage().setResizable(true);
        } catch (Exception e) {
            error("Signed in, but the menu failed to open: " + e.getMessage());
        }
    }

    private void info(String text) {
        statusLabel.setStyle("-fx-text-fill: #444444;");
        statusLabel.setText(text);
    }

    private void error(String text) {
        statusLabel.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
        statusLabel.setText(text);
    }
}
