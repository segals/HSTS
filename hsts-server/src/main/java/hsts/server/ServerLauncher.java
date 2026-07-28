package hsts.server;

import javafx.application.Application;

/**
 * Entry point of {@code G1_Server.jar}.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>It would look more natural to put {@code main} straight into
 * {@link ServerApp}, the class that extends {@code Application}, and name that
 * as the jar's Main-Class. That does not work, and the failure is confusing.</p>
 *
 * <p>When the JVM is asked to start a class that <em>extends
 * {@code javafx.application.Application}</em>, it checks that the JavaFX
 * <em>modules</em> are present on the module path. In a shaded fat jar they are
 * not - everything has been flattened onto the plain classpath. The JVM then
 * refuses to start and prints:</p>
 *
 * <pre>    Error: JavaFX runtime components are missing, and are required to run this application</pre>
 *
 * <p>...which sounds like JavaFX is absent, when in fact it is sitting right
 * there inside the same jar.</p>
 *
 * <p>The check is only applied to the class named in the manifest. So the fix is
 * to name a class that does <em>not</em> extend {@code Application} - this one -
 * and have it call {@code launch} on the real one. JavaFX then starts normally
 * from the classpath.</p>
 *
 * <p><b>Do not merge this class into {@link ServerApp}.</b> The jar will stop
 * running and the error message will not explain why.</p>
 */
public final class ServerLauncher {

    private ServerLauncher() {
        // no instances - this class is only an entry point
    }

    public static void main(String[] args) {
        Application.launch(ServerApp.class, args);
    }
}
