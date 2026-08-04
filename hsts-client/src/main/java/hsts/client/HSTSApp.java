package hsts.client;

import hsts.client.net.ClientController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * The JavaFX application of the client side.
 *
 * <p>Started by {@link ClientLauncher} - see the comment there for why it is not
 * the entry point itself.</p>
 *
 * <p>The first window asks for the server's address and port, as required by
 * מתווה item 15, "GUI לאתחול הקשר". Nothing else can happen until a connection
 * exists, because the client holds no data of its own - everything it displays
 * comes from the server.</p>
 */
public class HSTSApp extends Application {

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws IOException {
        primaryStage = stage;

        stage.setTitle("HSTS Client - Group 1");
        stage.setScene(loadScene("/fxml/ClientStartup.fxml"));
        stage.setResizable(false);
        stage.show();
    }

    /**
     * Loads an FXML file from <em>inside the jar</em>.
     *
     * <p>The leading slash makes this an absolute classpath lookup, which is
     * where Maven puts everything under {@code src/main/resources}. A relative
     * path would resolve against this class's package - it would work when run
     * from an IDE and fail from the packaged jar, which is precisely the sort of
     * difference milestone 1 exists to find.</p>
     */
    public static Scene loadScene(String fxmlPath) throws IOException {
        URL url = HSTSApp.class.getResource(fxmlPath);
        if (url == null) {
            throw new IOException("FXML not found on the classpath: " + fxmlPath);
        }
        Scene scene = new Scene(scrollable(new FXMLLoader(url).load()));
        applyStylesheet(scene);
        return scene;
    }

    /**
     * Wraps a screen so it can never be cut off by a window that is too small.
     *
     * <h2>Why</h2>
     *
     * <p>The screens ask for the width they need - the marking screen wants three
     * columns and 1280 points. Make the window narrower than that, by dragging it or
     * because the display is smaller, and JavaFX squeezes the columns down to their
     * minimum and then simply <b>clips</b> whatever still does not fit. Reported from
     * the screen: the third card of the release form was sliced down its middle by
     * the window edge.</p>
     *
     * <p>Inside a scroll pane the same window shows a scroll bar instead. Nothing is
     * lost - it is reachable, which is the whole difference between "small" and
     * "broken".</p>
     *
     * <h2>Why here and not in seventeen FXML files</h2>
     *
     * <p>Every screen goes through this method. Doing it in each file would mean
     * seventeen chances to forget, and the eighteenth screen would be the one that
     * arrives clipped at the demo.</p>
     *
     * <p>{@code fitToWidth} and {@code fitToHeight} make the content fill the window
     * whenever the window is the bigger of the two, so a screen with room to spare
     * still stretches exactly as it did before. The scroll bars appear only when they
     * are the only way to see everything.</p>
     */
    private static javafx.scene.Parent scrollable(javafx.scene.Parent screen) {
        neverSqueezeText(screen);
        javafx.scene.control.ScrollPane frame = new javafx.scene.control.ScrollPane(screen);
        frame.setFitToWidth(true);
        frame.setFitToHeight(true);
        frame.setPannable(false);
        // No border and no background of its own: this is a container, not a panel,
        // and it must not draw a line around every screen in the system.
        frame.getStyleClass().add("screen-frame");
        return frame;
    }

    /**
     * Stops any wrapping text being squeezed shorter than it needs to be.
     *
     * <h2>The fault</h2>
     *
     * <p>Reported from the screen, with pictures: "Tick the questions you want.
     * Filter first if the bank is long -..." and "Only you can see this. Your
     * teacher sees ho...". Both of those labels wrap. Wrapping solves running off
     * the <em>side</em>; it does nothing about running out of <em>height</em>.
     * When a label that needs two lines is given the height of one - because it
     * sits in a box with something growing beside it, and boxes shrink their
     * children to their minimum before they give up - JavaFX draws one line and an
     * ellipsis. The sentence is simply gone.</p>
     *
     * <h2>The fix</h2>
     *
     * <p>{@code USE_PREF_SIZE} as a minimum height means "never shorter than the
     * text needs", and because the preferred height of a wrapping label is worked
     * out from the width it has been given, it is right at every window size
     * rather than at the one it was tested at. What has to give instead is the
     * window, which is inside a scroll frame precisely so that it can.</p>
     *
     * <p>Done here, where every screen is loaded, rather than as an attribute in
     * eighteen FXML files and every one written after them.</p>
     */
    private static void neverSqueezeText(javafx.scene.Node node) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.isWrapText()) {
            labeled.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        if (node instanceof javafx.scene.Parent parent) {
            // getChildrenUnmodifiable, not getChildren: a control's skin builds its
            // own children later, and they are not ours to change.
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                neverSqueezeText(child);
            }
        }
    }

    /**
     * Attaches the shared stylesheet to a scene.
     *
     * <p>Every window goes through here, so the whole system looks like one
     * product instead of a set of separately-built screens. The stylesheet lives
     * in {@code hsts-common}, which is packaged into both jars, so the server's
     * windows match the client's.</p>
     *
     * <p>A missing stylesheet is not fatal - the screen still works, it just
     * looks plain - so this warns rather than throwing.</p>
     */
    public static void applyStylesheet(Scene scene) {
        URL css = HSTSApp.class.getResource("/css/hsts.css");
        if (css == null) {
            System.err.println("Stylesheet /css/hsts.css not found - screens will look unstyled.");
            return;
        }
        scene.getStylesheets().add(css.toExternalForm());
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    /** Closes the socket cleanly so the server sees a tidy disconnect. */
    @Override
    public void stop() {
        ClientController.getInstance().disconnect();
    }
}
