package hsts.client.gui;

import hsts.common.entity.Principal;
import hsts.common.entity.Student;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.entity.Course;
import hsts.common.protocol.MenuContext;
import hsts.common.protocol.PendingCounts;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
 * <p>Every entry opens a screen. While the system was being built, entries whose
 * milestone had not arrived were shown greyed out with the milestone named; there
 * is nothing left to grey out, and a menu that talks about its own construction
 * has no place in front of a user.</p>
 *
 * <p><b>The menu is a convenience, not a security boundary.</b> Hiding a button
 * stops an honest user pressing the wrong thing; it stops nobody else. Every
 * request is authorised again on the server.</p>
 */
public class MainMenuController extends GUIScreen {

    private static final String REQ_LOGOUT = "logout";
    private static final String REQ_COUNTS  = "counts";
    private static final String REQ_CONTEXT = "context";

    @FXML private Label  nameLabel;
    @FXML private Label  roleLabel;
    @FXML private Label  contextLabel;
    @FXML private VBox   menuBox;
    @FXML private Button logoutButton;
    @FXML private Label  statusLabel;
    @FXML private Label  footerLabel;

    /** Which number from the reply belongs on which entry. Null means no badge. */
    @FunctionalInterface
    private interface Counter {
        int of(PendingCounts counts);
    }

    /**
     * One menu entry.
     *
     * @param text    what the button says
     * @param fxml    the screen it opens
     * @param counter which pending count to show on it, or null for no badge
     */
    private record MenuEntry(String text, String fxml, Counter counter) {
        MenuEntry(String text, String fxml) {
            this(text, fxml, null);
        }
    }

