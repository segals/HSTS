package hsts.client.gui;

import hsts.common.entity.Bot;
import hsts.common.entity.BotConversation;
import hsts.common.entity.Course;
import hsts.common.entity.KnowledgeSource;
import hsts.common.enums.BotStatus;
import hsts.common.enums.KnowledgeSourceType;
import hsts.common.protocol.BotCreateRequest;
import hsts.common.protocol.BotStatusRequest;
import hsts.common.protocol.BotUsage;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.SourceRequest;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SUC-13 and the teacher's half of SUC-15: building and watching a course bot.
 *
 * <p>She creates one for a course she teaches (requirement 65), gives it the
 * question bank, a document or typed text (66, 68), turns it on and off (60), and
 * sees how it is being used with <b>no student names</b> (75).</p>
 *
 * <p>Requirement 67 needs nothing special here: the server lets any teacher of the
 * course add material, so a colleague simply opens the same bot and adds to it. The
 * material list names who added each piece, which is how that becomes visible.</p>
 *
 * <h2>Several bots per course, one of them on</h2>
 *
 * <p>A course may have more than one bot - a general one and a revision one, say -
 * but only one may be switched on, so requirement 70's "the course bot" stays
 * unambiguous for a student. Switching one on switches the course's others off, and
 * the server says which.</p>
 *
 * <p>The bot list is keyed on the <b>course</b> rather than the bot name, because a
 * teacher of two courses sees both here and needs them to group at a glance.</p>
 *
 * <h2>Deleting</h2>
 *
 * <p>Deleting destroys the bot's stored questions and answers, which requirement 73
 * says are kept. So the confirmation names how many will go: a dialog that asked
 * only "are you sure?" would hide the part that matters.</p>
 */
public class BotManagementController extends GUIScreen {

    private static final String REQ_BOTS    = "bm.bots";
    private static final String REQ_COURSES = "bm.courses";
    private static final String REQ_CREATE  = "bm.create";
    private static final String REQ_STATUS  = "bm.status";
    private static final String REQ_ADD     = "bm.add";
    private static final String REQ_REMOVE  = "bm.remove";
    private static final String REQ_USAGE   = "bm.usage";
    private static final String REQ_IMPACT  = "bm.impact";
    private static final String REQ_DELETE  = "bm.delete";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    /** Refused before upload: a whole textbook is not knowledge, it is a timeout. */
    private static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<Bot> botList;
    @FXML private Button onButton;
    @FXML private Button offButton;
    @FXML private ComboBox<Course> courseBox;
    @FXML private TextField botNameField;
    @FXML private Button deleteButton;
    @FXML private Button createButton;

    @FXML private Label   botTitleLabel;
    @FXML private Label   botMetaLabel;
    @FXML private TabPane tabs;
    @FXML private ListView<KnowledgeSource> sourceList;
    @FXML private Button  removeSourceButton;
    @FXML private Button  addBankButton;
    @FXML private Button  addWordButton;
    @FXML private Button  addPdfButton;
    @FXML private TextField textTitleField;
    @FXML private TextArea  textArea;
    @FXML private Button  addTextButton;
    @FXML private Label   uploadNoteLabel;

    @FXML private HBox usageStatsRow;
    @FXML private ListView<BotUsage.CommonQuestion> commonList;
    @FXML private ListView<BotConversation> recentList;
    @FXML private VBox  fullExchangeBox;
    @FXML private Label exchangeWhenLabel;
    @FXML private Label exchangeQuestionLabel;
    @FXML private Label exchangeAnswerLabel;

    @FXML private Label statusLabel;

