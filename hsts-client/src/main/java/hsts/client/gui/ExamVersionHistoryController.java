package hsts.client.gui;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamQuestion;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every stored version of one exam, laid beside the current one.
 *
 * <p>The same window as {@link VersionHistoryController} and deliberately so: an
 * exam's history was a block of text in a dialog, which proved the old versions
 * existed and left the reader to spot the difference herself. Editing an exam
 * matters more than editing a question - a new version has to be approved all
 * over again - so "what did I actually change" is a question worth answering on
 * the screen.</p>
 *
 * <p>The one thing an exam has that a question has not is its list of questions
 * and their marks. It is compared as a field like any other, written out one
 * question to a line, so a swapped question or a moved mark shows up in the same
 * place as a changed duration.</p>
 */
public class ExamVersionHistoryController {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    @FXML private Label    titleLabel;
    @FXML private Label    subtitleLabel;
    @FXML private ListView<Exam> versionList;
    @FXML private GridPane diffGrid;
    @FXML private Label    changeSummaryLabel;
    @FXML private Button   closeButton;

    /** Newest first, as the server returns them. */
    private List<Exam> versions = new ArrayList<>();
    private Exam current;

    @FXML
    private void initialize() {
        diffGrid.getColumnConstraints().addAll(fixed(130), grow(), grow());

        versionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Exam exam, boolean empty) {
                super.updateItem(exam, empty);
                if (empty || exam == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label version = new Label("Version " + exam.getVersion());
                version.getStyleClass().add("h3");

                Label badge = new Label(exam.isCurrent() ? "CURRENT" : "OLD");
                badge.getStyleClass().add(exam.isCurrent() ? "badge-current" : "badge-old");

                HBox top = new HBox(8, version, badge);

                // What it was, not only when: the state is the reason an old
                // version is worth looking at - it is usually the rejected one.
                Label what = new Label(exam.getQuestionCount() + " questions  ·  "
                        + exam.getDurationMinutes() + " min  ·  "
                        + exam.getStatus().getDisplayName());
                what.getStyleClass().add("caption");
                what.setWrapText(true);

                Label when = new Label(exam.getCreatedAt() == null
                        ? "" : exam.getCreatedAt().format(WHEN));
                when.getStyleClass().add("caption");
                when.setWrapText(true);

                VBox row = new VBox(3, top, what, when);
                row.setMinHeight(Region.USE_PREF_SIZE);
                setGraphic(row);
                setText(null);
            }
        });

        versionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, selected) -> showComparison(selected));
    }

    /** Called by the screen that opened this window. */
    public void setVersions(List<Exam> versions) {
        this.versions = versions;
        this.current = versions.stream().filter(Exam::isCurrent)
                               .findFirst().orElse(versions.get(0));

        titleLabel.setText(current.describe() + "  ·  version history");
        subtitleLabel.setText(versions.size() + " version(s) stored. "
                + (versions.size() == 1
                   ? "This exam has not been edited yet."
                   : "Every earlier version is still in the database, with the "
                   + "questions it had at the time."));

        versionList.setItems(FXCollections.observableArrayList(versions));

        // The newest version that is not the current one is the comparison
        // somebody opening this window came to see.
        versions.stream().filter(e -> !e.isCurrent()).findFirst()
                .ifPresentOrElse(versionList.getSelectionModel()::select,
                                 () -> versionList.getSelectionModel().selectFirst());
    }

    // -----------------------------------------------------------------

    private void showComparison(Exam selected) {
        diffGrid.getChildren().clear();
        if (selected == null) {
            return;
        }

        boolean sameVersion = selected.getVersion() == current.getVersion();
        int row = 0;
        int changes = 0;

        addHeader(row++, sameVersion);

        changes += addRow(row++, "Name",        nullToDash(selected.getName()),
                                                nullToDash(current.getName()));
        changes += addRow(row++, "Duration",    selected.getDurationMinutes() + " minutes",
                                                current.getDurationMinutes() + " minutes");
        changes += addRow(row++, "Questions",   String.valueOf(selected.getQuestionCount()),
                                                String.valueOf(current.getQuestionCount()));
        changes += addRow(row++, "Total marks", String.valueOf(selected.getTotalPoints()),
                                                String.valueOf(current.getTotalPoints()));
        changes += addRow(row++, "For the students",
                                                nullToDash(selected.getInstructionsForStudents()),
                                                nullToDash(current.getInstructionsForStudents()));
        changes += addRow(row++, "Private note", nullToDash(selected.getNotesForTeacher()),
                                                 nullToDash(current.getNotesForTeacher()));
        changes += addRow(row++, "The paper",   describeQuestions(selected),
                                                describeQuestions(current));

        // Not counted as a change: the state is what happened TO a version, not
        // something its author edited. An old version is nearly always rejected
        // and the current one nearly always is not, so counting it would make
        // every comparison claim one more change than was made.
        addRow(row++, "State", selected.getStatus().getDisplayName(),
                               current.getStatus().getDisplayName());
        addRow(row++, "Written", selected.getCreatedAt() == null
                        ? "—" : selected.getCreatedAt().format(WHEN),
                                current.getCreatedAt() == null
                        ? "—" : current.getCreatedAt().format(WHEN));
        addRow(row, "Written by", nullToDash(selected.getAuthorName()),
                                  nullToDash(current.getAuthorName()));

        if (sameVersion) {
            changeSummaryLabel.setText("This is the current version.");
        } else if (changes == 0) {
            changeSummaryLabel.setText("No differences in content between these two versions.");
        } else {
            changeSummaryLabel.setText(changes + " field(s) changed, highlighted below.");
        }
    }

    /**
     * The paper itself, one question to a line.
     *
     * <p>The question's own version is part of the line: an exam pins the version
     * it was built with, so the same question at v2 and at v3 are two different
     * papers and the comparison has to say so.</p>
     */
    private static String describeQuestions(Exam exam) {
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            return "—";
        }
        StringBuilder text = new StringBuilder();
        int number = 1;
        for (ExamQuestion eq : exam.getQuestions()) {
            String name = (eq.getQuestion() == null || eq.getQuestion().getName() == null
                           || eq.getQuestion().getName().isBlank())
                    ? eq.getQuestionId()
                    : eq.getQuestion().getName() + "  ·  " + eq.getQuestionId();
            text.append(number++).append(".  ").append(name)
                .append("  v").append(eq.getQuestionVersion())
                .append("  ·  ").append(eq.getPoints())
                .append(eq.getPoints() == 1 ? " mark" : " marks");
            if (number <= exam.getQuestions().size()) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private void addHeader(int row, boolean sameVersion) {
        Label field = new Label("FIELD");
        field.getStyleClass().add("section-title");

        Exam selected = versionList.getSelectionModel().getSelectedItem();

        Label left = new Label(sameVersion
                ? "Version " + current.getVersion() + " (current)"
                : "Version " + selected.getVersion() + " (older)");
        left.getStyleClass().add("h3");
        left.setWrapText(true);

        Label right = new Label("Version " + current.getVersion() + " (current)");
        right.getStyleClass().add("h3");
        right.setWrapText(true);

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
        name.setMinHeight(Region.USE_PREF_SIZE);

        diffGrid.add(name,             0, row);
        diffGrid.add(value(before, changed), 1, row);
        diffGrid.add(value(after,  changed), 2, row);
        return changed ? 1 : 0;
    }

    private static Label value(String text, boolean changed) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(changed ? "diff-changed" : "diff-same");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
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
        c.setMinWidth(170);
        return c;
    }

    @FXML
    private void onClose() {
        ((Stage) closeButton.getScene().getWindow()).close();
    }
}
