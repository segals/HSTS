package hsts.client.gui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A search box and a row of buttons, over any list.
 *
 * <h2>Why buttons and not only a box</h2>
 *
 * <p>Typing works when you already know what to type. A button says what there is:
 * a teacher looking at fifty exams can see at a glance that they are in four
 * courses and two states, which no amount of typing will tell her. So both -
 * buttons for the things there is a known list of, and free text for everything
 * else.</p>
 *
 * <p>Buttons in the same group are OR'd and the groups are AND'd, which is what
 * people expect without being told: "Algebra or Mechanics, and only the approved
 * ones". Nothing selected means no restriction, so the list starts complete.</p>
 *
 * @param <T> what the list holds
 */
public class FilterBar<T> extends VBox {

    private final TextField search = new TextField();
    private final List<Group<T>> groups = new ArrayList<>();
    private final Label countLabel = new Label();
    private Function<T, String> searchText = item -> String.valueOf(item);
    private Runnable onChanged = () -> { };

    /** One row of buttons: its heading, its heading label, the choices, and the test. */
    private record Group<T>(String heading, Label label, FlowPane buttons,
                            BiPredicate<T, String> matches) { }

    /**
     * What was ticked in each row before the rows were thrown away.
     *
     * <p>The rows are rebuilt whenever fresh data arrives, and fresh data now
     * arrives on its own: a teacher releasing an exam reloads the principal's
     * lists under her. Without this, her filter cleared itself every time somebody
     * else did something, which is worse than the stale list it was meant to
     * fix.</p>
     */
    private final java.util.Map<String, Set<String>> remembered = new java.util.HashMap<>();

    public FilterBar() {
        setSpacing(6);
        search.setPromptText("Search");
        search.textProperty().addListener((o, was, now) -> changed());

        Button clear = new Button("Clear filters");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> clear());

        countLabel.getStyleClass().add("caption");
        countLabel.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(8, search, clear, countLabel);
        HBox.setHgrow(search, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(row);
    }

    /** What the free-text box searches in. */
    public FilterBar<T> searchingIn(Function<T, String> text) {
        this.searchText = (text == null) ? item -> "" : text;
        return this;
    }

    /**
     * Adds a row of buttons.
     *
     * @param heading what the row is - "COURSE", "STATUS"
     * @param choices the button captions, in the order they should appear
     * @param matches whether one item matches one caption
     */
    public FilterBar<T> withButtons(String heading, List<String> choices,
                                    BiPredicate<T, String> matches) {
        if (choices == null || choices.isEmpty()) {
            return this;
        }
        Label label = new Label(heading);
        label.getStyleClass().add("section-title");
        label.setWrapText(true);

        Set<String> wasTicked = remembered.getOrDefault(heading, Set.of());

        FlowPane buttons = new FlowPane(6, 6);
        for (String choice : choices) {
            ToggleButton button = new ToggleButton(choice);
            button.getStyleClass().add("filter-chip");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setSelected(wasTicked.contains(choice));
            button.setOnAction(e -> changed());
            buttons.getChildren().add(button);
        }
        groups.add(new Group<>(heading, label, buttons, matches));
        getChildren().addAll(label, buttons);
        return this;
    }

    /**
     * Removes every button row, keeping the search box and its text.
     *
     * <p>The choices come from the data, and the data arrives after the screen is
     * built - and arrives again when she picks another course. Rebuilding is how a
     * course with no approved exams avoids having a button that always shows
     * nothing.</p>
     */
    public void clearGroups() {
        for (Group<T> group : groups) {
            // Remember what was ticked, so a reload does not clear her filter.
            remembered.put(group.heading(), selected(group.buttons()));
            getChildren().removeAll(group.label(), group.buttons());
        }
        groups.clear();
    }

    /** The distinct values of one field, in order, for a row of buttons. */
    public static <T> List<String> distinct(List<T> items, Function<T, String> field) {
        Set<String> values = new java.util.TreeSet<>();
        for (T item : items) {
            String value = field.apply(item);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    /** Called whenever a filter changes, so the screen can re-apply it. */
    public FilterBar<T> onChanged(Runnable listener) {
        this.onChanged = (listener == null) ? () -> { } : listener;
        return this;
    }

    /** Everything from the list that passes every filter. */
    public List<T> apply(List<T> items) {
        List<T> kept = new ArrayList<>();
        String typed = search.getText() == null ? "" : search.getText().trim().toLowerCase();

        for (T item : items) {
            if (!typed.isEmpty()) {
                String text = searchText.apply(item);
                if (text == null || !text.toLowerCase().contains(typed)) {
                    continue;
                }
            }
            boolean keep = true;
            for (Group<T> group : groups) {
                Set<String> wanted = selected(group.buttons());
                if (wanted.isEmpty()) {
                    continue;                     // this row restricts nothing
                }
                boolean any = false;
                for (String choice : wanted) {
                    if (group.matches().test(item, choice)) {
                        any = true;
                    }
                }
                if (!any) {
                    keep = false;
                }
            }
            if (keep) {
                kept.add(item);
            }
        }
        countLabel.setText(kept.size() + " of " + items.size() + " shown");
        return kept;
    }

    public void clear() {
        search.clear();
        // Forget the ticks as well, or the next reload would put back the very
        // filter she has just cleared.
        remembered.clear();
        for (Group<T> group : groups) {
            for (var node : group.buttons().getChildren()) {
                ((ToggleButton) node).setSelected(false);
            }
        }
        changed();
    }

    private void changed() {
        onChanged.run();
    }

    private static Set<String> selected(FlowPane buttons) {
        Set<String> picked = new LinkedHashSet<>();
        for (var node : buttons.getChildren()) {
            ToggleButton button = (ToggleButton) node;
            if (button.isSelected()) {
                picked.add(button.getText());
            }
        }
        return picked;
    }
}
