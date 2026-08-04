package hsts.client.gui;

import hsts.common.entity.Answer;
import hsts.common.entity.Exam;
import hsts.common.entity.ExamExecution;
import hsts.common.entity.ExamQuestion;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Question;
import hsts.common.protocol.ExamRef;
import hsts.common.protocol.PushEvent;
import hsts.common.protocol.PushType;
import hsts.common.protocol.QuestionRef;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.common.protocol.ResultsQuery;
import hsts.common.protocol.ResultsReport;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SUC-12 / מתווה scenario 11: the principal browsing questions, exams and results.
 *
 * <p>Requirement 62 gives her read-only access to all three, so all three are tabs
 * of one screen. There is no Save, Edit or Delete anywhere here - and behind the
 * screen there is no request that could write one, so the restriction does not
 * depend on this class remembering to leave a button out.</p>
 *
 * <p>She sees exams complete, including the author's private notes. Acceptance
 * test 4.10 forbids showing those to a <b>student</b>; requirement 62 says the
 * principal gets "כלל הנתונים" - all of it.</p>
 */
public class PrincipalBrowseController extends GUIScreen {

    private static final String REQ_QUESTIONS = "pb.questions";
    private static final String REQ_CALENDAR = "p.calendar";
    private static final String REQ_ACTIVITY = "p.activity";
    private static final String REQ_QUESTION_GET = "pb.question";
    private static final String REQ_EXAMS     = "pb.exams";
    private static final String REQ_EXAM_GET  = "pb.exam";
    private static final String REQ_SITTINGS  = "pb.sittings";
    private static final String REQ_RESULTS   = "pb.results";

    /** See {@code TeacherReportsController.SittingRow} - same reason. */
    private record SittingRow(ExamExecution sitting) {
        boolean isAllTogether() {
            return sitting == null;
        }
    }

    @FXML private Label   subtitleLabel;
    @FXML private Button  backButton;
    @FXML private TabPane tabs;
    @FXML private Label   statusLabel;

    // ---- questions ----
    @FXML private javafx.scene.layout.VBox questionButtonHolder;
    @FXML private javafx.scene.layout.VBox examButtonHolder;

    /**
     * Buttons beside the two search boxes.
     *
     * <p>She had a box to type in, which works when you know what to type. A
     * principal looking at two hundred questions cannot know; the buttons tell her
     * what there is - which courses, which topics, which states - and are one press
     * rather than a spelling.</p>
     */
    private final FilterBar<Question> questionButtons = new FilterBar<>();
    private final FilterBar<Exam>     examButtons     = new FilterBar<>();

    // ---- the school calendar ----
    @FXML private javafx.scene.layout.GridPane calendarGrid;
    @FXML private javafx.scene.layout.GridPane calendarHeadingRow;
    @FXML private Label calMonthLabel;
    @FXML private Label calendarHintLabel;
    @FXML private VBox  calendarDetailBox;
    @FXML private Label calendarCountLabel;
    @FXML private javafx.scene.layout.VBox calendarFilterHolder;

    /** Which month is on the wall. Starts at this one, moved by the arrows. */
    private java.time.YearMonth shownMonth = java.time.YearMonth.now();

    /** The day whose sittings are written out beside the grid. */
    private java.time.LocalDate chosenDay;
    private final java.util.List<hsts.common.entity.ExamExecution> allSittings =
            new java.util.ArrayList<>();
    private final FilterBar<hsts.common.entity.ExamExecution> calendarFilter = new FilterBar<>();

    // ---- what the staff have done ----
    @FXML private javafx.scene.control.TableView<hsts.common.entity.ActivityEntry> activityTable;
    @FXML private javafx.scene.control.TableColumn<hsts.common.entity.ActivityEntry, String>
            actWhenColumn, actWhoColumn, actRoleColumn, actWhatColumn, actDetailColumn;
    @FXML private Label activityCountLabel;
    @FXML private javafx.scene.layout.VBox activityFilterHolder;
    private final java.util.List<hsts.common.entity.ActivityEntry> allActivity =
            new java.util.ArrayList<>();
    private final FilterBar<hsts.common.entity.ActivityEntry> activityFilter = new FilterBar<>();

    @FXML private ListView<Question> questionList;
    @FXML private VBox              questionDetailBox;

    // ---- exams ----
    @FXML private ListView<Exam> examList;
    @FXML private VBox          examDetailBox;

