package hsts.client.gui;

import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.TodoItem;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * What one teacher or coordinator still has to do.
 *
 * <p>Each line says how many, what they are, and opens the screen they are on -
 * a list that made her go and find the marking screen herself would have told her
 * something she mostly knew.</p>
 *
 * <p>It re-asks itself whenever a push says something might have changed, for the
 * same reason the menu badges do: a list that is only right when you open it is a
 * list you have to keep reopening.</p>
 */
public class TodoController extends GUIScreen {

    private static final String REQ_TODO = "todo.list";

    @FXML private Label subtitleLabel;
    @FXML private Button backButton;
    @FXML private VBox  mineBox;
    @FXML private VBox  mineRows;
    @FXML private VBox  othersBox;
    @FXML private VBox  othersRows;
    @FXML private Label emptyLabel;
    @FXML private Label statusLabel;

    /** The same events the menu badges listen for - they move the same numbers. */
    private static final java.util.Set<PushType> AFFECTS_TODO = java.util.EnumSet.of(
            PushType.EXAM_AWAITING_APPROVAL,
            PushType.EXAM_APPROVED,
            PushType.EXAM_REJECTED,
            PushType.GRADE_APPROVED,
            PushType.RESULTS_CHANGED,
            PushType.EXAM_LIVE_STATUS,
            PushType.PENDING_COUNTS_CHANGED);

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Everything of yours that is not finished. "
                            + "This updates by itself.");

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        reload();
    }

    @Override
    protected void onPush(PushEvent event) {
        super.onPush(event);
        if (AFFECTS_TODO.contains(event.getType())) {
            reload();
        }
    }

    private void reload() {
        send(RequestType.MY_TODO, null, REQ_TODO);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        if (!REQ_TODO.equals(response.getRequestId())) {
            return;
        }
        if (!response.isOk()) {
            showError(response.getMessage());
            return;
        }
        show((List<TodoItem>) response.getPayload(), response.getMessage());
    }

    private void show(List<TodoItem> items, String message) {
        mineRows.getChildren().clear();
        othersRows.getChildren().clear();

        for (TodoItem item : items) {
            (item.isMine() ? mineRows : othersRows).getChildren().add(row(item));
        }

        boolean anyMine = !mineRows.getChildren().isEmpty();
        boolean anyOther = !othersRows.getChildren().isEmpty();

        mineBox.setVisible(anyMine);
        mineBox.setManaged(anyMine);
        othersBox.setVisible(anyOther);
        othersBox.setManaged(anyOther);

        // Nothing at all is a real answer and deserves saying, rather than an empty
        // screen that looks like something failed to load.
        emptyLabel.setVisible(!anyMine && !anyOther);
        emptyLabel.setManaged(!anyMine && !anyOther);
        emptyLabel.setText("Nothing is waiting for you. Every exam of yours is approved, "
                         + "released and marked.");

        showMessage(message);
        fitToContent();
    }

    /** One line: the count, what it is, and a way to go and do it. */
    private HBox row(TodoItem item) {
        Label count = new Label(String.valueOf(item.getCount()));
        count.getStyleClass().add("todo-count");
        count.setMinWidth(Region.USE_PREF_SIZE);

        Label title = new Label(item.getTitle());
        title.getStyleClass().add("h3");
        title.setWrapText(true);

        Label detail = new Label(item.getDetail());
        detail.getStyleClass().add("caption");
        detail.setWrapText(true);

        VBox words = new VBox(2, title, detail);
        HBox.setHgrow(words, Priority.ALWAYS);
        words.setMaxWidth(Double.MAX_VALUE);

        HBox line = new HBox(12, count, words);
        line.setAlignment(Pos.CENTER_LEFT);

        if (item.getScreen() != null) {
            Button open = new Button(item.isMine() ? "Go there" : "Look");
            open.setMinWidth(Region.USE_PREF_SIZE);
            open.setOnAction(e -> switchTo(item.getScreen()));
            line.getChildren().add(open);
        }
        return line;
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
