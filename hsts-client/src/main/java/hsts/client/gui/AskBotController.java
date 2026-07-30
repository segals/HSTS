package hsts.client.gui;

import hsts.common.entity.Bot;
import hsts.common.entity.BotConversation;
import hsts.common.protocol.BotQuestion;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SUC-14 and the student's half of SUC-15: asking the course bot.
 *
 * <p>One screen for both, because asking and reading back what you asked are the
 * same activity. Requirement 74 wants the question, the answer and the time; all
 * three are in the history list, and clicking one shows it in full.</p>
 *
 * <p><b>No rule is decided here.</b> Requirement 70 (enrolled and switched on) and
 * requirement 71 (not while she is sitting that course's exam) are the server's, and
 * this screen shows whatever refusal comes back. Deciding on the client would mean
 * the check could be bypassed by anything that is not this client - and requirement
 * 71 in particular is the one worth cheating.</p>
 */
public class AskBotController extends GUIScreen {

    private static final String REQ_BOTS    = "ab.bots";
    private static final String REQ_ASK     = "ab.ask";
    private static final String REQ_HISTORY = "ab.history";

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private Label  subtitleLabel;
    @FXML private Button backButton;
    @FXML private ListView<Bot> botList;
    @FXML private ListView<BotConversation> historyList;

    @FXML private Label    botTitleLabel;
    @FXML private Label    botMetaLabel;
    @FXML private VBox     answerBox;
    @FXML private TextArea questionArea;
    @FXML private Button   askButton;
    @FXML private Label    hintLabel;
    @FXML private Label    statusLabel;

    private Bot chosenBot;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Ask about your courses. The bot is not available during "
                            + "an exam in that course.");
        hintLabel.setText("The bot reads what your teacher gave it.");

        useWrappingCells(botList, b ->
                b.getName() + "  ·  " + b.getStatus().getDisplayName()
              + "\n" + b.getCourseName());

        useWrappingCells(historyList, c ->
                c.getAskedAt().format(WHEN) + "  ·  " + c.getCourseName()
              + "\n" + shorten(c.getQuestion(), 120));

        botList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, bot) -> chooseBot(bot));

        // Requirement 74: clicking one shows the question, the answer and the time.
        historyList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, conversation) -> {
                    if (conversation != null) {
                        showConversation(conversation, true);
                    }
                });

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        showNoBot();
        send(RequestType.BOT_AVAILABLE, null, REQ_BOTS);
        send(RequestType.BOT_MY_HISTORY, null, REQ_HISTORY);
    }

    @FXML
    private void onAsk() {
        if (chosenBot == null) {
            showError("Choose one of your course bots first.");
            return;
        }
        String question = questionArea.getText();
        if (question == null || question.trim().isEmpty()) {
            showError("Type a question first.");
            questionArea.requestFocus();
            return;
        }
        askButton.setDisable(true);
        showMessage("Asking " + chosenBot.getName() + "...");
        send(RequestType.BOT_ASK,
             new BotQuestion(chosenBot.getCourseCode(), question.trim()), REQ_ASK);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    /**
     * Her teacher switched a bot on or off, or deleted one. NFR 18.
     *
     * <p>Requirement 60 lets the teacher flip availability at any moment, and
     * requirement 70 makes that the difference between being able to ask and not.
     * Without this she would sit looking at "not switched on" after it had been
     * switched on, and there is no Refresh button to press.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() != PushType.BOT_AVAILABILITY_CHANGED) {
            super.onPush(event);
            return;
        }
        showMessage(event.getMessage());
        send(RequestType.BOT_AVAILABLE, null, REQ_BOTS);
    }

    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }
        if (!response.isOk()) {
            // Every refusal lands here: not enrolled, not switched on, you are in an
            // exam, nothing came back. Each has its own wording from the server.
            askButton.setDisable(false);
            showError(response.getMessage());
            return;
        }
        switch (id) {
            case REQ_BOTS -> {
                List<Bot> bots = (List<Bot>) response.getPayload();
                // Which course she was on. Kept by COURSE, not by bot id: her
                // teacher may have switched to a different bot on the same course,
                // and she should follow it rather than lose her place.
                String wasOn = (chosenBot == null) ? null : chosenBot.getCourseCode();

                botList.setItems(FXCollections.observableArrayList(bots));
                botList.setPlaceholder(new Label("None of your courses has a study bot."));

                if (bots.isEmpty()) {
                    showNoBot();
                    showMessage(response.getMessage());
                } else if (wasOn != null) {
                    for (Bot b : bots) {
                        if (wasOn.equals(b.getCourseCode())) {
                            botList.getSelectionModel().select(b);
                            chooseBot(b);       // status may have flipped
                            break;
                        }
                    }
                } else if (bots.size() == 1) {
                    botList.getSelectionModel().select(0);
                }
            }
            case REQ_ASK -> {
                askButton.setDisable(false);
                questionArea.clear();
                showConversation((BotConversation) response.getPayload(), false);
                clearMessage();
                send(RequestType.BOT_MY_HISTORY, null, REQ_HISTORY);
            }
            case REQ_HISTORY -> {
                List<BotConversation> history = (List<BotConversation>) response.getPayload();
                historyList.setItems(FXCollections.observableArrayList(history));
                historyList.setPlaceholder(
                        new Label("You have not asked anything yet."));
            }
            default -> { }
        }
    }

    private void chooseBot(Bot bot) {
        chosenBot = bot;
        if (bot == null) {
            showNoBot();
            return;
        }
        botTitleLabel.setText(bot.getName() + "   ·   " + bot.getCourseName());
        botMetaLabel.setText(bot.isActive()
                ? "Ready. Type your question below."
                : "Your teacher has not switched this bot on. You cannot ask it anything "
                + "at the moment.");
        askButton.setDisable(!bot.isActive());
    }

    private void showNoBot() {
        chosenBot = null;
        botTitleLabel.setText("No bot chosen");
        botMetaLabel.setText("Pick one of your course bots on the left.");
        answerBox.getChildren().clear();
        askButton.setDisable(true);
    }

    /** Shows one exchange. {@code fromHistory} only changes the heading. */
    private void showConversation(BotConversation conversation, boolean fromHistory) {
        Label when = new Label((fromHistory ? "Asked " : "Just asked, ")
                + conversation.getAskedAt().format(WHEN)
                + "   ·   " + conversation.getCourseName());
        when.getStyleClass().add("caption");
        when.setWrapText(true);

        Label question = new Label(conversation.getQuestion());
        question.setWrapText(true);
        question.getStyleClass().add("h3");
        question.setMaxWidth(Double.MAX_VALUE);

        Label answer = new Label(conversation.getAnswer());
        answer.setWrapText(true);
        answer.setMaxWidth(Double.MAX_VALUE);

        VBox answerCard = new VBox(6, answer);
        answerCard.getStyleClass().add("card");

        answerBox.getChildren().setAll(when, question, answerCard);
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "...";
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
