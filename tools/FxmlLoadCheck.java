import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.stage.Stage;

import java.net.URL;

/**
 * Loads every FXML screen in both jars and reports any that fail.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Java compiler cannot see inside an FXML file. A broken one builds
 * perfectly and only fails when somebody clicks the button that opens it - which,
 * left to chance, means during the demo.</p>
 *
 * <p>It was written after exactly that happened: two screens contained a comment
 * written as</p>
 *
 * <pre>    &lt;!-- ---------- left: the bank ---------- --&gt;</pre>
 *
 * <p>XML forbids {@code --} inside a comment, so both files were invalid and the
 * question bank would not open. The build was clean and every other test passed.
 * Java {@code //} comments and CSS {@code /* *}{@code /} comments have no such
 * rule, which is why the habit slipped in unnoticed.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>From the project root, after {@code mvn package}:</p>
 *
 * <pre>
 * javac -cp "hsts-client/target/G1_Client.jar;hsts-server/target/G1_Server.jar" ^
 *       -d tools tools/FxmlLoadCheck.java
 *
 * java -cp "hsts-client/target/G1_Client.jar;hsts-server/target/G1_Server.jar;tools" ^
 *      FxmlLoadCheck
 * </pre>
 *
 * <p>Exit code 0 means every screen loaded. Use {@code :} instead of {@code ;} as
 * the classpath separator on macOS or Linux.</p>
 *
 * <p><b>Note the shape of this class.</b> {@code main} does not extend
 * {@code Application}; the {@code Application} subclass is nested inside. Naming
 * an {@code Application} subclass as the entry point makes the JVM demand JavaFX
 * on the module path and refuse to start from a classpath run - the same trap
 * {@code ClientLauncher} and {@code ServerLauncher} exist to avoid.</p>
 */
public class FxmlLoadCheck {

    /** Every screen in the system. Add new ones here as they are built. */
    private static final String[] SCREENS = {
        "/fxml/ClientStartup.fxml",
        "/fxml/Login.fxml",
        "/fxml/MainMenu.fxml",
        "/fxml/QuestionMgmt.fxml",
        "/fxml/VersionHistory.fxml",
        "/fxml/ExamBuilder.fxml",
        "/fxml/ExamApproval.fxml",
        "/fxml/ExamRelease.fxml",
        "/fxml/TakeExam.fxml",
        "/fxml/TeacherLiveExam.fxml",
        "/fxml/Grading.fxml",
        "/fxml/StudentResults.fxml",
        "/fxml/ServerStartup.fxml",
        "/fxml/ServerConsole.fxml",
    };

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    public static class App extends Application {
        @Override
        public void start(Stage stage) {
            int ok = 0;
            int bad = 0;

            for (String path : SCREENS) {
                URL url = FxmlLoadCheck.class.getResource(path);
                if (url == null) {
                    System.out.println("[MISSING] " + path);
                    bad++;
                    continue;
                }
                try {
                    new FXMLLoader(url).load();
                    System.out.println("[OK]      " + path);
                    ok++;
                } catch (Throwable t) {
                    bad++;
                    Throwable root = t;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    System.out.println("[FAILED]  " + path);
                    System.out.println("          " + root.getClass().getName()
                                     + ": " + firstLine(root.getMessage()));
                }
            }

            System.out.println();
            System.out.println("==== loaded " + ok + ", failed " + bad + " ====");
            Platform.exit();
            System.exit(bad > 0 ? 1 : 0);
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "(no message)";
        }
        int newline = s.indexOf('\n');
        return newline < 0 ? s : s.substring(0, newline);
    }
}
