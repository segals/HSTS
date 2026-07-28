package hsts.client.gui;

import hsts.client.HSTSApp;
import hsts.client.net.ClientController;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/**
 * The common base for every screen, from the submitted class diagram.
 *
 * <p>It gives all screens one way to reach the server ({@link #controller}) and
 * one way to talk to the user ({@link #showMessage} / {@link #showError}), so
 * that messages look and behave the same everywhere. NFR 21 asks for a
 * consistent interface with clear success and error reporting; doing it in one
 * place is how that stays true as screens are added.</p>
 *
 * <h2>Why {@link #fitToContent()} exists</h2>
 *
 * <p>A window is sized once, when it is first shown, to fit the text it had at
 * that moment. Status labels wrap, so a longer message becomes two or three
 * lines - but the window does not grow to match, and the extra lines are simply
 * cut off at the bottom edge.</p>
 *
 * <p>That was found the honest way: logging in twice as the same user produced
 * <i>"This user is already logged in on another computer. Log out the..."</i> and
 * the rest was invisible. An error message the user cannot finish reading is
 * worse than no error message, because it looks like the program is broken.</p>
 *
 * <p>So every message re-measures the layout and resizes the window to fit.</p>
 */
public abstract class GUIScreen {

    /** The route to the server. Screens never touch a socket themselves. */
    protected final ClientController controller = ClientController.getInstance();

    /** Where messages are shown. Each screen hands its own label over on startup. */
    private Label statusLabel;

    /**
     * Registers this screen's status label. Call it from {@code initialize()}.
     *
     * <p>The label must have {@code wrapText="true"} in the FXML, otherwise a long
     * message runs off the side instead of wrapping.</p>
     */
    protected void bindStatusLabel(Label label) {
        this.statusLabel = label;
    }

    /** Shows a normal, informational message. */
    public void showMessage(String text) {
        setStatus(text, "-fx-text-fill: #444444;");
    }

    /** Shows a failure. Same mechanism, different colour, so nothing is missed. */
    public void showError(String text) {
        setStatus(text, "-fx-text-fill: #b00020; -fx-font-weight: bold;");
    }

    /** Clears the message area without collapsing the layout. */
    public void clearMessage() {
        setStatus(" ", "-fx-text-fill: #444444;");
    }

    private void setStatus(String text, String style) {
        if (statusLabel == null) {
            System.out.println("[no status label bound] " + text);
            return;
        }
        // Callers may be on a background thread; JavaFX allows only its own.
        if (Platform.isFxApplicationThread()) {
            applyStatus(text, style);
        } else {
            Platform.runLater(() -> applyStatus(text, style));
        }
    }

    private void applyStatus(String text, String style) {
        statusLabel.setStyle(style);
        statusLabel.setText(text == null ? " " : text);
        fitToContent();
    }

    /**
     * Re-measures the window so all of its content is visible.
     *
     * <p>The three steps matter and are easy to get wrong:</p>
     * <ol>
     *   <li>{@code applyCss()} - styles affect font size, and font size affects
     *       how text wraps.</li>
     *   <li>{@code layout()} - forces the wrapping to be recalculated <em>now</em>
     *       rather than at the next frame. Without it, {@code sizeToScene} would
     *       measure the previous message and be one step behind.</li>
     *   <li>{@code sizeToScene()} - grows or shrinks the window to the result.</li>
     * </ol>
     */
    protected void fitToContent() {
        Platform.runLater(() -> {
            Stage stage = HSTSApp.getPrimaryStage();
            if (stage == null || stage.getScene() == null) {
                return;
            }
            Parent root = stage.getScene().getRoot();
            root.applyCss();
            root.layout();
            stage.sizeToScene();
        });
    }

    /**
     * Replaces the window's contents with another screen.
     *
     * @param fxmlPath absolute classpath path, e.g. {@code "/fxml/MainMenu.fxml"}
     */
    protected void switchTo(String fxmlPath, boolean resizable) {
        try {
            Stage stage = HSTSApp.getPrimaryStage();
            stage.setScene(HSTSApp.loadScene(fxmlPath));
            stage.setResizable(resizable);
            stage.sizeToScene();
        } catch (Exception e) {
            showError("That screen failed to open: " + e.getMessage());
        }
    }
}
