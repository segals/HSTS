import hsts.client.net.ClientController;
import hsts.common.entity.Principal;
import hsts.common.entity.Student;
import hsts.common.entity.SubjectCoordinator;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.entity.Course;
import hsts.common.protocol.MenuContext;
import hsts.common.protocol.PendingCounts;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The badges on the menu, as actually laid out by JavaFX.
 *
 * <p>{@code BadgeTest} proves the numbers are right. This proves they reach the
 * screen: that the badge exists on the entries that should have one and on no
 * others, that it is invisible while nothing is waiting, that it shows the number
 * when something is, and that it sits at the right-hand end of its button rather
 * than somewhere that depends on how long the entry's text happens to be.</p>
 *
 * <p>No server: the menu is given a user directly and the counts are applied
 * directly, because what is under test here is the layout, not the round trip.</p>
 *
 * <p>Same shape as {@code FxmlLoadTest} - {@code main} does not extend
 * {@code Application}, or the JVM demands JavaFX on the module path.</p>
 */
public class MenuBadgeTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }

    public static class App extends Application {
        @Override
        public void start(Stage stage) {
            try {
                run(stage);
            } catch (Throwable t) {
                failed++;
                System.out.println("   [FAIL] harness threw:");
                t.printStackTrace(System.out);
            }
            System.out.println();
            System.out.println("==== passed " + passed + ", failed " + failed + " ====");
            Platform.exit();
            System.exit(failed > 0 ? 1 : 0);
        }
    }

    static void run(Stage stage) throws Exception {

        // ---- a coordinator: exams to approve, and papers to mark as a teacher ----
        System.out.println("1. the coordinator's menu");
        SubjectCoordinator coordinator = new SubjectCoordinator(
                "100000025", "coordinator1", "Noa Katz", "01");
        coordinator.setTaughtCourseCodes(List.of("02"));
        Menu co = open(stage, coordinator);

        check("her approval entry exists", co.button("Approve or reject exams") != null);
        // Nothing on the menu may still talk about the system being built.
        for (Button b : co.buttons()) {
            String caption = Menu.captionOf(b);
            check("entry reads plainly: \"" + caption + "\"",
                    !caption.toLowerCase().contains("milestone") && !b.isDisabled());
        }
        check("it starts with no badge showing", !co.badgeShowing("Approve or reject exams"));
        check("neither does the marking entry",
                !co.badgeShowing("Mark and approve grades"));
        check("and an entry that cannot have one has no badge node at all",
                co.badge("Question bank") == null);

        co.apply(new PendingCounts(3, 2, 0, 0, 0));
        check("THREE EXAMS SHOWS A BADGE OF 3",
                "3".equals(co.badgeText("Approve or reject exams"))
             && co.badgeShowing("Approve or reject exams"));
        check("and two papers a badge of 2",
                "2".equals(co.badgeText("Mark and approve grades"))
             && co.badgeShowing("Mark and approve grades"));

        // The layout, once the scene has been through a pass.
        Button approve = co.button("Approve or reject exams");
        Label badge = co.badge("Approve or reject exams");
        double badgeRight = badge.localToScene(badge.getBoundsInLocal()).getMaxX();
        double buttonRight = approve.localToScene(approve.getBoundsInLocal()).getMaxX();
        System.out.println("   badge ends at " + Math.round(badgeRight)
                + ", button ends at " + Math.round(buttonRight));
        check("THE BADGE SITS AT THE RIGHT-HAND END, like a phone",
                badgeRight <= buttonRight && buttonRight - badgeRight < 40);
        check("it is a real circle, not a sliver",
                badge.getWidth() >= 18 && badge.getHeight() >= 14);

        // Two digits must still fit and stay inside the button.
        co.apply(new PendingCounts(12, 0, 0, 0, 0));
        check("twelve reads as 12", "12".equals(co.badgeText("Approve or reject exams")));
        badgeRight = badge.localToScene(badge.getBoundsInLocal()).getMaxX();
        check("and stays inside the button", badgeRight <= buttonRight + 0.5);

        // Back to nothing: it must disappear, not sit there showing a nought.
        co.apply(new PendingCounts(0, 0, 0, 0, 0));
        check("EMPTYING THE QUEUE HIDES THE BADGE",
                !co.badgeShowing("Approve or reject exams"));
        check("and it takes up no room while hidden",
                !co.badge("Approve or reject exams").isManaged());

        // ---- a plain teacher ----
        System.out.println("2. a teacher's menu");
        Teacher teacher = new Teacher("100000066", "teacher1", "Noa Levi");
        teacher.setTaughtCourseCodes(List.of("01"));
        Menu t = open(stage, teacher);
        check("no approval entry - she is not a coordinator",
                t.button("Approve or reject exams") == null);
        check("she has a release entry - she teaches a course",
                t.button("Release an exam") != null);
        t.apply(new PendingCounts(0, 5, 0, 0, 2));
        check("five papers waiting shows 5",
                "5".equals(t.badgeText("Mark and approve grades")));
        check("AND TWO OF HER EXAMS CAME BACK APPROVED",
                "2".equals(t.badgeText("Release an exam"))
             && t.badgeShowing("Release an exam"));

        // ---- a coordinator who teaches nothing ----
        System.out.println("2b. a coordinator with no classes of her own");
        SubjectCoordinator idle = new SubjectCoordinator(
                "100000033", "coordinator2", "Maya Shapira", "02");
        idle.setTaughtCourseCodes(List.of());
        Menu none = open(stage, idle);
        // Everything that needs a course of her own is gone, and the server refuses
        // each of them too: building (requirement 20), releasing (SUC-6), marking
        // (the sittings SHE released) and a study bot (requirement 65).
        for (String gone : java.util.List.of("Release an exam", "Build an exam",
                "Mark and approve grades", "Exams running now",
                "Results and histogram", "Course study bot")) {
            check("no \"" + gone + "\" entry - it could only ever be empty",
                    none.button(gone) == null);
        }
        check("but she still approves exams for her subject",
                none.button("Approve or reject exams") != null);
        check("and the question bank stays - requirement 19 gives her the subject's",
                none.button("Question bank") != null);
        check("and her own reports, which are about exams she wrote",
                none.button("My reports") != null);

        // A teacher WITH a course keeps every one of them.
        Teacher busy = new Teacher("100000066", "teacher1", "Noa Levi");
        busy.setTaughtCourseCodes(List.of("01"));
        Menu working = open(stage, busy);
        for (String kept : java.util.List.of("Release an exam", "Build an exam",
                "Mark and approve grades", "Course study bot")) {
            check("a teacher who teaches keeps \"" + kept + "\"",
                    working.button(kept) != null);
        }

        // ---- a student ----
        System.out.println("3. a student's menu");
        Student student = new Student("100000140", "student1", "Maya Cohen");
        student.setEnrolledCourseCodes(List.of("01", "03"));
        Menu s = open(stage, student);
        check("she has both badged entries",
                s.badge("Take an exam") != null && s.badge("My grades") != null);
        s.apply(new PendingCounts(0, 0, 1, 4, 0));
        check("one exam open now", "1".equals(s.badgeText("Take an exam")));
        check("four marks she has not read", "4".equals(s.badgeText("My grades")));
        s.apply(new PendingCounts(0, 0, 0, 0, 0));
        check("both clear together",
                !s.badgeShowing("Take an exam") && !s.badgeShowing("My grades"));

        // ---- the line under her name ----
        System.out.println("5. the line under her name, in words");
        Course algebra = new Course("02", "Algebra", "01");
        algebra.setSubjectName("Mathematics");
        Menu named = open(stage, coordinator);
        named.context(new MenuContext(List.of(algebra), List.of(), "01", "Mathematics"));
        String line = named.contextText();
        System.out.println("   " + line);
        check("the subject is named, not coded", line.contains("Mathematics"));
        check("and so is the course she teaches", line.contains("Algebra"));
        check("with the code kept for the paperwork", line.contains("(02)"));

        Menu bare = open(stage, idle);
        bare.context(new MenuContext(List.of(), List.of(), "02", "Physics"));
        System.out.println("   " + bare.contextText());
        check("a coordinator with no classes is TOLD so, not left trailing",
                bare.contextText().contains("no courses of her own"));

        // ---- the principal ----
        System.out.println("4. the principal's menu has no badges at all");
        Menu head = open(stage, new Principal("100000017", "principal", "Dalia Ben-Ami"));
        check("nothing on her browse entry",
                head.badge("Browse questions, exams and results") == null);
        check("nor on her reports entry", head.badge("Statistical reports") == null);
    }

    // -----------------------------------------------------------------

    /** One loaded menu, with the pieces this test pokes at. */
    private record Menu(Object controller, Parent root) {

        Button button(String startsWith) {
            for (Button b : buttons()) {
                if (captionOf(b).startsWith(startsWith)) {
                    return b;
                }
            }
            return null;
        }

        Label badge(String entry) {
            Button b = button(entry);
            if (b == null) {
                return null;
            }
            for (Node n : ((javafx.scene.layout.HBox) b.getGraphic()).getChildren()) {
                if (n instanceof Label label
                        && label.getStyleClass().contains("badge-unread")) {
                    return label;
                }
            }
            return null;
        }

        String badgeText(String entry) {
            Label l = badge(entry);
            return l == null ? null : l.getText();
        }

        boolean badgeShowing(String entry) {
            Label l = badge(entry);
            return l != null && l.isVisible();
        }

        List<Button> buttons() {
            List<Button> found = new ArrayList<>();
            collect(root, found);
            return found;
        }

        private static void collect(Node node, List<Button> into) {
            if (node instanceof Button b && b.getGraphic() instanceof javafx.scene.layout.HBox) {
                into.add(b);
            }
            if (node instanceof Parent p) {
                for (Node child : p.getChildrenUnmodifiable()) {
                    collect(child, into);
                }
            }
        }

        static String captionOf(Button b) {
            for (Node n : ((javafx.scene.layout.HBox) b.getGraphic()).getChildren()) {
                if (n instanceof Label label
                        && !label.getStyleClass().contains("badge-unread")) {
                    return label.getText();
                }
            }
            return "";
        }

        void context(MenuContext context) throws Exception {
            Method m = controller.getClass()
                    .getDeclaredMethod("describeContext", MenuContext.class);
            m.setAccessible(true);
            String line = (String) m.invoke(controller, context);
            java.lang.reflect.Field f = controller.getClass().getDeclaredField("contextLabel");
            f.setAccessible(true);
            ((Label) f.get(controller)).setText(line);
            root.applyCss();
            root.layout();
        }

        String contextText() throws Exception {
            java.lang.reflect.Field f = controller.getClass().getDeclaredField("contextLabel");
            f.setAccessible(true);
            return ((Label) f.get(controller)).getText();
        }

        void apply(PendingCounts counts) throws Exception {
            Method m = controller.getClass()
                    .getDeclaredMethod("applyCounts", PendingCounts.class);
            m.setAccessible(true);
            m.invoke(controller, counts);
            // A layout pass, so the positions below are the real ones.
            root.applyCss();
            root.layout();
        }
    }

    /** Loads the menu for one user and lays it out at a realistic size. */
    private static Menu open(Stage stage, User user) throws Exception {
        ClientController.getInstance().setCurrentUser(user);
        FXMLLoader loader = new FXMLLoader(MenuBadgeTest.class.getResource("/fxml/MainMenu.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 560, 560);
        hsts.client.HSTSApp.applyStylesheet(scene);
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return new Menu(loader.getController(), root);
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
