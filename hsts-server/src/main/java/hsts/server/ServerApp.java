package hsts.server;

import hsts.server.dao.DBController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/**
 * The JavaFX application of the server side.
 *
 * <p>Started by {@link ServerLauncher} - see the comment there for why it is not
 * the entry point itself.</p>
 *
 * <p>The first thing the user sees is the startup window asking for the
 * listening port and the database details. That window is required by
 * מתווה item 15, "GUI לאתחול הקשר".</p>
 */
public class ServerApp extends Application {

    /** Kept so any screen can swap the scene without passing the stage around. */
    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws IOException {
        primaryStage = stage;

        stage.setTitle("HSTS Server - Group 1");
        stage.setScene(loadScene("/fxml/ServerStartup.fxml"));
        stage.setResizable(false);
        stage.show();
    }

    /**
     * Loads an FXML file from <em>inside the jar</em>.
     *
     * <p>The leading slash matters: it makes the lookup absolute from the root of
     * the classpath, which is where Maven puts everything under
     * {@code src/main/resources}. A relative path would be resolved against this
     * class's package and would work when run from the IDE but fail from the
     * packaged jar - exactly the kind of difference milestone 1 exists to catch.</p>
     */
    public static Scene loadScene(String fxmlPath) throws IOException {
        URL url = ServerApp.class.getResource(fxmlPath);
        if (url == null) {
            throw new IOException("FXML not found on the classpath: " + fxmlPath);
        }
        Scene scene = new Scene(new FXMLLoader(url).load());

        // The same stylesheet the client uses. It lives in hsts-common, which is
        // packaged into both jars, so the two programs look like one system.
        URL css = ServerApp.class.getResource("/css/hsts.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        return scene;
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * Resizes the window so all of its content is visible.
     *
     * <p>A window is sized once, when first shown, to fit the text it had then.
     * Status labels wrap, so a longer message becomes two or three lines - and
     * without this the extra lines are cut off at the bottom edge. The database
     * error messages here are long enough for that to matter.</p>
     *
     * <p>{@code applyCss} then {@code layout} force the wrapping to be
     * recalculated before measuring; without them {@code sizeToScene} measures
     * the <em>previous</em> message and stays one step behind.</p>
     */
    public static void fitToContent() {
        Platform.runLater(() -> {
            if (primaryStage == null || primaryStage.getScene() == null) {
                return;
            }
            Parent root = primaryStage.getScene().getRoot();
            root.applyCss();
            root.layout();
            primaryStage.sizeToScene();
        });
    }

    /**
     * Closes the listening socket and the database connection on the way out, so
     * that stopping the server does not leave a port or a connection dangling.
     */
    @Override
    public void stop() {
        HSTSServer.getInstance().shutdown();
        DBController.getInstance().disconnect();
    }
}