    private Bot chosenBot;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Every course you teach. A course may have several bots, "
                            + "but only one switched on at a time.");
        uploadNoteLabel.setText("Word (.docx) is read exactly. A PDF is read only if its "
                              + "text is not compressed - if it is refused, paste the text "
                              + "in above instead.");

        // The course leads, because a teacher of two courses sees both here and the
        // bots need to group by course at a glance. The switched-on one says so
        // plainly, since only one per course can be.
        useWrappingCells(botList, b ->
                b.getCourseName() + "   ·   " + (b.isActive() ? "ON" : "off")
              + "\n" + b.getName()
              + "\n" + b.getSources().size()
              + (b.getSources().size() == 1 ? " source" : " sources"));

        useWrappingCells(sourceList, s ->
                s.getTitle() + "  ·  " + s.getType().getDisplayName()
              + "  ·  " + s.getLength() + " characters"
              + "\nadded by " + s.getAddedByName() + " on " + s.getAddedAt().format(WHEN)
              + "\n" + s.getPreview(160));

        useWrappingCells(commonList, q ->
                q.getTimesAsked() + (q.getTimesAsked() == 1 ? " time" : " times")
              + "\n" + q.getQuestion());

        // Requirement 75: there is nowhere here to print a name, and the server has
        // already stripped it, so there would be nothing to print.
        //
        // The question only. Answers run to twenty lines, and a list of twenty-line
        // entries cannot be scanned - she is looking for WHAT was asked. Clicking a
        // row opens the whole exchange underneath.
        useWrappingCells(recentList, c ->
                c.getAskedAt().format(WHEN)
              + "\n" + c.getQuestion());

        courseBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Course course) {
                return course == null ? "" : course.getName() + "  (" + course.getCourseCode() + ")";
            }
            @Override public Course fromString(String s) {
                return null;
            }
        });

        botList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, bot) -> chooseBot(bot));

        recentList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, conversation) -> showExchange(conversation));

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNoBot();
        send(RequestType.BOT_LIST_MINE, null, REQ_BOTS);
        send(RequestType.BOT_COURSES_FREE, null, REQ_COURSES);
    }

    // -----------------------------------------------------------------
    //  Actions
    // -----------------------------------------------------------------

    @FXML
    private void onCreate() {
        Course course = courseBox.getValue();
        if (course == null) {
            showError("Choose one of your courses first.");
            return;
        }
        String name = botNameField.getText();
        if (name == null || name.trim().isEmpty()) {
            showError("Give the bot a name.");
            botNameField.requestFocus();
            return;
        }
        send(RequestType.BOT_CREATE,
             new BotCreateRequest(course.getCourseCode(), name.trim()), REQ_CREATE);
    }

    @FXML
    private void onActivate() {
        setStatus(BotStatus.ACTIVE);
    }

    @FXML
    private void onDeactivate() {
        setStatus(BotStatus.INACTIVE);
    }

    private void setStatus(BotStatus status) {
        if (chosenBot == null) {
            showError("Choose a bot first.");
            return;
        }
        send(RequestType.BOT_SET_STATUS,
             new BotStatusRequest(chosenBot.getBotId(), status), REQ_STATUS);
    }

    /**
     * Deleting is two steps, and the first one asks.
     *
     * <p>The server is asked how many stored questions would go, so the confirmation
     * can name the number. Requirement 73 says the questions and answers are kept,
     * and this destroys them - a dialog that said only "are you sure?" would be
     * hiding the part that matters.</p>
     */
    @FXML
    private void onDelete() {
        if (chosenBot == null) {
            showError("Choose a bot first.");
            return;
        }
        send(RequestType.BOT_DELETE_IMPACT, chosenBot.getBotId(), REQ_IMPACT);
    }

    private void confirmDelete(int storedQuestions) {
        Bot bot = chosenBot;
        if (bot == null) {
            return;
        }
        String detail = storedQuestions == 0
                ? "It has never been used, so nothing else goes with it."
                : "Its " + storedQuestions + " stored question"
                  + (storedQuestions == 1 ? "" : "s")
                  + " and answer" + (storedQuestions == 1 ? "" : "s")
                  + " will be deleted too, and cannot be recovered.";

        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(hsts.client.HSTSApp.getPrimaryStage());
        ask.setTitle("Delete this bot?");
        ask.setHeaderText("Delete \"" + bot.getName() + "\" from " + bot.getCourseName() + "?");
        ask.setContentText(detail + "\n\nAll the material you gave it is removed as well.");
        prepareDialog(ask, 460);

        ButtonType deleteIt = new ButtonType("Delete the bot", ButtonBar.ButtonData.OK_DONE);
        ButtonType keep = new ButtonType("Keep it", ButtonBar.ButtonData.CANCEL_CLOSE);
        ask.getButtonTypes().setAll(keep, deleteIt);

        ask.showAndWait().ifPresent(chosen -> {
            if (chosen == deleteIt) {
                send(RequestType.BOT_DELETE, bot.getBotId(), REQ_DELETE);
            } else {
                clearMessage();
            }
        });
    }

    @FXML
    private void onAddQuestionBank() {
        if (chosenBot == null) {
            showError("Choose a bot first.");
            return;
        }
        send(RequestType.BOT_ADD_SOURCE, SourceRequest.questionBank(chosenBot.getBotId(),
                chosenBot.getCourseName() + " question bank"), REQ_ADD);
    }

    @FXML
    private void onAddText() {
        if (chosenBot == null) {
            showError("Choose a bot first.");
            return;
        }
        String text = textArea.getText();
        if (text == null || text.trim().length() < 20) {
            showError("Type at least a couple of sentences for the bot to learn from.");
            textArea.requestFocus();
            return;
        }
        String title = textTitleField.getText();
        send(RequestType.BOT_ADD_SOURCE, SourceRequest.text(chosenBot.getBotId(),
                (title == null || title.isBlank()) ? "Typed notes" : title.trim(),
                text.trim()), REQ_ADD);
    }

    @FXML
    private void onAddWord() {
        upload(KnowledgeSourceType.WORD, "Word document", "*.docx");
    }

    @FXML
    private void onAddPdf() {
        upload(KnowledgeSourceType.PDF, "PDF document", "*.pdf");
    }

    /**
     * Picks a file and sends its bytes.
     *
     * <p>The bytes go to the server, not text extracted here. The client would
     * otherwise need the same parsing code, and the rule about what counts as
     * readable material has to live somewhere a client cannot talk past.</p>
     */
    private void upload(KnowledgeSourceType type, String description, String extension) {
        if (chosenBot == null) {
            showError("Choose a bot first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a " + description + " for " + chosenBot.getName());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(description, extension));
        File file = chooser.showOpenDialog(hsts.client.HSTSApp.getPrimaryStage());
        if (file == null) {
            return;                                     // she changed her mind
        }
        try {
            long size = Files.size(file.toPath());
            if (size > MAX_UPLOAD_BYTES) {
                showError("That file is " + (size / (1024 * 1024)) + " MB. Keep uploads "
                        + "under 5 MB - a bot cannot read a whole textbook at once anyway.");
                return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            showMessage("Reading " + file.getName() + "...");
            send(RequestType.BOT_ADD_SOURCE, SourceRequest.upload(chosenBot.getBotId(),
                    type, file.getName(), bytes), REQ_ADD);
        } catch (Exception e) {
            showError("Could not read that file: " + e.getMessage());
        }
    }

    @FXML
    private void onRemoveSource() {
        KnowledgeSource source = sourceList.getSelectionModel().getSelectedItem();
        if (source == null) {
            showError("Choose a piece of material to remove.");
            return;
        }
        send(RequestType.BOT_REMOVE_SOURCE, source.getSourceId(), REQ_REMOVE);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    /**
     * A colleague changed something, or a student asked a question. NFR 18.
     *
     * <p>Requirement 67 lets any teacher of the course edit the bot, so two
     * teachers can have this screen open at once - and a student asking moves the
     * usage figures under her while she watches. Both arrive here and reload.</p>
     *
     * <p>The selected bot and the open exchange are kept where they can be, so a
     * reload does not throw away what she was reading.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() != PushType.BOT_CHANGED) {
            super.onPush(event);
            return;
        }
        showMessage(event.getMessage());
        send(RequestType.BOT_LIST_MINE, null, REQ_BOTS);
        send(RequestType.BOT_COURSES_FREE, null, REQ_COURSES);
        if (chosenBot != null) {
            send(RequestType.BOT_USAGE, chosenBot.getBotId(), REQ_USAGE);
        }
    }

    // -----------------------------------------------------------------
    //  Replies
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }
        if (!response.isOk()) {
            showError(response.getMessage());
            return;
        }
        switch (id) {
            case REQ_BOTS -> {
                List<Bot> bots = (List<Bot>) response.getPayload();
                int keep = (chosenBot == null) ? -1 : chosenBot.getBotId();
                botList.setItems(FXCollections.observableArrayList(bots));
                for (Bot b : bots) {
                    if (b.getBotId() == keep) {
                        botList.getSelectionModel().select(b);
                        chosenBot = b;          // the reloaded copy, not the stale one
                        showBot(b);
                        break;
                    }
                }
                if (bots.isEmpty()) {
                    showMessage(response.getMessage());
                }
            }
            case REQ_COURSES -> {
                List<Course> free = (List<Course>) response.getPayload();
                courseBox.setItems(FXCollections.observableArrayList(free));
                createButton.setDisable(free.isEmpty());
                courseBox.setDisable(free.isEmpty());
                if (free.isEmpty()) {
                    courseBox.setPromptText("Every course you teach already has a bot");
                }
            }
            case REQ_CREATE -> {
                botNameField.clear();
                showSuccess(response.getMessage());
                chosenBot = (Bot) response.getPayload();
                refreshAll();
            }
            case REQ_STATUS, REQ_ADD, REQ_REMOVE -> {
                if (id.equals(REQ_ADD)) {
                    textArea.clear();
                    textTitleField.clear();
                }
                showSuccess(response.getMessage());
                chosenBot = (Bot) response.getPayload();
                refreshAll();
            }
            case REQ_IMPACT -> confirmDelete((Integer) response.getPayload());
            case REQ_DELETE -> {
                showSuccess(response.getMessage());
                chosenBot = null;
                botList.getSelectionModel().clearSelection();
                showNoBot();
                send(RequestType.BOT_LIST_MINE, null, REQ_BOTS);
                send(RequestType.BOT_COURSES_FREE, null, REQ_COURSES);
            }
            case REQ_USAGE -> showUsage((BotUsage) response.getPayload());
            default -> { }
        }
    }

    /**
     * The whole exchange, once she has picked a question.
     *
     * <p>Requirement 75 again: there is no name to show, because the server did not
     * send one. What she gets is the question and the answer her bot gave, in full.</p>
     */
    private void showExchange(BotConversation conversation) {
        boolean show = conversation != null;
        fullExchangeBox.setVisible(show);
        fullExchangeBox.setManaged(show);
        if (!show) {
            return;
        }
        exchangeWhenLabel.setText(conversation.getAskedAt().format(WHEN));
        exchangeQuestionLabel.setText(conversation.getQuestion());
        exchangeAnswerLabel.setText(conversation.getAnswer());
    }

    private void refreshAll() {
        send(RequestType.BOT_LIST_MINE, null, REQ_BOTS);
        send(RequestType.BOT_COURSES_FREE, null, REQ_COURSES);
        if (chosenBot != null) {
            showBot(chosenBot);
            send(RequestType.BOT_USAGE, chosenBot.getBotId(), REQ_USAGE);
        }
    }

    // -----------------------------------------------------------------
    //  Display
    // -----------------------------------------------------------------

    private void chooseBot(Bot bot) {
        chosenBot = bot;
        if (bot == null) {
            showNoBot();
            return;
        }
        showBot(bot);
        send(RequestType.BOT_USAGE, bot.getBotId(), REQ_USAGE);
    }

    private void showNoBot() {
        chosenBot = null;
        botTitleLabel.setText("No bot chosen");
        botMetaLabel.setText("Pick one on the left, or create one below it.");
        sourceList.setItems(FXCollections.observableArrayList());
        usageStatsRow.getChildren().clear();
        commonList.setItems(FXCollections.observableArrayList());
        recentList.setItems(FXCollections.observableArrayList());
        onButton.setDisable(true);
        offButton.setDisable(true);
        removeSourceButton.setDisable(true);
        deleteButton.setDisable(true);
        showExchange(null);
    }

    private void showBot(Bot bot) {
        botTitleLabel.setText(bot.getName() + "   ·   " + bot.getCourseName());
        botMetaLabel.setText(bot.getStatus().getDisplayName()
                + "   ·   created by " + bot.getCreatedByName()
                + "   ·   " + bot.getSources().size()
                + (bot.getSources().size() == 1 ? " source" : " sources")
                + (bot.hasKnowledge() ? "" : "   ·   nothing to read yet, so it cannot be turned on"));

        sourceList.setItems(FXCollections.observableArrayList(bot.getSources()));
        onButton.setDisable(bot.isActive() || !bot.hasKnowledge());
        offButton.setDisable(!bot.isActive());
        removeSourceButton.setDisable(bot.getSources().isEmpty());
        deleteButton.setDisable(false);
        showExchange(null);          // a different bot, so the old exchange is stale
    }

    private void showUsage(BotUsage usage) {
        // Which exchange she was reading, so a reload triggered by somebody else
        // does not close it under her.
        BotConversation wasReading = recentList.getSelectionModel().getSelectedItem();

        usageStatsRow.getChildren().setAll(
                statTile("Questions asked", String.valueOf(usage.getTotalQuestions())),
                statTile("Students using it", String.valueOf(usage.getDistinctStudents())),
                statTile("Different questions",
                        String.valueOf(usage.getCommonQuestions().size())));
        commonList.setItems(FXCollections.observableArrayList(usage.getCommonQuestions()));
        recentList.setItems(FXCollections.observableArrayList(usage.getRecent()));

        if (wasReading != null) {
            for (BotConversation c : usage.getRecent()) {
                if (c.getConvId() == wasReading.getConvId()) {
                    recentList.getSelectionModel().select(c);
                    break;
                }
            }
        }
        if (usage.getTotalQuestions() == 0) {
            commonList.setPlaceholder(new Label("Nobody has asked it anything yet."));
            recentList.setPlaceholder(new Label("Nothing to show yet."));
        }
    }

    private VBox statTile(String caption, String value) {
        Label number = new Label(value);
        number.setStyle("-fx-font-size: 22px; -fx-font-weight: 600;");
        Label name = new Label(caption);
        name.getStyleClass().add("caption");
        name.setWrapText(true);

        VBox tile = new VBox(2, number, name);
        tile.getStyleClass().add("card");
        tile.setMinWidth(140);
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
