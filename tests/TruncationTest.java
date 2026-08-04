import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks for text that is cut off on any screen, at any size it can be opened at.
 *
 * <h2>Why by measurement rather than by eye</h2>
 *
 * <p>Reported from the screen: the release form showed a button reading "..." where
 * "Now" should have been. Looking for the rest by eye means opening seventeen
 * screens and reading every control on each - and the one that is wrong will be on
 * the screen nobody happened to resize.</p>
 *
 * <p>So each screen is laid out at three widths - its preferred size, its declared
 * minimum, and the size the display would clamp it to - and every {@code Labeled}
 * node that is not wrapping is measured: if the width it was given is less than the
 * width its text needs, JavaFX is showing an ellipsis and a word is missing.</p>
 *
 * <p>Wrapping labels are skipped on purpose. Their preferred width is the whole
 * sentence on one line, which is exactly what they are not doing - they grow
 * downwards instead, and the window follows.</p>
 *
 * <p>Same shape as {@code FxmlLoadTest}: {@code main} does not extend
 * {@code Application}, or the JVM demands JavaFX on the module path.</p>
 */
public class TruncationTest {

    private static final String[] SCREENS = {
        "/fxml/ClientStartup.fxml", "/fxml/Login.fxml", "/fxml/MainMenu.fxml", "/fxml/Todo.fxml",
        "/fxml/QuestionMgmt.fxml", "/fxml/VersionHistory.fxml", "/fxml/ExamBuilder.fxml",
        "/fxml/ExamApproval.fxml", "/fxml/ExamRelease.fxml", "/fxml/TakeExam.fxml",
        "/fxml/TeacherLiveExam.fxml", "/fxml/Grading.fxml", "/fxml/StudentResults.fxml",
        "/fxml/TeacherReports.fxml", "/fxml/PrincipalBrowse.fxml", "/fxml/Reports.fxml",
        "/fxml/BotManagement.fxml", "/fxml/AskBot.fxml", "/fxml/ExamVersionHistory.fxml",
    };

    /** A small laptop after 150% scaling - the case that produced the report. */
    private static final double SMALL_WIDTH = 1280;
    private static final double SMALL_HEIGHT = 720;

    private static int checked = 0, cut = 0;

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    public static class App extends Application {
        @Override
        public void start(Stage stage) {
            Rectangle2D area = Screen.getPrimary().getVisualBounds();
            System.out.println("This display's working area: "
                    + Math.round(area.getWidth()) + " x " + Math.round(area.getHeight()));

            for (String path : SCREENS) {
                URL url = TruncationTest.class.getResource(path);
                if (url == null) {
                    System.out.println("[MISSING] " + path);
                    cut++;
                    continue;
                }
                try {
                    List<String> problems = new ArrayList<>();
                    problems.addAll(measure(stage, url, path, 0, 0));            // preferred
                    problems.addAll(measure(stage, url, path,
                            SMALL_WIDTH, SMALL_HEIGHT));                          // clamped
                    problems.addAll(measure(stage, url, path, 960, 1030));        // the reported shape
                    problems.addAll(measure(stage, url, path, 700, 500));         // smaller still
                    problems.addAll(measureRevealed(stage, path));                 // every pane
                    problems.addAll(measureFilled(stage, path));                   // with real text
                    checked++;
                    if (problems.isEmpty()) {
                        System.out.println("[OK]      " + path);
                    } else {
                        cut++;
                        System.out.println("[CUT]     " + path);
                        problems.stream().distinct().limit(8)
                                .forEach(p -> System.out.println("            " + p));
                    }
                } catch (Throwable t) {
                    cut++;
                    System.out.println("[FAILED]  " + path + "  " + t);
                }
            }

            System.out.println();
            System.out.println("==== screens checked " + checked + ", with cut-off text " + cut + " ====");
            Platform.exit();
            System.exit(cut > 0 ? 1 : 0);
        }
    }

    /**
     * Lays one screen out at a size and returns any text being cut off.
     *
     * <p>Loaded through {@code HSTSApp.loadScene}, which is what the running client
     * uses - so this measures the screen as it really is, inside the scroll frame
     * that stops a small window slicing content off.</p>
     */
    private static List<String> measure(Stage stage, URL url, String path,
                                        double width, double height) throws Exception {
        Scene scene = hsts.client.HSTSApp.loadScene(path);
        stage.setScene(scene);
        if (width > 0) {
            stage.setWidth(width);
            stage.setHeight(height);
        } else {
            stage.sizeToScene();
        }
        stage.show();
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        List<String> problems = new ArrayList<>();
        walk(scene.getRoot(), problems);

        // The screen itself must still be at least as wide as it asked to be. If it
        // is not, the frame is squeezing it and the content really is being cut - a
        // scroll bar is the point of this, not a smaller layout.
        Parent inner = innerScreen(scene.getRoot());
        if (inner instanceof javafx.scene.layout.Region region
                && region.getMinWidth() > 0
                && region.getWidth() + 1 < region.getMinWidth()) {
            problems.add("the screen itself was squeezed to " + Math.round(region.getWidth())
                    + " below its minimum of " + Math.round(region.getMinWidth()));
        }
        if (!(scene.getRoot() instanceof javafx.scene.control.ScrollPane)) {
            problems.add("this screen is NOT inside a scroll frame, so a small window "
                    + "would cut it off");
        }
        return problems;
    }

    /** The screen inside the frame. */
    private static Parent innerScreen(Parent root) {
        if (root instanceof javafx.scene.control.ScrollPane frame
                && frame.getContent() instanceof Parent content) {
            return content;
        }
        return root;
    }

