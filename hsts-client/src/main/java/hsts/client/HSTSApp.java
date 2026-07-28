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
        return new Scene(new FXMLLoader(url).load());
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
