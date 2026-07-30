package hsts.client.gui;

import hsts.client.HSTSApp;
import hsts.client.net.ClientController;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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

        // Every screen becomes the current push listener as it opens. Doing it
        // here rather than in each screen means no screen can forget - and a
        // screen that forgot would silently swallow the announcements NFR 18
        // exists to deliver.
        controller.setPushHandler(this::onPush);
    }

    /**
     * Something happened on the server that this user should know about.
     *
     * <p>The default is to show the message. A screen that displays data the
     * event affects should override this, call {@code super.onPush(event)} and
     * then reload - that is what "no manual refresh" means in practice.</p>
     */
    protected void onPush(PushEvent event) {
        if (event.getType() == PushType.SESSION_TIMED_OUT) {
            // Requirement 76. Handled here rather than on each screen, because it
            // can arrive on ANY of them - and every screen must react the same way.
            // The server closes the connection a moment later, so this has to be
            // shown and acted on now, not after a round trip.
            signedOutForIdleness(event.getMessage());
            return;
        }
        if (event.getType() == PushType.EXAM_REJECTED) {
            showError(event.getMessage());
        } else {
            showSuccess(event.getMessage());
        }
    }

    /**
     * Back to the login screen, saying why (requirement 76).
     *
     * <p>Told plainly, because the alternative is the connection simply dropping
     * and her concluding the network is broken. The dialog comes first so the
     * message survives the screen change.</p>
     */
    private void signedOutForIdleness(String why) {
        javafx.application.Platform.runLater(() -> {
            javafx.scene.control.Alert told =
                    new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.INFORMATION);
            told.initOwner(hsts.client.HSTSApp.getPrimaryStage());
            told.setTitle("Signed out");
            told.setHeaderText("You have been signed out");
            told.setContentText(why == null
                    ? "You were signed out after a period without activity."
                    : why);
            prepareDialog(told, 420);
            told.showAndWait();
            switchTo("/fxml/Login.fxml");
        });
    }

    /** Shows a normal, informational message. */
    public void showMessage(String text) {
        setStatus(text, "status-info");
    }

    /** Shows something that went well, so success is as visible as failure. */
    public void showSuccess(String text) {
        setStatus(text, "status-success");
    }

    /** Shows a failure. Same mechanism, different colour, so nothing is missed. */
    public void showError(String text) {
        setStatus(text, "status-error");
    }

    /** Clears the message area without collapsing the layout. */
    public void clearMessage() {
        setStatus(" ", "status-info");
    }

    /**
     * Applies one of the three status styles.
     *
     * <p>The look lives in {@code hsts.css}, not here. Setting colours inline
     * would mean every screen carried its own idea of what an error looks like,
     * and they would drift apart as screens were added.</p>
     */
    private void setStatus(String text, String styleClass) {
        if (statusLabel == null) {
            System.out.println("[no status label bound] " + text);
            return;
        }
        // Callers may be on a background thread; JavaFX allows only its own.
        if (Platform.isFxApplicationThread()) {
            applyStatus(text, styleClass);
        } else {
            Platform.runLater(() -> applyStatus(text, styleClass));
        }
    }

    private void applyStatus(String text, String styleClass) {
        statusLabel.getStyleClass().removeAll("status-info", "status-error", "status-success");
        if (!statusLabel.getStyleClass().contains("status")) {
            statusLabel.getStyleClass().add("status");
        }
        statusLabel.getStyleClass().add(styleClass);
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
            fitToScreen(stage);
        });
    }

    /**
     * Keeps a window inside the display it is on.
     *
     * <p>The screens ask for the width they need - a marking screen with three
     * columns wants 1280 - and on a laptop running at 125% or 150% scaling the
     * desktop is only 1536 or 1280 points wide. {@code sizeToScene} happily makes a
     * window taller than the screen, and the bottom of it, buttons and all, is
     * simply not there. Reported from the screen: "the choose question window isn't
     * fully visible".</p>
     *
     * <p>So the window is clamped to the working area - which excludes the taskbar -
     * and moved back into view. It stays resizable, and every screen keeps a scroll
     * pane over the part that can grow, so clamping hides nothing.</p>
     */
    protected static void fitToScreen(Stage stage) {
        javafx.geometry.Rectangle2D area =
                javafx.stage.Screen.getPrimary().getVisualBounds();

        if (stage.getWidth() > area.getWidth()) {
            stage.setWidth(area.getWidth());
        }
        if (stage.getHeight() > area.getHeight()) {
            stage.setHeight(area.getHeight());
        }
        // A window that was clamped is usually now half off the edge it grew past.
        if (stage.getX() < area.getMinX() || stage.getY() < area.getMinY()
                || stage.getX() + stage.getWidth() > area.getMaxX()
                || stage.getY() + stage.getHeight() > area.getMaxY()) {
            stage.setX(area.getMinX() + (area.getWidth() - stage.getWidth()) / 2);
            stage.setY(area.getMinY() + (area.getHeight() - stage.getHeight()) / 2);
        }
    }

    /**
     * Prepares a dialog so its text is never cut off.
     *
     * <p>A JavaFX {@code Alert} sizes itself to its content and then refuses to grow,
     * so a long sentence is clipped rather than wrapped. Making it resizable and
     * giving the label a width to wrap into fixes both, and every dialog in the
     * system goes through here so none of them can be forgotten.</p>
     */
    protected static void prepareDialog(javafx.scene.control.Dialog<?> dialog, double minWidth) {
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(minWidth);
        pane.setPrefWidth(minWidth);
        dialog.setResizable(true);

        // The content and header labels do not wrap by default inside a dialog.
        pane.lookupAll(".label").forEach(node -> {
            if (node instanceof Label label) {
                label.setWrapText(true);
            }
        });
        pane.getScene().getWindow().sizeToScene();
    }

    /**
     * Makes a list show its full text, wrapped over as many lines as needed.
     *
     * <p>A JavaFX {@code ListView} does not wrap. Anything wider than the control
     * is simply cut off, and widening the window does not help once the layout has
     * stopped growing - so a list of questions shows
     * "00101 (v1) What is the sum of the angles in" and the reader is left
     * guessing which question that is. In a screen whose whole purpose is
     * <em>choosing</em> from a list, that is not a cosmetic problem.</p>
     *
     * <p>The label's width is bound to the list's, minus room for the scroll bar,
     * so the text re-wraps as the window is resized instead of being clipped.</p>
     *
     * @param toText how to turn one item into the text to display
     */
    protected static <T> void useWrappingCells(ListView<T> list,
                                               java.util.function.Function<T, String> toText) {
        list.setCellFactory(view -> new ListCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                // 28px leaves room for the scroll bar and the cell's own padding.
                label.maxWidthProperty().bind(view.widthProperty().subtract(28));
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    label.setText(toText.apply(item));
                    setGraphic(label);
                    setText(null);
                }
            }
        });
    }

    /**
     * Replaces the window's contents with another screen.
     *
     * <p>Every screen is resizable and sized to what it holds, then clamped to the
     * display. There used to be a flag for this and two screens passed
     * {@code false}; a window a user cannot resize is a window she cannot read on a
     * smaller laptop, and there was no reason for the exception.</p>
     *
     * @param fxmlPath absolute classpath path, e.g. {@code "/fxml/MainMenu.fxml"}
     */
    protected void switchTo(String fxmlPath) {
        try {
            Stage stage = HSTSApp.getPrimaryStage();
            stage.setScene(HSTSApp.loadScene(fxmlPath));
            stage.setResizable(true);
            stage.sizeToScene();
            fitToScreen(stage);
        } catch (Exception | Error e) {
            // FXML failures wrap the real cause several layers deep, and the
            // outer LoadException usually has an empty message - which produced
            // the useless "That screen failed to open:" with nothing after it.
            // Dig out the root cause and name the file, and print the whole
            // trace to the console for anyone who needs more.
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.err.println("Failed to open " + fxmlPath);
            e.printStackTrace();

            String detail = (root.getMessage() == null || root.getMessage().isBlank())
                    ? root.getClass().getSimpleName()
                    : root.getClass().getSimpleName() + " - " + root.getMessage();
            showError("Could not open " + fxmlPath + "\n" + detail);
        }
    }
}