    /** The badge label on each entry that has one, so the counts can be applied. */
    private final java.util.Map<Counter, Label> badges = new java.util.LinkedHashMap<>();

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
            menuBox.getChildren().add(buildButton(entry));
        }

        // Nothing to explain any more. Hidden rather than blanked, so it does not
        // leave an empty line where a sentence used to be.
        footerLabel.setVisible(false);
        footerLabel.setManaged(false);

        clearMessage();

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(reason -> {
            logoutButton.setDisable(true);
            showError(reason);
        });

        // What is waiting for her, for the badges. Asked once here; asked again only
        // when a push says something that could change it has happened. No timer:
        // this must not become a request a second.
        askForCounts();
        // ...and who she is, in words. Asked once: it cannot change while she is
        // signed in, so no push re-asks for it.
        send(RequestType.MENU_CONTEXT, null, REQ_CONTEXT);
    }

    /**
     * One menu button, with room for an unread badge at its right-hand end.
     *
     * <p>The whole row is the button's <em>graphic</em> rather than its text, so the
     * badge sits inside the button and lights up with it on hover, and so a spacer
     * can push it to the far edge the way a phone does. A plain graphic beside the
     * text would sit immediately after the words, where a long entry and a short one
     * would put it in different places.</p>
     *
     * <p>The row's width follows the button's, which the surrounding {@code VBox}
     * stretches to fill - so the width comes from the parent and this cannot chase
     * its own tail.</p>
     */
    private Button buildButton(MenuEntry entry) {
        Button button = new Button();
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("menu-entry");

        Label caption = new Label(entry.text());
        // Never squeezed to an ellipsis, however narrow the window is made.
        caption.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(10, caption, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.prefWidthProperty().bind(button.widthProperty().subtract(GRAPHIC_INSET));

        if (entry.counter() != null) {
            Label badge = new Label();
            badge.getStyleClass().add("badge-unread");
            // Not managed while it is empty, so an entry with nothing waiting takes
            // exactly the height it always did.
            badge.setVisible(false);
            badge.setManaged(false);
            row.getChildren().add(badge);
            badges.put(entry.counter(), badge);
        }

        button.setGraphic(row);
        button.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        button.setOnAction(e -> switchTo(entry.fxml()));
        return button;
    }

    /** The button's own left and right padding plus its border, from hsts.css. */
    private static final int GRAPHIC_INSET = 32;

    /**
     * The line under her name, written from codes until the names arrive.
     *
     * <p>Replaced a moment later by {@link #describeContext(MenuContext)}, which has
     * the course <em>names</em>. This version is what she sees for the length of one
     * round trip, and saying "02" for a moment is better than saying nothing and
     * then jumping.</p>
     */
    private String describeContext(User user) {
        if (user instanceof SubjectCoordinator coordinator) {
            return "Coordinates subject " + coordinator.getCoordinatedSubjectCode()
                 + "   ·   teaches: " + join(coordinator.getTaughtCourseCodes());
        }
        if (user instanceof Teacher teacher) {
            return "Teaches: " + join(teacher.getTaughtCourseCodes());
        }
        if (user instanceof Student student) {
            return "Studies: " + join(student.getEnrolledCourseCodes());
        }
        if (user instanceof Principal) {
            return "Read-only access to all questions, exams and results.";
        }
        return "";
    }

    /**
     * The same line once the server has said what those codes are called.
     *
     * <p>A coordinator is told both things separately, because they are separate:
     * the subject she runs, and the classes she teaches herself. Those are
     * different lists and conflating them is what made "teaches course(s): 02"
     * unreadable in the first place.</p>
     *
     * <p>A coordinator with no classes of her own is told so in as many words. It
     * is a real state - she runs a subject without teaching in it - and leaving the
     * sentence to trail off would look like a fault.</p>
     */
    private String describeContext(MenuContext context) {
        StringBuilder line = new StringBuilder();

        if (context.getCoordinatedSubjectName() != null) {
            line.append("Coordinates ").append(context.getCoordinatedSubjectName())
                .append(" (").append(context.getCoordinatedSubjectCode()).append(")");
        }

        if (!context.getTaughtCourses().isEmpty()) {
            if (line.length() > 0) {
                line.append("   ·   ");
            }
            line.append("Teaches ").append(nameCourses(context.getTaughtCourses()));
        } else if (context.getCoordinatedSubjectName() != null) {
            line.append("   ·   teaches no courses of her own");
        }

        if (!context.getEnrolledCourses().isEmpty()) {
            if (line.length() > 0) {
                line.append("   ·   ");
            }
            line.append("Studies ").append(nameCourses(context.getEnrolledCourses()));
        }

        return line.length() == 0 ? contextLabel.getText() : line.toString();
    }

    /** "Algebra (02), Mechanics (03)" - the name first, the code for the paperwork. */
    private String nameCourses(List<Course> courses) {
        StringBuilder out = new StringBuilder();
        for (Course course : courses) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(course.getName()).append(" (").append(course.getCourseCode()).append(")");
        }
        return out.toString();
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
            entries.add(new MenuEntry("Question bank",           "/fxml/QuestionMgmt.fxml"));
            entries.add(new MenuEntry("Build an exam",           "/fxml/ExamBuilder.fxml"));
            // Only for somebody who actually teaches. Releasing is done by the
            // teacher OF THE COURSE (SUC-6), so for a coordinator with no classes of
            // her own the screen can only ever be empty - and an entry that opens
            // onto nothing is a question the user has to answer for herself.
            if (!((Teacher) user).getTaughtCourseCodes().isEmpty()) {
                entries.add(new MenuEntry("Release an exam",     "/fxml/ExamRelease.fxml",
                                          PendingCounts::getExamsNewlyApproved));
            }
            entries.add(new MenuEntry("Exams running now",       "/fxml/TeacherLiveExam.fxml"));
            entries.add(new MenuEntry("Mark and approve grades", "/fxml/Grading.fxml",
                                      PendingCounts::getPapersToApprove));
            entries.add(new MenuEntry("Results and histogram",   "/fxml/TeacherReports.fxml"));
            entries.add(new MenuEntry("My reports",              "/fxml/Reports.fxml"));
            entries.add(new MenuEntry("Course study bot",        "/fxml/BotManagement.fxml"));
        }

        if (user instanceof SubjectCoordinator) {
            entries.add(new MenuEntry("Approve or reject exams", "/fxml/ExamApproval.fxml",
                                      PendingCounts::getExamsToApprove));
        }

        if (user instanceof Student) {
            entries.add(new MenuEntry("Take an exam",            "/fxml/TakeExam.fxml",
                                      PendingCounts::getExamsToSit));
            entries.add(new MenuEntry("My grades",               "/fxml/StudentResults.fxml",
                                      PendingCounts::getNewResults));
            // SUC-14 and SUC-15 are one screen for a student: asking and reading
            // back what she asked are the same activity, and the history is on it.
            entries.add(new MenuEntry("Course study bot",        "/fxml/AskBot.fxml"));
        }

        if (user instanceof Principal) {
            // Requirement 62 names questions, exams and results together, and they
            // are three tabs of one screen rather than three near-identical windows.
            entries.add(new MenuEntry("Browse questions, exams and results", "/fxml/PrincipalBrowse.fxml"));
            entries.add(new MenuEntry("Statistical reports",     "/fxml/Reports.fxml"));
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
            return;
        }
        if (REQ_CONTEXT.equals(response.getRequestId())) {
            if (response.isOk() && response.getPayload() instanceof MenuContext context) {
                contextLabel.setText(describeContext(context));
                fitToContent();
            }
            return;
        }
        if (REQ_COUNTS.equals(response.getRequestId())) {
            // A failure here is deliberately silent. Nothing is broken from her point
            // of view - the menu works - and an error line about counting would be
            // the only thing on a screen she has just arrived at.
            if (response.isOk() && response.getPayload() instanceof PendingCounts counts) {
                applyCounts(counts);
            }
        }
    }

    /**
     * Which pushes mean a badge could have changed.
     *
     * <p>An allowlist rather than "refresh on anything": the clock pushes a tick
     * every second while an exam is being sat, and a menu that answered those with a
     * request each would be polling by another name.</p>
     */
    private static final java.util.Set<PushType> AFFECTS_BADGES = java.util.EnumSet.of(
            PushType.EXAM_AWAITING_APPROVAL,   // an exam arrived for the coordinator
            PushType.EXAM_APPROVED,
            PushType.EXAM_REJECTED,
            PushType.GRADE_APPROVED,           // a mark reached a student
            PushType.RESULTS_CHANGED,          // marking or publishing moved
            PushType.EXAM_LIVE_STATUS,         // a paper was handed in on her sitting
            PushType.PENDING_COUNTS_CHANGED);  // an exam was given to her class

    @Override
    protected void onPush(PushEvent event) {
        super.onPush(event);
        if (AFFECTS_BADGES.contains(event.getType())) {
            askForCounts();
        }
    }

    private void askForCounts() {
        if (badges.isEmpty()) {
            return;                    // this role has no badged entries - the principal
        }
        send(RequestType.PENDING_COUNTS, null, REQ_COUNTS);
    }

    /** Puts the numbers on the badges, hiding any that has nothing waiting. */
    private void applyCounts(PendingCounts counts) {
        for (java.util.Map.Entry<Counter, Label> entry : badges.entrySet()) {
            int waiting = entry.getKey().of(counts);
            Label badge = entry.getValue();
            badge.setText(String.valueOf(waiting));
            badge.setVisible(waiting > 0);
            badge.setManaged(waiting > 0);
        }
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            // Same reasoning as above: the menu itself is unaffected.
            System.out.println("Could not ask for the pending counts: " + e.getMessage());
        }
    }

    private void backToLogin() {
        controller.clearCurrentUser();
        switchTo("/fxml/Login.fxml");
    }
}