    // ---- results ----
    @FXML private ListView<Exam>       resultExamList;
    @FXML private ListView<SittingRow> resultSittingList;
    @FXML private Label   resultTitleLabel;
    @FXML private Label   resultMetaLabel;
    @FXML private HBox    resultStatsRow;
    @FXML private HistogramView resultHistogram;
    @FXML private Label   resultNoteLabel;
    @FXML private TableView<Grade> resultTable;
    @FXML private TableColumn<Grade, String> rName;
    @FXML private TableColumn<Grade, String> rMark;
    @FXML private TableColumn<Grade, String> rAuto;
    @FXML private TableColumn<Grade, String> rCourse;
    @FXML private TableColumn<Grade, String> rDuration;
    @FXML private TableColumn<Grade, String> rStatus;

    /** Everything the server sent, before the search boxes narrow it. */
    private final List<Question> allQuestions = new ArrayList<>();
    private final List<Exam> allExams = new ArrayList<>();

    private Exam chosenResultExam;

    /** The sitting she is reading, so a reload can put her back on it. 0 = none. */
    private int readingSittingId;

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Everything in the system, read-only (requirement 62). "
                            + "Nothing on this screen can change anything.");

        useWrappingCells(questionList, q ->
                q.describe() + "  ·  " + q.describeCourse()
              + "\n" + q.getTopic() + "  ·  " + q.getDifficulty().getDisplayName()
              + "  ·  " + q.getText());

        useWrappingCells(examList, e ->
                e.describe() + "  ·  " + e.getCourseName()
              + "\n" + e.getAuthorName() + "  ·  " + e.getStatus().getDisplayName()
              + "  ·  " + e.getQuestions().size() + " questions");

        useWrappingCells(resultExamList, e ->
                e.describe() + "  ·  " + e.getCourseName()
              + "\nwritten by " + e.getAuthorName());

        useWrappingCells(resultSittingList, row -> row.isAllTogether()
                ? "All sittings together\nEvery class that has sat this exam"
                : "Code " + row.sitting().getExecutionCode()
                  + "  ·  " + row.sitting().getOpenTime().toLocalDate()
                  + "\nreleased by " + row.sitting().getReleasedByName()
                  + "  ·  " + row.sitting().getNumStarted() + " sat it");

        setUpResultTable();

        // The list carries no answers - the server leaves them off deliberately -
        // so the full question is fetched when one is picked. Drawing the list's
        // copy would show a question with no options under it at all.
        questionList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, q) -> {
                    if (q == null) {
                        clearQuestionDetail();
                    } else {
                        send(RequestType.PRINCIPAL_QUESTION_GET,
                             new QuestionRef(q.getQuestionId(), q.getVersion()), REQ_QUESTION_GET);
                    }
                });
        examList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, e) -> {
                    if (e != null) {
                        send(RequestType.PRINCIPAL_EXAM_GET,
                             new ExamRef(e.getExamId(), e.getVersion()), REQ_EXAM_GET);
                    }
                });
        resultExamList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, e) -> chooseResultExam(e));
        resultSittingList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, row) -> {
                    if (chosenResultExam != null && row != null) {
                        askForResults(row);
                    }
                });

        send(RequestType.PRINCIPAL_CALENDAR, null, REQ_CALENDAR);
        send(RequestType.PRINCIPAL_ACTIVITY, 200, REQ_ACTIVITY);

        questionButtonHolder.getChildren().add(questionButtons);
        examButtonHolder.getChildren().add(examButtons);
        questionButtons
                .searchingIn(q -> q.describe() + " " + q.getText() + " " + q.getTopic()
                                + " " + q.describeCourse() + " " + q.getAuthorName())
                .onChanged(this::applyQuestionFilter);
        examButtons
                .searchingIn(e -> e.describe() + " " + e.getCourseName()
                                + " " + e.getAuthorName()
                                + " " + e.getStatus().getDisplayName())
                .onChanged(this::applyExamFilter);

        setUpCalendar();
        setUpActivity();

        controller.setResponseHandler(this::onServerResponse);
        controller.setConnectionLostHandler(this::showError);

        clearQuestionDetail();
        clearExamDetail();
        clearResults();

        send(RequestType.PRINCIPAL_QUESTIONS, null, REQ_QUESTIONS);
        send(RequestType.PRINCIPAL_EXAMS, null, REQ_EXAMS);
    }

    private void setUpResultTable() {
        rName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStudentName()));
        rMark.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? String.valueOf(c.getValue().getFinalGrade()) : "-"));
        rAuto.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getAutoGrade())));
        rCourse.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCourseName()));
        rDuration.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getActualDuration() == null
                        ? "" : String.valueOf(c.getValue().getActualDuration())));
        rStatus.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().isApproved() ? "approved" : "waiting for approval"));

        resultTable.setPlaceholder(new Label("Pick an exam on the left."));
        resultTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @FXML
    private void onBack() {
        switchTo("/fxml/MainMenu.fxml");
    }

    /**
     * A mark changed anywhere in the school. NFR 18.
     *
     * <p>Requirement 62 gives her everything, which means her figures go stale for
     * reasons that have nothing to do with her. Only the results tab is redrawn -
     * reloading the question bank because somebody approved a mark would throw away
     * her search for no reason.</p>
     */
    @Override
    protected void onPush(PushEvent event) {
        if (event.getType() == PushType.SCHOOL_ACTIVITY) {
            // Somebody on the staff did something. Everything on this screen is
            // the whole school, so everything on it is now possibly wrong: a
            // release adds a sitting to the calendar, a new question changes the
            // bank, an approval changes an exam's state, and all of them add a
            // line to the activity list. Asking for all four is one round trip
            // each and saves deciding, here, which request types affect which tab
            // - a decision that would be silently wrong the first time somebody
            // added a request type and did not think of this screen.
            showMessage(event.getMessage());
            send(RequestType.PRINCIPAL_CALENDAR, null, REQ_CALENDAR);
            send(RequestType.PRINCIPAL_ACTIVITY, 200, REQ_ACTIVITY);
            send(RequestType.PRINCIPAL_QUESTIONS, null, REQ_QUESTIONS);
            send(RequestType.PRINCIPAL_EXAMS, null, REQ_EXAMS);
            return;
        }
        if (event.getType() == PushType.EXAM_LIVE_STATUS) {
            // A student started an exam or handed one in. Only the calendar cares:
            // it says how many sat each sitting. Nothing else on this screen moved,
            // and a student's exam is not staff activity, so the log is untouched.
            showMessage(event.getMessage());
            send(RequestType.PRINCIPAL_CALENDAR, null, REQ_CALENDAR);
            return;
        }
        if (event.getType() != PushType.RESULTS_CHANGED) {
            super.onPush(event);
            return;
        }
        showMessage(event.getMessage());
        SittingRow row = resultSittingList.getSelectionModel().getSelectedItem();
        if (chosenResultExam != null && row != null) {
            askForResults(row);
        }
    }

    // -----------------------------------------------------------------
    //  Replies
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void onServerResponse(Response response) {
        String id = response.getRequestId();
        if (id == null) {
            return;
        }
        if (!response.isOk()) {
            showError(response.getMessage());
            return;
        }
        switch (id) {
            case REQ_CALENDAR -> {
                allSittings.clear();
                allSittings.addAll((List<hsts.common.entity.ExamExecution>) response.getPayload());
                calendarFilter.clearGroups();
                calendarFilter.withButtons("COURSE",
                        FilterBar.distinct(allSittings,
                                hsts.common.entity.ExamExecution::describeCourse),
                        (x, choice) -> choice.equals(x.describeCourse()));
                calendarFilter.withButtons("GIVEN BY",
                        FilterBar.distinct(allSittings,
                                hsts.common.entity.ExamExecution::getReleasedByName),
                        (x, choice) -> choice.equals(x.getReleasedByName()));
                calendarFilter.withButtons("WHEN", java.util.List.of("Finished",
                        "Open now", "Still to come"),
                        (x, choice) -> choice.equals(calendarState(x)));
                showCalendar();
            }
            case REQ_ACTIVITY -> {
                allActivity.clear();
                allActivity.addAll((List<hsts.common.entity.ActivityEntry>) response.getPayload());
                activityFilter.clearGroups();
                activityFilter.withButtons("WHO",
                        FilterBar.distinct(allActivity,
                                hsts.common.entity.ActivityEntry::getUserName),
                        (a, choice) -> choice.equals(a.getUserName()));
                activityFilter.withButtons("WHAT",
                        FilterBar.distinct(allActivity,
                                hsts.common.entity.ActivityEntry::getAction),
                        (a, choice) -> choice.equals(a.getAction()));
                showActivity();
            }
            case REQ_QUESTIONS -> {
                allQuestions.clear();
                allQuestions.addAll((List<Question>) response.getPayload());
                questionButtons.clearGroups();
                // "Plane Geometry (01)", not "01". The code is what the question's
                // own number is built from and is worth keeping in front of her;
                // it is not what she calls the course.
                questionButtons.withButtons("COURSE",
                        FilterBar.distinct(allQuestions, Question::describeCourse),
                        (q, choice) -> choice.equals(q.describeCourse()));
                questionButtons.withButtons("TOPIC",
                        FilterBar.distinct(allQuestions, Question::getTopic),
                        (q, choice) -> choice.equals(q.getTopic()));
                questionButtons.withButtons("DIFFICULTY",
                        FilterBar.distinct(allQuestions,
                                q -> q.getDifficulty().getDisplayName()),
                        (q, choice) -> choice.equals(q.getDifficulty().getDisplayName()));
                applyQuestionFilter();
            }
            case REQ_EXAMS -> {
                allExams.clear();
                allExams.addAll((List<Exam>) response.getPayload());
                examButtons.clearGroups();
                examButtons.withButtons("COURSE",
                        FilterBar.distinct(allExams, Exam::describeCourse),
                        (e, choice) -> choice.equals(e.describeCourse()));
                examButtons.withButtons("STATUS",
                        FilterBar.distinct(allExams, e -> e.getStatus().getDisplayName()),
                        (e, choice) -> choice.equals(e.getStatus().getDisplayName()));
                examButtons.withButtons("WRITTEN BY",
                        FilterBar.distinct(allExams, Exam::getAuthorName),
                        (e, choice) -> choice.equals(e.getAuthorName()));
                applyExamFilter();

                // Only exams somebody has actually sat belong on the results tab;
                // an exam nobody has taken has no results to browse.
                Exam wasReading = resultExamList.getSelectionModel().getSelectedItem();
                resultExamList.setItems(FXCollections.observableArrayList(allExams));
                reselect(resultExamList, wasReading,
                         (a, b) -> a.getExamId().equals(b.getExamId()));
            }
            case REQ_QUESTION_GET -> showQuestion((Question) response.getPayload());
            case REQ_EXAM_GET  -> showExam((Exam) response.getPayload());
            case REQ_SITTINGS  -> {
                List<ExamExecution> sittings = (List<ExamExecution>) response.getPayload();
                List<SittingRow> rows = new ArrayList<>();
                if (!sittings.isEmpty()) {
                    rows.add(new SittingRow(null));
                    sittings.forEach(x -> rows.add(new SittingRow(x)));
                }
                resultSittingList.setItems(FXCollections.observableArrayList(rows));
                showMessage(response.getMessage());
                if (rows.isEmpty()) {
                    clearResults();
                } else {
                    // Back to the sitting she was reading, if it is still there.
                    // This list is rebuilt by other people's actions now, and
                    // being thrown back to "all sittings together" every time a
                    // colleague marked a paper would be its own annoyance.
                    int back = 0;
                    for (int i = 0; i < rows.size(); i++) {
                        SittingRow row = rows.get(i);
                        if (!row.isAllTogether()
                                && row.sitting().getExecutionId() == readingSittingId) {
                            back = i;
                        }
                    }
                    resultSittingList.getSelectionModel().select(back);
                }
            }
            case REQ_RESULTS -> showResults((ResultsReport) response.getPayload(),
                                            response.getMessage());
            default -> { }
        }
    }

    // -----------------------------------------------------------------
    //  Questions
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    //  The school calendar
    // -----------------------------------------------------------------

    /**
     * Where one sitting is in time: over, running, or still to come.
     *
     * <p>Worked out here rather than stored, for the same reason "in the drawer" is
     * not stored: it changes by itself as the clock moves, and a flag would need
     * somebody to remember to turn it.</p>
     */
    private static String calendarState(hsts.common.entity.ExamExecution x) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (x.getCloseTime().isBefore(now)) {
            return "Finished";
        }
        return x.getOpenTime().isAfter(now) ? "Still to come" : "Open now";
    }

    /** Sunday to Saturday - the Israeli school week, which is what this is. */
    private static final java.time.DayOfWeek[] WEEK = {
        java.time.DayOfWeek.SUNDAY,   java.time.DayOfWeek.MONDAY,
        java.time.DayOfWeek.TUESDAY,  java.time.DayOfWeek.WEDNESDAY,
        java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY,
        java.time.DayOfWeek.SATURDAY
    };

    private static final java.time.format.DateTimeFormatter CAL_MONTH =
            java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final java.time.format.DateTimeFormatter CAL_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    private static final java.time.format.DateTimeFormatter CAL_DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    private void setUpCalendar() {
        calendarFilterHolder.getChildren().add(calendarFilter);
        calendarFilter.searchingIn(x -> x.describeExam() + " " + x.getCourseName()
                                      + " " + x.getExecutionCode() + " " + x.getReleasedByName())
                      .onChanged(this::showCalendar);

        // Seven equal columns on both the heading row and the grid, so the day
        // names stay over their own days at every window width.
        for (java.time.DayOfWeek day : WEEK) {
            javafx.scene.layout.ColumnConstraints column =
                    new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(100.0 / WEEK.length);
            column.setHgrow(Priority.ALWAYS);
            calendarHeadingRow.getColumnConstraints().add(column);

            javafx.scene.layout.ColumnConstraints same =
                    new javafx.scene.layout.ColumnConstraints();
            same.setPercentWidth(100.0 / WEEK.length);
            same.setHgrow(Priority.ALWAYS);
            calendarGrid.getColumnConstraints().add(same);

            Label name = new Label(day.getDisplayName(
                    java.time.format.TextStyle.SHORT, Locale.getDefault()));
            name.getStyleClass().add("cal-weekday");
            name.setMaxWidth(Double.MAX_VALUE);
            name.setWrapText(true);
            calendarHeadingRow.add(name, calendarHeadingRow.getColumnConstraints().size() - 1, 0);
        }
        showCalendarDay(null);
    }

    @FXML private void onPreviousMonth() {
        shownMonth = shownMonth.minusMonths(1);
        showCalendar();
    }

    @FXML private void onNextMonth() {
        shownMonth = shownMonth.plusMonths(1);
        showCalendar();
    }

    @FXML private void onThisMonth() {
        shownMonth = java.time.YearMonth.now();
        showCalendar();
    }

    /**
     * Draws the month.
     *
     * <p>A table sorted by date is a list of dates. A month laid out as a month
     * answers "how busy is next week" by being looked at, which is the question a
     * head teacher actually has - and it is the shape every other calendar she has
     * ever used already has.</p>
     *
     * <p>The filter is applied first and the grid is drawn from what survives, so
     * the buttons above narrow the calendar rather than a separate list.</p>
     */
    private void showCalendar() {
        List<hsts.common.entity.ExamExecution> shown = calendarFilter.apply(allSittings);
        calendarCountLabel.setText(shown.size() + " of " + allSittings.size() + " shown");
        calMonthLabel.setText(shownMonth.format(CAL_MONTH));

        // A sitting belongs to the day it opens. One that runs past midnight says
        // so on its own line rather than being drawn twice, which would make the
        // same exam look like two.
        java.util.Map<java.time.LocalDate, List<hsts.common.entity.ExamExecution>> byDay =
                new java.util.HashMap<>();
        for (hsts.common.entity.ExamExecution sitting : shown) {
            byDay.computeIfAbsent(sitting.getOpenTime().toLocalDate(),
                    d -> new ArrayList<>()).add(sitting);
        }
        for (List<hsts.common.entity.ExamExecution> ofOneDay : byDay.values()) {
            ofOneDay.sort(java.util.Comparator.comparing(
                    hsts.common.entity.ExamExecution::getOpenTime));
        }

        calendarGrid.getChildren().clear();
        calendarGrid.getRowConstraints().clear();

        java.time.LocalDate first = shownMonth.atDay(1);
        // Sunday is column 0. DayOfWeek numbers Monday 1 to Sunday 7, so the
        // remainder by 7 puts Sunday at 0 and leaves the rest in order.
        int blanksBefore = first.getDayOfWeek().getValue() % 7;
        int cells = blanksBefore + shownMonth.lengthOfMonth();
        int weeks = (cells + 6) / 7;

        for (int week = 0; week < weeks; week++) {
            javafx.scene.layout.RowConstraints row = new javafx.scene.layout.RowConstraints();
            row.setPercentHeight(100.0 / weeks);
            row.setVgrow(Priority.ALWAYS);
            calendarGrid.getRowConstraints().add(row);
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate start = first.minusDays(blanksBefore);
        int thisMonth = 0;

        for (int cell = 0; cell < weeks * 7; cell++) {
            java.time.LocalDate date = start.plusDays(cell);
            List<hsts.common.entity.ExamExecution> onThatDay =
                    byDay.getOrDefault(date, List.of());
            if (java.time.YearMonth.from(date).equals(shownMonth)) {
                thisMonth += onThatDay.size();
            }
            calendarGrid.add(dayCell(date, onThatDay, today), cell % 7, cell / 7);
        }

        // An empty month reads as a broken screen unless it says why it is empty
        // and where the nearest thing is.
        if (thisMonth == 0) {
            calendarHintLabel.setText("Nothing in " + shownMonth.format(CAL_MONTH)
                    + "." + nearestElsewhere(shown));
        } else {
            calendarHintLabel.setText(thisMonth + " sitting(s) this month. "
                    + "Click a day to read it in full.");
        }
        if (chosenDay != null) {
            showCalendarDay(chosenDay);
        }
    }

    /** " The nearest is 3 September - Mid-term." Blank when there is nothing at all. */
    private String nearestElsewhere(List<hsts.common.entity.ExamExecution> shown) {
        hsts.common.entity.ExamExecution nearest = null;
        long best = Long.MAX_VALUE;
        java.time.LocalDate middle = shownMonth.atDay(15);
        for (hsts.common.entity.ExamExecution sitting : shown) {
            long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                    middle, sitting.getOpenTime().toLocalDate()));
            if (days < best) {
                best = days;
                nearest = sitting;
            }
        }
        return nearest == null
                ? "  No exam has been given to a class yet."
                : "  The nearest is " + nearest.getOpenTime().toLocalDate()
                  .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
                  + " - " + nearest.describeExam() + ".";
    }

    /** One square: the date, and a line for every sitting that starts on it. */
    private VBox dayCell(java.time.LocalDate date,
                         List<hsts.common.entity.ExamExecution> sittings,
                         java.time.LocalDate today) {
        VBox cell = new VBox(3);
        cell.getStyleClass().add("cal-day");
        if (!java.time.YearMonth.from(date).equals(shownMonth)) {
            cell.getStyleClass().add("cal-day-outside");
        }
        if (date.equals(today)) {
            cell.getStyleClass().add("cal-today");
        }
        if (date.equals(chosenDay)) {
            cell.getStyleClass().add("cal-day-chosen");
        }

        Label number = new Label(String.valueOf(date.getDayOfMonth()));
        number.getStyleClass().add("cal-day-number");
        cell.getChildren().add(number);

        for (hsts.common.entity.ExamExecution sitting : sittings) {
            Label chip = new Label(sitting.getOpenTime().format(CAL_TIME)
                    + "  " + shortName(sitting));
            chip.getStyleClass().addAll("cal-chip", chipStyle(calendarState(sitting)));
            chip.setWrapText(true);
            chip.setMaxWidth(Double.MAX_VALUE);
            chip.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            chip.setOnMouseClicked(e -> showCalendarDay(date));
            cell.getChildren().add(chip);
        }

        cell.setOnMouseClicked(e -> showCalendarDay(date));
        return cell;
    }

    private static String shortName(hsts.common.entity.ExamExecution sitting) {
        return (sitting.getExamName() == null || sitting.getExamName().isBlank())
                ? sitting.getExamId() : sitting.getExamName();
    }

    private static String chipStyle(String state) {
        return switch (state) {
            case "Finished"  -> "cal-chip-done";
            case "Open now"  -> "cal-chip-open";
            default          -> "cal-chip-soon";
        };
    }

    /**
     * Writes one day out in full beside the grid.
     *
     * <p>The grid has room for a time and a name and no more, so everything the
     * old table had a column for - the code, who gave it, how many sat it - lives
     * here, where there is room to say it in words.</p>
     */
    private void showCalendarDay(java.time.LocalDate date) {
        chosenDay = date;
        List<javafx.scene.Node> parts = new ArrayList<>();

        if (date == null) {
            parts.add(caption("Click a day in the calendar."));
            calendarDetailBox.getChildren().setAll(parts);
            return;
        }

        parts.add(heading(date.format(CAL_DAY)));

        List<hsts.common.entity.ExamExecution> onThatDay = new ArrayList<>();
        for (hsts.common.entity.ExamExecution sitting : calendarFilter.apply(allSittings)) {
            if (sitting.getOpenTime().toLocalDate().equals(date)) {
                onThatDay.add(sitting);
            }
        }
        onThatDay.sort(java.util.Comparator.comparing(
                hsts.common.entity.ExamExecution::getOpenTime));

        if (onThatDay.isEmpty()) {
            parts.add(caption("No exam was given to a class that day."));
        }
        for (hsts.common.entity.ExamExecution sitting : onThatDay) {
            VBox block = new VBox(3);
            block.getStyleClass().add("card");
            block.getChildren().add(heading(sitting.describeExam()));
            block.getChildren().add(caption(sitting.describeCourse()));
            block.getChildren().add(wrapped(sitting.getOpenTime().format(CAL_TIME)
                    + " to " + sitting.getCloseTime().format(CAL_TIME)
                    + (sitting.getCloseTime().toLocalDate().equals(date)
                       ? "" : " the next day")));
            block.getChildren().add(caption("Code " + sitting.getExecutionCode()
                    + "  ·  given by " + sitting.getReleasedByName()));
            block.getChildren().add(caption(sitting.getNumStarted() + " sat it  ·  "
                    + calendarState(sitting)));
            parts.add(block);
        }
        calendarDetailBox.getChildren().setAll(parts);
        showCalendarChosenDay();
    }

    /**
     * Marks the chosen square, without redrawing the month.
     *
     * <p>Redrawing would work and would also throw away the scroll position and
     * flicker, for a change of one outline.</p>
     */
    private void showCalendarChosenDay() {
        java.time.LocalDate first = shownMonth.atDay(1);
        java.time.LocalDate start = first.minusDays(first.getDayOfWeek().getValue() % 7);

        for (javafx.scene.Node node : calendarGrid.getChildren()) {
            node.getStyleClass().remove("cal-day-chosen");

            Integer column = javafx.scene.layout.GridPane.getColumnIndex(node);
            Integer row    = javafx.scene.layout.GridPane.getRowIndex(node);
            if (column == null || row == null) {
                continue;
            }
            if (start.plusDays(row * 7L + column).equals(chosenDay)) {
                node.getStyleClass().add("cal-day-chosen");
            }
        }
    }

    // -----------------------------------------------------------------
    //  What the staff have done
    // -----------------------------------------------------------------

    private static final java.time.format.DateTimeFormatter ACT_WHEN =
            java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm:ss");

    private void setUpActivity() {
        activityFilterHolder.getChildren().add(activityFilter);
        activityFilter.searchingIn(a -> a.getUserName() + " " + a.getAction()
                                      + " " + a.getDetail())
                      .onChanged(this::showActivity);

        actWhenColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getAt().format(ACT_WHEN)));
        actWhoColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getUserName()));
        actRoleColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getRole()));
        actWhatColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getAction()));
        actDetailColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getDetail()));
        activityTable.setPlaceholder(new Label("Nothing has been done yet."));
    }

    private void showActivity() {
        List<hsts.common.entity.ActivityEntry> shown = activityFilter.apply(allActivity);
        activityTable.setItems(FXCollections.observableArrayList(shown));
        activityCountLabel.setText(shown.size() + " of " + allActivity.size() + " shown");
    }

    /**
     * One filter over the bank: the search box and the buttons, both in the filter
     * bar.
     *
     * <p>There used to be a course box and a search field above it as well. Two
     * boxes that narrow the same list is one more than anybody needs, and the
     * count beside each of them disagreed about what "shown" meant.</p>
     */
    private void applyQuestionFilter() {
        Question wasOn = questionList.getSelectionModel().getSelectedItem();
        List<Question> shown = questionButtons.apply(allQuestions);
        questionList.setItems(FXCollections.observableArrayList(shown));
        reselect(questionList, wasOn, (a, b) -> a.getQuestionId().equals(b.getQuestionId()));
        if (shown.isEmpty()) {
            clearQuestionDetail();
        }
    }

    /**
     * Puts the selection back on the same row after the list was rebuilt.
     *
     * <p>The lists now reload on their own - a teacher writing a question rebuilds
     * the principal's bank underneath her. Losing her place every time somebody
     * else did something would be a worse screen than the stale one this replaced,
     * so the row is found again by its id rather than by its position.</p>
     */
    private <T> void reselect(ListView<T> list, T wasOn,
                              java.util.function.BiPredicate<T, T> sameThing) {
        if (wasOn == null) {
            return;
        }
        for (T item : list.getItems()) {
            if (sameThing.test(item, wasOn)) {
                list.getSelectionModel().select(item);
                return;
            }
        }
    }

    private void clearQuestionDetail() {
        questionDetailBox.getChildren().setAll(caption("Pick a question on the left."));
    }

    private void showQuestion(Question q) {
        if (q == null) {
            clearQuestionDetail();
            return;
        }
        List<javafx.scene.Node> parts = new ArrayList<>();
        parts.add(heading(q.describe()));
        parts.add(wrapped(q.getText()));
        parts.add(caption("version " + q.getVersion()
                + "  ·  " + q.describeCourse()
                + "  ·  " + q.getTopic()
                + "  ·  " + q.getDifficulty().getDisplayName()
                + "  ·  written by " + q.getAuthorName()));

        if (q.getInstructions() != null && !q.getInstructions().isBlank()) {
            parts.add(wrapped("Instructions:  " + q.getInstructions()));
        }

        for (Answer option : q.getAnswers()) {
            Label line = wrapped("   " + option.getAnswerNo() + ".  " + option.getText()
                    + (option.isCorrect() ? "     ← the correct answer" : ""));
            if (option.isCorrect()) {
                line.getStyleClass().add("status-success");
            }
            parts.add(line);
        }
        questionDetailBox.getChildren().setAll(parts);
    }

    // -----------------------------------------------------------------
    //  Exams
    // -----------------------------------------------------------------

    private void applyExamFilter() {
        Exam wasOn = examList.getSelectionModel().getSelectedItem();
        List<Exam> shown = examButtons.apply(allExams);
        examList.setItems(FXCollections.observableArrayList(shown));
        reselect(examList, wasOn, (a, b) -> a.getExamId().equals(b.getExamId()));
        if (shown.isEmpty()) {
            clearExamDetail();
        }
    }

    private void clearExamDetail() {
        examDetailBox.getChildren().setAll(caption("Pick an exam on the left."));
    }

    private void showExam(Exam exam) {
        List<javafx.scene.Node> parts = new ArrayList<>();
        parts.add(heading(exam.describe() + "  ·  " + exam.describeCourse()));
        parts.add(caption("version " + exam.getVersion()
                + "  ·  written by " + exam.getAuthorName()
                + "  ·  " + exam.getDurationMinutes() + " minutes"
                + "  ·  " + exam.getStatus().getDisplayName()
                + "  ·  " + exam.getTotalPoints() + " points in total"));

        if (exam.getRejectionReason() != null && !exam.getRejectionReason().isBlank()) {
            Label why = wrapped("Rejected because:  " + exam.getRejectionReason());
            why.getStyleClass().add("status-error");
            parts.add(why);
        }
        if (exam.getInstructionsForStudents() != null
                && !exam.getInstructionsForStudents().isBlank()) {
            parts.add(wrapped("For the students:  " + exam.getInstructionsForStudents()));
        }
        // Requirement 62: she sees all the data. A student may not see this
        // (acceptance test 4.10); the principal may.
        if (exam.getNotesForTeacher() != null && !exam.getNotesForTeacher().isBlank()) {
            Label note = wrapped("The author's private note:  " + exam.getNotesForTeacher());
            note.getStyleClass().add("diff-changed");
            parts.add(note);
        }

        int number = 1;
        for (ExamQuestion eq : exam.getQuestions()) {
            Question q = eq.getQuestion();
            VBox block = new VBox(4);
            block.getStyleClass().add("card");
            block.getChildren().add(caption(number++ + ".      " + eq.getPoints()
                    + (eq.getPoints() == 1 ? " point" : " points")
                    + "      " + eq.getQuestionId() + " v" + eq.getQuestionVersion()));
            block.getChildren().add(wrapped(q == null ? "(question not loaded)" : q.getText()));
            if (q != null) {
                for (Answer option : q.getAnswers()) {
                    Label line = wrapped("   " + option.getAnswerNo() + ".  " + option.getText()
                            + (option.isCorrect() ? "     ← correct" : ""));
                    if (option.isCorrect()) {
                        line.getStyleClass().add("status-success");
                    }
                    block.getChildren().add(line);
                }
            }
            parts.add(block);
        }
        examDetailBox.getChildren().setAll(parts);
    }

    // -----------------------------------------------------------------
    //  Results
    // -----------------------------------------------------------------

    private void chooseResultExam(Exam exam) {
        chosenResultExam = exam;
        resultSittingList.setItems(FXCollections.observableArrayList());
        clearResults();
        if (exam != null) {
            send(RequestType.PRINCIPAL_SITTINGS, exam.getExamId(), REQ_SITTINGS);
        }
    }

    private void askForResults(SittingRow row) {
        readingSittingId = row.isAllTogether() ? 0 : row.sitting().getExecutionId();
        ResultsQuery query = row.isAllTogether()
                ? ResultsQuery.wholeExam(chosenResultExam.getExamId(),
                                         chosenResultExam.getVersion())
                : ResultsQuery.sitting(chosenResultExam.getExamId(),
                                       chosenResultExam.getVersion(),
                                       row.sitting().getExecutionId());
        send(RequestType.PRINCIPAL_RESULTS, query, REQ_RESULTS);
    }

    private void clearResults() {
        resultTitleLabel.setText("Nothing chosen");
        resultMetaLabel.setText("Pick an exam, then a sitting.");
        resultStatsRow.getChildren().clear();
        resultHistogram.show(null);
        resultTable.setItems(FXCollections.observableArrayList());
        resultNoteLabel.setText("");
    }

    private void showResults(ResultsReport report, String note) {
        resultTitleLabel.setText(report.getTitle());
        resultMetaLabel.setText(report.getSubtitle());

        ExamStatistics stats = report.getStatistics();
        resultStatsRow.getChildren().setAll(
                statTile("Approved marks", String.valueOf(stats.getGradeCount())),
                statTile("Average", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getAverage())),
                statTile("Median", stats.getGradeCount() == 0 ? "-"
                        : String.format("%.1f", stats.getMedian())),
                statTile("Papers in total", String.valueOf(report.getGrades().size())));

        resultHistogram.show(stats);
        resultTable.setItems(FXCollections.observableArrayList(report.getGrades()));
        resultTable.setPlaceholder(new Label("Nobody has sat this yet."));

        long waiting = report.getUnapprovedCount();
        resultNoteLabel.setText(waiting == 0
                ? "every paper approved"
                : waiting + " still waiting for approval - not in the figures above");
        showMessage(note);
    }

    // -----------------------------------------------------------------

    private VBox statTile(String caption, String value) {
        Label number = new Label(value);
        number.setStyle("-fx-font-size: 22px; -fx-font-weight: 600;");
        Label name = new Label(caption);
        name.getStyleClass().add("caption");
        name.setWrapText(true);

        VBox tile = new VBox(2, number, name);
        tile.getStyleClass().add("card");
        tile.setMinWidth(130);
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("h3");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("caption");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
