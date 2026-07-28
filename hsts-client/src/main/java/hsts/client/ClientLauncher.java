package hsts.client;

import javafx.application.Application;

/**
 * Entry point of {@code G1_Client.jar}.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>The obvious design would be to put {@code main} inside {@link HSTSApp} and
 * name that as the jar's Main-Class. It does not work, and the error message
 * sends you looking in the wrong place entirely.</p>
 *
 * <p>If the class named in the manifest <em>extends
 * {@code javafx.application.Application}</em>, the JVM insists on finding the
 * JavaFX <em>modules</em> on the module path. A shaded fat jar has no module
 * path - everything is flattened onto the classpath - so it stops with:</p>
 *
 * <pre>    Error: JavaFX runtime components are missing, and are required to run this application</pre>
 *
 * <p>JavaFX is not missing. It is inside the very jar being started. The check
 * simply looks in a place the fat jar does not use.</p>
 *
 * <p>That check applies only to the class in the manifest. Naming a plain class
 * that does not extend {@code Application}, and letting it call {@code launch}
 * on the real one, sidesteps it completely.</p>
 *
 * <p><b>Do not merge this class into {@link HSTSApp}.</b> The jar will stop
 * running and nothing in the error will tell you why.</p>
 */
public final class ClientLauncher {

    private ClientLauncher() {
        // no instances - this class is only an entry point
    }

    public static void main(String[] args) {
        Application.launch(HSTSApp.class, args);
    }
}
