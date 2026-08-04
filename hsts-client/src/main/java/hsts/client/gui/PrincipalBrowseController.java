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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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

    private static final String ALL_COURSES = "All courses";

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
    @FXML private ComboBox<String>  questionCourseBox;
    @FXML private TextField         questionSearchField;
    @FXML private Label             questionCountLabel;
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
    @FXML private javafx.scene.control.TableView<hsts.common.entity.ExamExecution> calendarTable;
    @FXML private javafx.scene.control.TableColumn<hsts.common.entity.ExamExecution, String>
            calWhenColumn, calUntilColumn, calExamColumn, calCourseColumn,
            calCodeColumn, calWhoColumn, calSatColumn, calStateColumn;
    @FXML private Label calendarCountLabel;
    @FXML private javafx.scene.layout.VBox calendarFilterHolder;
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
    @FXML private TextField     examSearchField;
    @FXML private Label         examCountLabel;
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

    @FXML
    private void initialize() {
        bindStatusLabel(statusLabel);
        subtitleLabel.setText("Everything in the system, read-only (requirement 62). "
                            + "Nothing on this screen can change anything.");

        useWrappingCells(questionList, q ->
                q.getQuestionId() + "  ·  " + q.getTopic()
              + "  ·  " + q.getDifficulty().getDisplayName()
              + "\n" + q.getText());

        useWrappingCells(examList, e ->
                "Exam " + e.getExamId() + "  ·  " + e.getCourseName()
              + "\n" + e.getAuthorName() + "  ·  " + e.getStatus().getDisplayName()
              + "  ·  " + e.getQuestions().size() + " questions");

        useWrappingCells(resultExamList, e ->
                "Exam " + e.getExamId() + "  ·  " + e.getCourseName()
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

        questionCourseBox.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, course) -> applyQuestionFilter());
        questionSearchField.textProperty()
                .addListener((obs, old, text) -> applyQuestionFilter());
        examSearchField.textProperty()
                .addListener((obs, old, text) -> applyExamFilter());

        send(RequestType.PRINCIPAL_CALENDAR, null, REQ_CALENDAR);
        send(RequestType.PRINCIPAL_ACTIVITY, 200, REQ_ACTIVITY);

        questionButtonHolder.getChildren().add(questionButtons);
        examButtonHolder.getChildren().add(examButtons);
        questionButtons.onChanged(this::applyQuestionFilter);
        examButtons.onChanged(this::applyExamFilter);

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
                                hsts.common.entity.ExamExecution::getCourseName),
                        (x, choice) -> choice.equals(x.getCourseName()));
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
                questionButtons.withButtons("COURSE",
                        FilterBar.distinct(allQuestions, Question::getCourseCode),
                        (q, choice) -> choice.equals(q.getCourseCode()));
                questionButtons.withButtons("TOPIC",
                        FilterBar.distinct(allQuestions, Question::getTopic),
                        (q, choice) -> choice.equals(q.getTopic()));
                questionButtons.withButtons("DIFFICULTY",
                        FilterBar.distinct(allQuestions,
                                q -> q.getDifficulty().getDisplayName()),
                        (q, choice) -> choice.equals(q.getDifficulty().getDisplayName()));
                fillCourseBox();
                applyQuestionFilter();
            }
            case REQ_EXAMS -> {
                allExams.clear();
                allExams.addAll((List<Exam>) response.getPayload());
                examButtons.clearGroups();
                examButtons.withButtons("COURSE",
                        FilterBar.distinct(allExams, Exam::getCourseName),
                        (e, choice) -> choice.equals(e.getCourseName()));
                examButtons.withButtons("STATUS",
                        FilterBar.distinct(allExams, e -> e.getStatus().getDisplayName()),
                        (e, choice) -> choice.equals(e.getStatus().getDisplayName()));
                examButtons.withButtons("WRITTEN BY",
                        FilterBar.distinct(allExams, Exam::getAuthorName),
                        (e, choice) -> choice.equals(e.getAuthorName()));
                applyExamFilter();

                // Only exams somebody has actually sat belong on the results tab;
                // an exam nobody has taken has no results to browse.
                resultExamList.setItems(FXCollections.observableArrayList(allExams));
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
                if (!rows.isEmpty()) {
                    resultSittingList.getSelectionModel().select(0);
                } else {
                    clearResults();
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

    private void fillCourseBox() {
        List<String> courses = new ArrayList<>();
        courses.add(ALL_COURSES);
        allQuestions.stream()
                .map(Question::getCourseCode)
                .distinct()
                .sorted()
                .forEach(courses::add);
        String kept = questionCourseBox.getValue();
        questionCourseBox.setItems(FXCollections.observableArrayList(courses));
        questionCourseBox.setValue(courses.contains(kept) ? kept : ALL_COURSES);
    }

    // -----------------------------------------------------------------
    //  The school calendar
    // -----------------------------------------------------------------

    private static final java.time.format.DateTimeFormatter CAL_WHEN =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm");

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

    private void setUpCalendar() {
        calendarFilterHolder.getChildren().add(calendarFilter);
        calendarFilter.searchingIn(x -> x.describeExam() + " " + x.getCourseName()
                                      + " " + x.getExecutionCode() + " " + x.getReleasedByName())
                      .onChanged(this::showCalendar);

        calWhenColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getOpenTime().format(CAL_WHEN)));
        calUntilColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getCloseTime().format(CAL_WHEN)));
        calExamColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().describeExam()));
        calCourseColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getCourseName()));
        calCodeColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getExecutionCode()));
        calWhoColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getReleasedByName()));
        calSatColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(c.getValue().getNumStarted())));
        calStateColumn.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                calendarState(c.getValue())));
        calendarTable.setPlaceholder(new Label("No exam has been given to a class yet."));
    }

    private void showCalendar() {
        List<hsts.common.entity.ExamExecution> shown = calendarFilter.apply(allSittings);
        calendarTable.setItems(FXCollections.observableArrayList(shown));
        calendarCountLabel.setText(shown.size() + " of " + allSittings.size() + " shown");
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

    private void applyQuestionFilter() {
        String course = questionCourseBox.getValue();
        String search = text(questionSearchField);

        List<Question> shown = new ArrayList<>();
        for (Question q : allQuestions) {
            boolean courseMatches = course == null || ALL_COURSES.equals(course)
                                 || course.equals(q.getCourseCode());
            boolean textMatches = search.isEmpty()
                    || contains(q.getText(), search)
                    || contains(q.getTopic(), search)
                    || contains(q.getQuestionId(), search);
            if (courseMatches && textMatches) {
                shown.add(q);
            }
        }
        shown = questionButtons.apply(shown);
        questionList.setItems(FXCollections.observableArrayList(shown));
        questionCountLabel.setText(shown.size() + " of " + allQuestions.size() + " shown");
        if (shown.isEmpty()) {
            clearQuestionDetail();
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
        parts.add(heading(q.getText()));
        parts.add(caption(q.getQuestionId() + "  ·  version " + q.getVersion()
                + "  ·  course " + q.getCourseCode()
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
        String search = text(examSearchField);
        List<Exam> shown = new ArrayList<>();
        for (Exam e : allExams) {
            if (search.isEmpty()
                    || contains(e.getExamId(), search)
                    || contains(e.getCourseName(), search)
                    || contains(e.getAuthorName(), search)
                    || contains(e.getStatus().getDisplayName(), search)) {
                shown.add(e);
            }
        }
        shown = examButtons.apply(shown);
        examList.setItems(FXCollections.observableArrayList(shown));
        examCountLabel.setText(shown.size() + " of " + allExams.size() + " shown");
        if (shown.isEmpty()) {
            clearExamDetail();
        }
    }

    private void clearExamDetail() {
        examDetailBox.getChildren().setAll(caption("Pick an exam on the left."));
    }

    private void showExam(Exam exam) {
        List<javafx.scene.Node> parts = new ArrayList<>();
        parts.add(heading("Exam " + exam.getExamId() + "  ·  " + exam.getCourseName()));
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

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String haystack, String needleLowerCase) {
        return haystack != null
            && haystack.toLowerCase(Locale.ROOT).contains(needleLowerCase);
    }

    private void send(RequestType type, Object payload, String requestId) {
        try {
            controller.send(new Request(type, payload, requestId));
        } catch (Exception e) {
            showError("Could not reach the server: " + e.getMessage());
        }
    }
}
