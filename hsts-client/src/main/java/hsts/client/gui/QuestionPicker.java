package hsts.client.gui;

import hsts.common.entity.Question;
import hsts.common.enums.DifficultyLevel;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Choosing questions from the bank: a tick beside each, and filters above.
 *
 * <h2>Why this replaced ctrl-click</h2>
 *
 * <p>The bank was a multi-select list with a line underneath reading "Ctrl-click to
 * select several". That is a keyboard trick people either know or do not, one slip
 * of the mouse loses a selection built up over a minute, and there is no way to
 * tell from a screenshot what is selected. A tick box says what is chosen, keeps
 * saying it, and needs nothing explained.</p>
 *
 * <p>It also survives filtering, which ctrl-click could not: tick four questions
 * about circles, type "triangle", tick two more, and all six are still chosen.
 * That is the whole point of having filters here at all.</p>
 *
 * <h2>Filters</h2>
 *
 * <p>Free text over the name, the number and the text; a button per topic; a button
 * per difficulty. Buttons rather than another box to type in, because the topics
 * are a short known list and reading them is faster than remembering them - and a
 * typed filter cannot tell you what is available.</p>
 *
 * <p>Buttons of the same kind are OR'd and the kinds are AND'd: "Angles or Circles,
 * and only the hard ones". Nothing is selected to begin with, which means
 * everything is shown.</p>
 */
public class QuestionPicker extends VBox {

    private final TextField search = new TextField();
    private final FlowPane topicButtons = new FlowPane(6, 6);
    private final FlowPane difficultyButtons = new FlowPane(6, 6);
    private final ListView<Question> list = new ListView<>();
    private final Label countLabel = new Label();

    /** Everything the bank sent, before filtering. */
    private final List<Question> all = new ArrayList<>();

    /**
     * The questions ticked, by id.
     *
     * <p>Kept here rather than read off the visible rows, because filtering removes
     * rows and a selection that disappeared when you searched would be worse than
     * no filter at all.</p>
     */
    private final Set<String> chosen = new LinkedHashSet<>();

    /** Told whenever the tick boxes change, so a screen can update its own count. */
    private Runnable onChanged = () -> { };

    public QuestionPicker() {
        setSpacing(8);

        search.setPromptText("Search the name, the number or the text");
        search.textProperty().addListener((o, was, now) -> refresh());

        Button clear = new Button("Clear filters");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> clearFilters());

        HBox searchRow = new HBox(8, search, clear);
        HBox.setHgrow(search, Priority.ALWAYS);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Label topicLabel = new Label("TOPIC");
        topicLabel.getStyleClass().add("section-title");
        Label hardLabel = new Label("DIFFICULTY");
        hardLabel.getStyleClass().add("section-title");

        for (DifficultyLevel level : DifficultyLevel.values()) {
            ToggleButton button = new ToggleButton(level.getDisplayName());
            button.getStyleClass().add("filter-chip");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setUserData(level);
            button.setOnAction(e -> refresh());
            difficultyButtons.getChildren().add(button);
        }

