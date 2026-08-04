import hsts.common.entity.ExamExecution;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the screens themselves do, as laid out by JavaFX and with no server.
 *
 * <p>Two things a round-trip harness cannot see, both of them reported from the
 * screen:</p>
 *
 * <ol>
 *   <li>A refusal on the study bot lasted about a second and then vanished. The
 *       exam clock ticks once a second with the seconds remaining and <b>no
 *       message</b>, and every screen but the exam screen wrote that nothing over
 *       whatever it was showing.</li>
 *   <li>The school calendar has to be a calendar - a month of squares with the
 *       exams on the days they happen - and not the table it was.</li>
 * </ol>
 *
 * <p>Same shape as {@code MenuBadgeTest}: {@code main} does not extend
 * {@code Application}, or the JVM demands JavaFX on the module path.</p>
 */
public class ScreenBehaviourTest {

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

        // ==============================================================
        System.out.println("1. A MESSAGE MUST NOT BE WIPED BY THE EXAM CLOCK");
        // ==============================================================
        for (String screen : new String[] {"/fxml/AskBot.fxml", "/fxml/StudentResults.fxml",
                                           "/fxml/MainMenu.fxml"}) {
            FXMLLoader loader = new FXMLLoader(ScreenBehaviourTest.class.getResource(screen));
            Parent root = loader.load();
            Object controller = loader.getController();
            stage.setScene(new Scene(root));
            stage.show();

            String refusal = "The study bot is not available while you are sitting an exam.";
            call(controller, "showError", refusal);
            Label status = statusLabelOf(controller);
            check(screen + ": the refusal is on the screen",
                    status != null && refusal.equals(status.getText()));

            // One second later the clock ticks. It is data for the exam screen -
            // seconds remaining - and says nothing to anybody else.
            push(controller, new PushEvent(PushType.EXAM_TIME_TICK, 300, null));
            check(screen + ": A TICK LEAVES THE REFUSAL ALONE",
                    refusal.equals(status.getText()));

            push(controller, new PushEvent(PushType.EXAM_TIME_TICK, 299, ""));
            check(screen + ": so does an empty one", refusal.equals(status.getText()));

            // Something with something to say still gets through - the guard must
            // not have turned the announcements off.
            //
            // EXAM_APPROVED, because it is one no screen here handles specially.
            // A screen that reloads on the push it is given would ask a server
            // that is not running and report that instead, which would be the
            // harness's doing rather than the product's.
            push(controller, new PushEvent(PushType.EXAM_APPROVED, null, "Your exam is approved."));
            check(screen + ": a real announcement still arrives",
                    "Your exam is approved.".equals(status.getText()));
        }

        // ==============================================================
        System.out.println("2. THE CALENDAR IS A CALENDAR");
        // ==============================================================
        FXMLLoader loader = new FXMLLoader(
                ScreenBehaviourTest.class.getResource("/fxml/PrincipalBrowse.fxml"));
        Parent root = loader.load();
        Object principal = loader.getController();
        stage.setScene(new Scene(root));
        stage.setWidth(1280);
        stage.setHeight(800);
        stage.show();

        GridPane heading = (GridPane) field(principal, "calendarHeadingRow");
        GridPane grid = (GridPane) field(principal, "calendarGrid");
        check("there is a row of day names", heading.getChildren().size() == 7);
        check("SEVEN COLUMNS, one per day of the week",
                grid.getColumnConstraints().size() == 7);
        check("the week starts on Sunday - this is an Israeli school",
                ((Label) heading.getChildren().get(0)).getText().toLowerCase().startsWith("sun"));

        // Feed it a month of sittings without a server.
        LocalDateTime when = LocalDateTime.now().withDayOfMonth(8).withHour(9).withMinute(0);
        List<ExamExecution> sittings = new ArrayList<>();
        sittings.add(sitting("010101", "Mid-term", "Plane Geometry", "AB12", when));
        sittings.add(sitting("020101", "End of term", "Plane Geometry", "CD34",
                when.withDayOfMonth(8).withHour(13)));
        sittings.add(sitting("030201", "Mechanics test", "Mechanics", "EF56",
                when.withDayOfMonth(20)));
        give(principal, sittings);

        int squares = grid.getChildren().size();
        System.out.println("   the month has " + squares + " squares");
        check("A MONTH OF SQUARES, whole weeks", squares % 7 == 0 && squares >= 28);

        List<String> chips = chipsIn(grid);
        System.out.println("   exams drawn: " + chips);
        check("EVERY SITTING IS DRAWN ON ITS DAY", chips.size() == 3);
        check("...with the time it opens", chips.get(0).startsWith("09:00"));
        check("...and its name, not its number",
                chips.stream().anyMatch(c -> c.contains("Mid-term")));
        check("two on one day are both drawn",
                chips.stream().filter(c -> c.contains("term")).count() == 2);
        check("nothing is truncated: the chips wrap",
                everyChipWraps(grid));

