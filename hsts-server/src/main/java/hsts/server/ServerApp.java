package hsts.server;

import hsts.server.dao.DBController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
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
        return new Scene(new FXMLLoader(url).load());
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
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
