package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.Question;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shows every stored version of a question and what changed between them.
 *
 * <p>This window is the demo evidence for מתווה scenario 2 item 2 - editing a
 * question keeps the previous version in the bank.</p>
 *
 * <p>An earlier attempt simply listed the versions in a dialog. That proved they
 * existed but not what had actually changed, which made it useless for the one
 * thing it is for. This version lays the selected version beside the current one,
 * field by field, and marks every difference.</p>
 */
public class VersionHistoryController {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private Label    titleLabel;
    @FXML private Label    subtitleLabel;
    @FXML private ListView<Question> versionList;
    @FXML private GridPane diffGrid;
    @FXML private Label    changeSummaryLabel;
    @FXML private Button   closeButton;

    /** Newest first, as the server returns them. */
    private List<Question> versions = new ArrayList<>();
    private Question current;

    @FXML
    private void initialize() {
        diffGrid.getColumnConstraints().addAll(
                fixed(120), grow(), grow());

        versionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Question q, boolean empty) {
                super.updateItem(q, empty);
                if (empty || q == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label version = new Label("Version " + q.getVersion());
                version.getStyleClass().add("h3");

                Label badge = new Label(q.isCurrent() ? "CURRENT" : "OLD");
                badge.getStyleClass().add(q.isCurrent() ? "badge-current" : "badge-old");

                HBox top = new HBox(8, version, badge);
                Label when = new Label(q.getCreatedAt().format(WHEN) + "  ·  " + q.getAuthorName());
                when.getStyleClass().add("caption");

                setGraphic(new VBox(3, top, when));
                setText(null);
            }
        });

        versionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> showComparison(selected));
    }

    /** Called by the screen that opened this window. */
    public void setVersions(List<Question> versions) {
        this.versions = versions;
        this.current = versions.stream().filter(Question::isCurrent)
                               .findFirst().orElse(versions.get(0));

        titleLabel.setText("Question " + current.getQuestionId() + "  ·  version history");
        subtitleLabel.setText(versions.size() + " version(s) stored. "
                + (versions.size() == 1
                   ? "This question has not been edited yet."
                   : "Every earlier version is still in the database."));

        versionList.setItems(FXCollections.observableArrayList(versions));

        // Pre-select the newest version that is NOT the current one, because that
        // is the comparison the user came here to see.
        versions.stream().filter(q -> !q.isCurrent()).findFirst()
                .ifPresentOrElse(versionList.getSelectionModel()::select,
                                 () -> versionList.getSelectionModel().selectFirst());
    }

    // -----------------------------------------------------------------

    private void showComparison(Question selected) {
        diffGrid.getChildren().clear();
        if (selected == null) {
            return;
        }

        boolean sameVersion = selected.getVersion() == current.getVersion();
        int row = 0;
        int changes = 0;

        // ---- column headings ----
        addHeader(row++, sameVersion);

        // ---- each field ----
        changes += addRow(row++, "Question text",  selected.getText(),         current.getText());
        changes += addRow(row++, "Instructions",   nullToDash(selected.getInstructions()),
                                                   nullToDash(current.getInstructions()));
        changes += addRow(row++, "Topic",          selected.getTopic(),        current.getTopic());
        changes += addRow(row++, "Difficulty",     selected.getDifficulty().getDisplayName(),
                                                   current.getDifficulty().getDisplayName());

        for (int i = 1; i <= 4; i++) {
            changes += addRow(row++, "Answer " + i,
                    describeAnswer(selected, i), describeAnswer(current, i));
        }

        changes += addRow(row++, "Correct answer",
                describeCorrect(selected), describeCorrect(current));
        changes += addRow(row++, "Picture",
                selected.hasImage() ? "attached" : "none",
                current.hasImage()  ? "attached" : "none");
        addRow(row++, "Written",
                selected.getCreatedAt().format(WHEN), current.getCreatedAt().format(WHEN));
        addRow(row, "Written by", selected.getAuthorName(), current.getAuthorName());

        if (sameVersion) {
            changeSummaryLabel.setText("This is the current version.");
        } else if (changes == 0) {
            changeSummaryLabel.setText("No differences in content between these two versions.");
        } else {
            changeSummaryLabel.setText(changes + " field(s) changed, highlighted below.");
        }
    }

    private void addHeader(int row, boolean sameVersion) {
        Label field = new Label("FIELD");
        field.getStyleClass().add("section-title");

        Question selected = versionList.getSelectionModel().getSelectedItem();

        Label left = new Label(sameVersion
                ? "Version " + current.getVersion() + " (current)"
                : "Version " + selected.getVersion() + " (older)");
        left.getStyleClass().add("h3");

        Label right = new Label("Version " + current.getVersion() + " (current)");
        right.getStyleClass().add("h3");

        diffGrid.add(field, 0, row);
        diffGrid.add(left,  1, row);
        diffGrid.add(right, 2, row);
    }

    /**
     * Adds one comparison row.
     *
     * @return 1 if the two values differ, 0 if they match - so the caller can
     *         count how much actually changed.
     */
    private int addRow(int row, String fieldName, String before, String after) {
        boolean changed = !Objects.equals(before, after);

        Label name = new Label(fieldName);
        name.getStyleClass().add("field-label");
        name.setWrapText(true);

        Label left = new Label(before);
        left.setWrapText(true);
        left.getStyleClass().add(changed ? "diff-changed" : "diff-same");
        left.setMaxWidth(Double.MAX_VALUE);

        Label right = new Label(after);
        right.setWrapText(true);
        right.getStyleClass().add(changed ? "diff-changed" : "diff-same");
        right.setMaxWidth(Double.MAX_VALUE);

        diffGrid.add(name,  0, row);
        diffGrid.add(left,  1, row);
        diffGrid.add(right, 2, row);
        return changed ? 1 : 0;
    }

    private String describeAnswer(Question q, int answerNo) {
        for (Answer a : q.getAnswers()) {
            if (a.getAnswerNo() == answerNo) {
                return a.getText() + (a.isCorrect() ? "   ✓" : "");
            }
        }
        return "—";
    }

    private String describeCorrect(Question q) {
        Answer correct = q.getCorrectAnswer();
        return correct == null ? "—" : "Answer " + correct.getAnswerNo();
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private static ColumnConstraints fixed(double width) {
        ColumnConstraints c = new ColumnConstraints();
        c.setMinWidth(width);
        c.setPrefWidth(width);
        return c;
    }

    private static ColumnConstraints grow() {
        ColumnConstraints c = new ColumnConstraints();
        c.setHgrow(Priority.ALWAYS);
        c.setMinWidth(180);
        return c;
    }

    @FXML
    private void onClose() {
        ((Stage) closeButton.getScene().getWindow()).close();
    }
}