        // The day panel is what the old table's columns became.
        call(principal, "showCalendarDay", when.toLocalDate());
        String detail = textOf((Parent) field(principal, "calendarDetailBox"));
        System.out.println("   the day reads: " + detail.replace('\n', '|'));
        check("PICKING A DAY WRITES IT OUT IN FULL", detail.contains("Mid-term"));
        check("...with the code the class was read", detail.contains("AB12"));
        check("...who gave it out", detail.contains("Dana Cohen"));
        check("...how many sat it", detail.contains("17 sat it"));
        check("...and the course by name and by code",
                detail.contains("Plane Geometry (01)"));
        check("both sittings that day are there", detail.contains("End of term"));
        check("and the other day's is not", !detail.contains("Mechanics test"));

        // The month arrows.
        String was = ((Label) field(principal, "calMonthLabel")).getText();
        call(principal, "onNextMonth");
        String next = ((Label) field(principal, "calMonthLabel")).getText();
        check("THE ARROWS MOVE THE MONTH", !was.equals(next));
        check("and an empty month says so rather than looking broken",
                ((Label) field(principal, "calendarHintLabel")).getText()
                        .toLowerCase().contains("nothing in"));
        check("...and says where the nearest one is",
                ((Label) field(principal, "calendarHintLabel")).getText()
                        .toLowerCase().contains("nearest"));
        call(principal, "onThisMonth");
        check("\"This month\" comes back", was.equals(
                ((Label) field(principal, "calMonthLabel")).getText()));

        check("nothing is filtered out - a finished sitting is the record of the year",
                chipsIn(grid).size() == 3);

        // ==============================================================
        System.out.println("3. ONE FILTER, NOT TWO");
        // ==============================================================
        // The question tab had a course box and a search field of its own above
        // the filter bar: two boxes narrowing the same list, each with its own
        // idea of how many were shown.
        check("the old course box is gone", !hasField(principal, "questionCourseBox"));
        check("the old question search box is gone",
                !hasField(principal, "questionSearchField"));
        check("the old exam search box is gone", !hasField(principal, "examSearchField"));
        check("the filter bar is still there", field(principal, "questionButtonHolder") != null);

        // ==============================================================
        System.out.println("4. AN EXAM'S HISTORY READS LIKE A QUESTION'S");
        // ==============================================================
        // It used to be a block of text in a dialog: it proved the old versions
        // existed and left the reader to find the difference between two
        // paragraphs, which on an exam is usually one question out of ten.
        FXMLLoader history = new FXMLLoader(
                ScreenBehaviourTest.class.getResource("/fxml/ExamVersionHistory.fxml"));
        Parent historyRoot = history.load();
        Object window = history.getController();
        stage.setScene(new Scene(historyRoot));
        stage.show();

        hsts.common.entity.Exam v1 = exam(1, false, "Mid-term", 60,
                new String[][] {{"00101", "1", "Triangle angle sum", "50"},
                                {"00201", "1", "Circle theorems",    "50"}});
        hsts.common.entity.Exam v2 = exam(2, true, "Mid-term", 90,
                new String[][] {{"00101", "2", "Triangle angle sum", "40"},
                                {"00301", "1", "Pythagoras",         "60"}});
        // Called directly: it is a public method meant for exactly this, and the
        // reflective helper below matches on the argument's runtime class, which
        // is ArrayList rather than the List the method declares.
        ((hsts.client.gui.ExamVersionHistoryController) window)
                .setVersions(new ArrayList<>(List.of(v2, v1)));

        GridPane diff = (GridPane) field(window, "diffGrid");
        String comparison = textOf(diff);
        check("both versions are listed",
                ((javafx.scene.control.ListView<?>) field(window, "versionList"))
                        .getItems().size() == 2);
        check("IT COMPARES THEM FIELD BY FIELD, like a question's",
                comparison.contains("Duration") && comparison.contains("The paper"));
        check("...and the older one is chosen for her, since that is why she came",
                comparison.contains("Version 1 (older)"));
        check("the duration is shown before and after",
                comparison.contains("60 minutes") && comparison.contains("90 minutes"));

        check("THE PAPER ITSELF IS ONE OF THE FIELDS", comparison.contains("Pythagoras"));
        check("...with each question's name, not only its number",
                comparison.contains("Triangle angle sum"));
        check("...and the version of the question this exam pinned",
                comparison.contains("v1") && comparison.contains("v2"));
        check("...and the marks", comparison.contains("50 marks")
                               && comparison.contains("40 marks"));