    /**
     * The same measurement with every hidden pane revealed.
     *
     * <p>Several screens keep panes hidden until they are needed - the automatic
     * exam builder, the take-exam steps - and a button inside one of those is
     * invisible to the pass above while being perfectly capable of arriving cut off
     * the moment the user clicks. Showing them all at once crowds the window more
     * than any real state does, so this is the strictest of the three passes.</p>
     */
    private static List<String> measureRevealed(Stage stage, String path) throws Exception {
        Scene scene = hsts.client.HSTSApp.loadScene(path);
        stage.setScene(scene);
        stage.setWidth(SMALL_WIDTH);
        stage.setHeight(SMALL_HEIGHT);
        stage.show();
        reveal(scene.getRoot());
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        List<String> problems = new ArrayList<>();
        walk(scene.getRoot(), problems);
        return problems;
    }

    /**
     * The same measurement with something written in every empty label.
     *
     * <p>Half the labels on these screens are blank in the FXML and filled in by
     * the controller when the data arrives - the points total, the count beside a
     * list, the name of the exam being marked. Measuring them empty measures
     * nothing, and one of the cut-off labels a user photographed was exactly this
     * kind: a points total reading "0 ...".</p>
     *
     * <p>So each empty label is given a sentence about as long as the real ones
     * and the screen is measured again. There is nothing behind the screen to
     * supply the real text - that would need a server, a login and a course - and
     * a sentence of the right length finds the same fault.</p>
     */
    private static List<String> measureFilled(Stage stage, String path) throws Exception {
        Scene scene = hsts.client.HSTSApp.loadScene(path);
        stage.setScene(scene);
        stage.setWidth(SMALL_WIDTH);
        stage.setHeight(SMALL_HEIGHT);
        stage.show();
        fill(scene.getRoot());
        scene.getRoot().applyCss();
        scene.getRoot().layout();

        List<String> problems = new ArrayList<>();
        walk(scene.getRoot(), problems);
        return problems;
    }

    /** About as long as "84 of 100 marks allocated - 16 still to give out". */
    private static final String SAMPLE =
            "84 of 100 marks allocated - 16 still to give out";

    private static void fill(Node node) {
        if (node instanceof Labeled labeled
                && (labeled.getText() == null || labeled.getText().isBlank())
                && !labeled.textProperty().isBound()
                && !(node instanceof javafx.scene.control.ButtonBase)) {
            labeled.setText(SAMPLE);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                fill(child);
            }
        }
    }

    private static void reveal(Node node) {
        // Only what the FXML owns. Inside a control's skin there are Paths, Texts
        // and Rectangles whose visibility is bound to the control's state, and
        // setting those throws "A bound value cannot be set".
        if (!node.visibleProperty().isBound()) {
            node.setVisible(true);
        }
        if (!node.managedProperty().isBound()) {
            node.setManaged(true);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                reveal(child);
            }
        }
    }

    private static void walk(Node node, List<String> problems) {
        if (node instanceof Labeled labeled && labeled.isWrapText()) {
            // A wrapping label cannot run off the side; it runs off the BOTTOM.
            // Given the height of one line when its text needs two, JavaFX draws
            // one line and an ellipsis, and the rest of the sentence is gone.
            //
            // This is the fault the first version of this harness could not see:
            // it skipped every wrapping label, and two of them were being cut on
            // the screens a user photographed. Measured at the width the label
            // actually has, so the answer is right at this window size rather
            // than at some other one.
            String text = labeled.getText();
            double width = labeled.getWidth();
            if (text != null && !text.isBlank() && isShowing(labeled)
                    && width > 0 && labeled.getHeight() > 0) {
                double needed = labeled.prefHeight(width);
                double given = labeled.getHeight();
                if (needed - given > 1.0) {
                    problems.add("\"" + shorten(text) + "\" wraps to "
                            + Math.round(needed) + " high and has " + Math.round(given));
                }
            }
        }
        if (node instanceof Labeled labeled && !labeled.isWrapText()) {
            String text = labeled.getText();
            if (text != null && !text.isBlank() && isShowing(labeled)) {
                // Measured from the TEXT and the font, not from prefWidth. A
                // control with an explicit prefWidth reports that width as its
                // preference however long the words are - so comparing the two
                // would call a button pinned to 30 pixels perfectly happy while it
                // showed "...". That is exactly the fault this exists to find.
                double needed = naturalWidth(labeled);
                double given = labeled.getWidth();
                if (given > 0 && needed - given > 1.0) {
                    problems.add("\"" + shorten(text) + "\" needs "
                            + Math.round(needed) + " and has " + Math.round(given));
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, problems);
            }
        }
    }

    /**
     * How wide this control has to be for all of its text to show.
     *
     * <p>The text laid out in the control's own font, plus its padding and border,
     * plus a graphic and the gap before it where there is one.</p>
     */
    private static double naturalWidth(Labeled labeled) {
        javafx.scene.text.Text measure = new javafx.scene.text.Text(labeled.getText());
        measure.setFont(labeled.getFont());
        double width = measure.getLayoutBounds().getWidth();

        javafx.geometry.Insets in = labeled.getInsets();
        width += in.getLeft() + in.getRight();

        Node graphic = labeled.getGraphic();
        if (graphic != null && graphic.isManaged()) {
            width += graphic.getLayoutBounds().getWidth() + labeled.getGraphicTextGap();
        }
        return width;
    }

    /**
     * True when this node and every ancestor is visible and managed.
     *
     * <p>{@code isVisible()} on its own is not enough: the take-exam screen holds
     * four panes and shows one at a time, and the buttons inside the hidden three
     * are laid out at nothing. They measured as cut off while being perfectly fine
     * on the pane the student can actually see.</p>
     */
    private static boolean isShowing(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible() || !n.isManaged()) {
                return false;
            }
        }
        return true;
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "...";
    }
}
