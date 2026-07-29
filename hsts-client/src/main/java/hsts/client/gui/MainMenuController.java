package hsts.client.gui;

import hsts.common.entity.Principal;
import hsts.common.entity.Student;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * מתווה scenario 1: the menu shown after logging in, matched to the user's role.
 *
 * <p>The menu is built from the {@link User} subclass the server sent back. A
 * {@link SubjectCoordinator} arrives as a coordinator object, so it gets the
 * teacher's entries <em>and</em> the approval entry, without this class testing
 * the role by hand.</p>
 *
 * <p>Entries whose milestone has not arrived yet are shown but disabled, with the
 * milestone named. Hiding them would make the menu look finished when it is not,
 * and this way the shape of the finished system is visible from the start.</p>
 *
 * <p><b>The menu is a convenience, not a security boundary.</b> Hiding a button
 * stops an honest user pressing the wrong thing; it stops nobody else. Every
 * request is authorised again on the server.</p>
 */
public class MainMenuController extends GUIScreen {

    private static final String REQ_LOGOUT = "logout";

    @FXML private Label  nameLabel;
    @FXML private Label  roleLabel;
    @FXML private Label  contextLabel;
    @FXML private VBox   menuBox;
    @FXML private Button logoutButton;
    @FXML private Label  statusLabel;
    @FXML private Label  footerLabel;

    /**
     * One menu entry.
     *
     * @param text      what the button says
     * @param milestone which milestone delivers it, shown while it is not ready
     * @param fxml      the screen to open, or null if it is not built yet
     */
    private record MenuEntry(String text, String milestone, String fxml) {
        boolean ready() {
            return fxml != null;
        }
    }

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);

        User user = controller.getCurrentUser();
        if (user == null) {
            nameLabel.setText("Not signed in");
            return;
        }

        nameLabel.setText(user.getFullName());
        roleLabel.setText(user.getRole().getDisplayName() + "   ·   ID " + user.getUserId());
        contextLabel.setText(describeContext(user));

        for (MenuEntry entry : menuFor(user)) {
            Button button = new Button(entry.ready()
                    ? entry.text()
                    : entry.text() + "   —   " + entry.milestone());
            button.setMaxWidth(Double.MAX_VALUE);
            button.setDisable(!entry.ready());
            button.getStyleClass().add("menu-entry");
            if (entry.ready()) {
                button.setOnAction(e -> switchTo(entry.fxml(), true));
            }
            menuBox.getChildren().add(button);
        }

        footerLabel.setText(
                "Greyed-out entries are not built yet. Milestone 2 delivers login, "
              + "roles and this menu; the features arrive in the milestones shown.");

        clearMessage();

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(reason -> {
            logoutButton.setDisable(true);
            showError(reason);
        });
    }

    /** A line of context that proves the server sent this user's real associations. */
    private String describeContext(User user) {
        if (user instanceof SubjectCoordinator coordinator) {
            return "Coordinates subject " + coordinator.getCoordinatedSubjectCode()
                 + "   ·   teaches course(s): " + join(coordinator.getTaughtCourseCodes());
        }
        if (user instanceof Teacher teacher) {
            return "Teaches course(s): " + join(teacher.getTaughtCourseCodes());
        }
        if (user instanceof Student student) {
            return "Enrolled in course(s): " + join(student.getEnrolledCourseCodes());
        }
        if (user instanceof Principal) {
            return "Read-only access to all questions, exams and results.";
        }
        return "";
    }

    private String join(List<String> codes) {
        return (codes == null || codes.isEmpty()) ? "none" : String.join(", ", codes);
    }

    /**
     * The entries for one role.
     *
     * <p>Traced straight to the מתווה scenarios so the menu can be checked against
     * the acceptance document rather than against taste.</p>
     */
    private List<MenuEntry> menuFor(User user) {
        List<MenuEntry> entries = new ArrayList<>();

        if (user instanceof Teacher) {                       // also covers coordinators
            entries.add(new MenuEntry("Question bank",           "milestone 3",  "/fxml/QuestionMgmt.fxml"));
            entries.add(new MenuEntry("Build an exam",           "milestone 4",  "/fxml/ExamBuilder.fxml"));
            entries.add(new MenuEntry("Release an exam",         "milestone 6",  "/fxml/ExamRelease.fxml"));
            entries.add(new MenuEntry("Exams running now",       "milestone 8",  "/fxml/TeacherLiveExam.fxml"));
            entries.add(new MenuEntry("Mark and approve grades", "milestone 9",  null));
            entries.add(new MenuEntry("Results and histogram",   "milestone 11", null));
            entries.add(new MenuEntry("My reports",              "milestone 13", null));
            entries.add(new MenuEntry("Course study bot",        "milestone 14", null));
        }

        if (user instanceof SubjectCoordinator) {
            entries.add(new MenuEntry("Approve or reject exams", "milestone 5",
                                      "/fxml/ExamApproval.fxml"));
        }

        if (user instanceof Student) {
            entries.add(new MenuEntry("Take an exam",            "milestone 7",  "/fxml/TakeExam.fxml"));
            entries.add(new MenuEntry("My grades",               "milestone 10", null));
            entries.add(new MenuEntry("Ask the course bot",      "milestone 14", null));
            entries.add(new MenuEntry("My bot history",          "milestone 14", null));
        }

        if (user instanceof Principal) {
            entries.add(new MenuEntry("Browse questions",        "milestone 12", null));
            entries.add(new MenuEntry("Browse exams",            "milestone 12", null));
            entries.add(new MenuEntry("Browse results",          "milestone 12", null));
            entries.add(new MenuEntry("Statistical reports",     "milestone 13", null));
        }

        return entries;
    }

    @FXML
    private void onLogout() {
        logoutButton.setDisable(true);
        showMessage("Signing out...");
        try {
            controller.send(new Request(RequestType.LOGOUT, null, REQ_LOGOUT));
        } catch (Exception e) {
            // Even if the message cannot be sent, return to the login screen.
            // The server clears the session when the connection drops anyway.
            backToLogin();
        }
    }

    private void onServerResponse(Response response) {
        if (REQ_LOGOUT.equals(response.getRequestId())) {
            backToLogin();
        }
    }

    private void backToLogin() {
        controller.clearCurrentUser();
        switchTo("/fxml/Login.fxml", false);
    }
}