        List<Node> changed = new ArrayList<>();
        collectNodes(diff, "diff-changed", changed);
        List<Node> unchanged = new ArrayList<>();
        collectNodes(diff, "diff-same", unchanged);
        check("EVERY DIFFERENCE IS MARKED", changed.size() >= 6);
        check("and what did not change is not", unchanged.size() >= 2);
        String summary = ((Label) field(window, "changeSummaryLabel")).getText();
        System.out.println("   " + summary);
        check("it counts them in words", summary.contains("field(s) changed"));
        check("the name did not change, so it is not counted as one",
                unchanged.stream().anyMatch(n -> "Mid-term".equals(((Label) n).getText())));
    }

    private static hsts.common.entity.Exam exam(int version, boolean current, String name,
                                                int minutes, String[][] questions) {
        hsts.common.entity.Exam exam = new hsts.common.entity.Exam();
        exam.setExamId("010101");
        exam.setVersion(version);
        exam.setCurrent(current);
        exam.setName(name);
        exam.setCourseCode("01");
        exam.setCourseName("Plane Geometry");
        exam.setDurationMinutes(minutes);
        exam.setAuthorName("Dana Cohen");
        exam.setStatus(current ? hsts.common.enums.ExamStatus.APPROVED
                               : hsts.common.enums.ExamStatus.REJECTED);
        exam.setCreatedAt(LocalDateTime.now().minusDays(version == 1 ? 3 : 1));

        List<hsts.common.entity.ExamQuestion> list = new ArrayList<>();
        int order = 1;
        for (String[] q : questions) {
            hsts.common.entity.ExamQuestion eq = new hsts.common.entity.ExamQuestion(
                    q[0], Integer.parseInt(q[1]), Integer.parseInt(q[3]), order++);
            hsts.common.entity.Question full = new hsts.common.entity.Question();
            full.setQuestionId(q[0]);
            full.setName(q[2]);
            eq.setQuestion(full);
            list.add(eq);
        }
        exam.setQuestions(list);
        return exam;
    }

    // -----------------------------------------------------------------

    private static ExamExecution sitting(String examId, String name, String course,
                                         String code, LocalDateTime open) {
        ExamExecution x = new ExamExecution();
        x.setExecutionId(Math.abs(examId.hashCode() % 1000));
        x.setExamId(examId);
        x.setExamVersion(1);
        x.setExamName(name);
        x.setCourseName(course);
        x.setExecutionCode(code);
        x.setOpenTime(open);
        x.setCloseTime(open.plusHours(2));
        x.setReleasedByName("Dana Cohen");
        x.setNumStarted(17);
        return x;
    }

    /** Puts a list of sittings into the screen the way the server's reply does. */
    @SuppressWarnings("unchecked")
    private static void give(Object controller, List<ExamExecution> sittings) throws Exception {
        Field all = controller.getClass().getDeclaredField("allSittings");
        all.setAccessible(true);
        List<ExamExecution> list = (List<ExamExecution>) all.get(controller);
        list.clear();
        list.addAll(sittings);
        call(controller, "showCalendar");
    }

    private static List<String> chipsIn(Parent grid) {
        List<String> found = new ArrayList<>();
        collectChips(grid, found);
        return found;
    }

    private static void collectChips(Node node, List<String> found) {
        if (node instanceof Label label && label.getStyleClass().contains("cal-chip")) {
            found.add(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectChips(child, found);
            }
        }
    }

    private static boolean everyChipWraps(Parent grid) {
        List<Node> chips = new ArrayList<>();
        collectNodes(grid, "cal-chip", chips);
        return chips.stream().allMatch(n -> ((Label) n).isWrapText());
    }

    private static void collectNodes(Node node, String styleClass, List<Node> found) {
        if (node.getStyleClass().contains(styleClass)) {
            found.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectNodes(child, styleClass, found);
            }
        }
    }

    private static String textOf(Parent parent) {
        StringBuilder text = new StringBuilder();
        gather(parent, text);
        return text.toString();
    }

    private static void gather(Node node, StringBuilder text) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                gather(child, text);
            }
        }
    }

    private static Label statusLabelOf(Object controller) throws Exception {
        // Held by the base class, which is where every screen registers it.
        Field f = controller.getClass().getSuperclass().getDeclaredField("statusLabel");
        f.setAccessible(true);
        return (Label) f.get(controller);
    }

    private static void push(Object controller, PushEvent event) throws Exception {
        Method m = findMethod(controller.getClass(), "onPush", PushEvent.class);
        m.setAccessible(true);
        m.invoke(controller, event);
    }

    private static void call(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof String ? String.class
                     : args[i] instanceof java.time.LocalDate ? java.time.LocalDate.class
                     : args[i].getClass();
        }
        Method m = findMethod(target.getClass(), name, types);
        m.setAccessible(true);
        m.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... types)
            throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                // keep going up
            }
        }
        throw new NoSuchMethodException(name + " on " + type);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static boolean hasField(Object target, String name) {
        try {
            target.getClass().getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