        list.setItems(FXCollections.observableArrayList());
        list.setCellFactory(view -> new TickCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        Button all = new Button("Tick everything shown");
        all.setMinWidth(Region.USE_PREF_SIZE);
        all.setOnAction(e -> tickAllShown(true));
        Button none = new Button("Untick all");
        none.setMinWidth(Region.USE_PREF_SIZE);
        none.setOnAction(e -> {
            chosen.clear();
            list.refresh();
            changed();
        });

        countLabel.getStyleClass().add("caption");
        HBox bottom = new HBox(8, all, none, spacer(), countLabel);
        bottom.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(searchRow, topicLabel, topicButtons,
                             hardLabel, difficultyButtons, list, bottom);
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /** Called whenever the ticks change. */
    public void setOnChanged(Runnable listener) {
        this.onChanged = (listener == null) ? () -> { } : listener;
    }

    /**
     * Loads a bank. Ticks are cleared, because they belonged to the old course.
     */
    public void setQuestions(List<Question> questions) {
        all.clear();
        chosen.clear();
        if (questions != null) {
            all.addAll(questions);
        }
        rebuildTopicButtons();
        refresh();
        changed();
    }

    /** The ticked questions, in the order the bank listed them. */
    public List<Question> getChosen() {
        List<Question> picked = new ArrayList<>();
        for (Question q : all) {
            if (chosen.contains(q.getQuestionId())) {
                picked.add(q);
            }
        }
        return picked;
    }

    public List<String> getChosenIds() {
        List<String> ids = new ArrayList<>();
        for (Question q : getChosen()) {
            ids.add(q.getQuestionId());
        }
        return ids;
    }

    public int getChosenCount() {
        return chosen.size();
    }

    public void clearChoice() {
        chosen.clear();
        list.refresh();
        changed();
    }

    // -----------------------------------------------------------------

    /**
     * One topic button per topic actually present in this bank.
     *
     * <p>Built from the questions rather than from a fixed list: topics are typed by
     * teachers, so the only honest source of "what topics are there" is what is in
     * front of her.</p>
     */
    private void rebuildTopicButtons() {
        topicButtons.getChildren().clear();
        Set<String> topics = new java.util.TreeSet<>();
        for (Question q : all) {
            if (q.getTopic() != null && !q.getTopic().isBlank()) {
                topics.add(q.getTopic());
            }
        }
        for (String topic : topics) {
            ToggleButton button = new ToggleButton(topic);
            button.getStyleClass().add("filter-chip");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setUserData(topic);
            button.setOnAction(e -> refresh());
            topicButtons.getChildren().add(button);
        }
        topicButtons.setVisible(!topics.isEmpty());
        topicButtons.setManaged(!topics.isEmpty());
    }

    private void clearFilters() {
        search.clear();
        for (var node : topicButtons.getChildren()) {
            ((ToggleButton) node).setSelected(false);
        }
        for (var node : difficultyButtons.getChildren()) {
            ((ToggleButton) node).setSelected(false);
        }
        refresh();
    }

    private void refresh() {
        String typed = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        Set<String> wantedTopics = selected(topicButtons);
        Set<String> wantedLevels = selected(difficultyButtons);

        List<Question> shown = new ArrayList<>();
        for (Question q : all) {
            if (!wantedTopics.isEmpty() && !wantedTopics.contains(String.valueOf(q.getTopic()))) {
                continue;
            }
            if (!wantedLevels.isEmpty()
                    && !wantedLevels.contains(String.valueOf(q.getDifficulty()))) {
                continue;
            }
            if (!typed.isEmpty() && !matches(q, typed)) {
                continue;
            }
            shown.add(q);
        }
        list.setItems(FXCollections.observableArrayList(shown));
        updateCount(shown.size());
    }

    private static Set<String> selected(FlowPane buttons) {
        Set<String> picked = new LinkedHashSet<>();
        for (var node : buttons.getChildren()) {
            ToggleButton button = (ToggleButton) node;
            if (button.isSelected()) {
                picked.add(String.valueOf(button.getUserData()));
            }
        }
        return picked;
    }

    private static boolean matches(Question q, String typed) {
        return contains(q.getName(), typed)
            || contains(q.getQuestionId(), typed)
            || contains(q.getText(), typed)
            || contains(q.getTopic(), typed);
    }

    private static boolean contains(String value, String typed) {
        return value != null && value.toLowerCase().contains(typed);
    }

    private void updateCount(int shown) {
        countLabel.setText(shown + " of " + all.size() + " shown   ·   "
                + chosen.size() + " chosen");
    }

    private void tickAllShown(boolean tick) {
        for (Question q : list.getItems()) {
            if (tick) {
                chosen.add(q.getQuestionId());
            } else {
                chosen.remove(q.getQuestionId());
            }
        }
        list.refresh();
        changed();
    }

    private void changed() {
        updateCount(list.getItems().size());
        onChanged.run();
    }

    /** A row: a tick box, then the name, number, topic and difficulty. */
    private class TickCell extends ListCell<Question> {
        private final CheckBox box = new CheckBox();
        private final Label label = new Label();
        private final HBox row = new HBox(8, box, label);

        TickCell() {
            label.setWrapText(true);
            label.maxWidthProperty().bind(list.widthProperty().subtract(60));
            row.setAlignment(Pos.TOP_LEFT);
            box.setOnAction(e -> {
                Question q = getItem();
                if (q == null) {
                    return;
                }
                if (box.isSelected()) {
                    chosen.add(q.getQuestionId());
                } else {
                    chosen.remove(q.getQuestionId());
                }
                changed();
            });
        }

        @Override
        protected void updateItem(Question q, boolean empty) {
            super.updateItem(q, empty);
            if (empty || q == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            box.setSelected(chosen.contains(q.getQuestionId()));
            label.setText(q.describe()
                    + "\n" + q.getTopic() + "  ·  " + q.getDifficulty().getDisplayName()
                    + "\n" + q.getText());
            setGraphic(row);
            setText(null);
        }
    }
}
