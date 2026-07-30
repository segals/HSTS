package hsts.client.gui;

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
public class LoginScreenController extends GUIScreen {

    private static final String REQ_LOGIN = "login";

    @FXML private Label         serverLabel;
    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button        loginButton;
    @FXML private Label         statusLabel;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);

        serverLabel.setText("Connected to " + controller.describeConnection());
        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(reason -> {
            loginButton.setDisable(true);
            showError(reason + " Restart the client to reconnect.");
        });

        clearMessage();
        usernameField.requestFocus();
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Enter both a username and a password.");
            return;
        }

        loginButton.setDisable(true);
        showMessage("Checking...");

        try {
            controller.send(new Request(RequestType.LOGIN,
                    new Credentials(username, password), REQ_LOGIN));
        } catch (Exception e) {
            loginButton.setDisable(false);
            showError("Could not reach the server: " + e.getMessage());
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
            showError(response.getMessage());
            return;
        }

        if (!(response.getPayload() instanceof User user)) {
            showError("The server accepted the login but sent no user details.");
            return;
        }

        controller.setCurrentUser(user);
        switchTo("/fxml/MainMenu.fxml");
    }
}
