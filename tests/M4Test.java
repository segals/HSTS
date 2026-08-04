import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.enums.ExamStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 4 verification: exam building (manual + automatic), the 100-point
 * rule, the "not enough questions" refusal, versioning, and version pinning.
 */
public class M4Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher;
    private static String course;

    /**
     * A port nobody is using, found at the moment of asking.
     *
     * <p>These suites used to each hold a fixed port in the 155xx range. On this
     * machine Windows hands out ephemeral ports from 1024 upwards - the whole range
     * - so an outbound connection belonging to any other program can be sitting on
     * the number a suite is about to listen on. It happened: one run failed with
     * "Address already in use" and the port turned out to be held by an unrelated
     * process. A fixed number was never safe, it was just usually lucky.</p>
     */
    private static int freePort() {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            probe.setReuseAddress(true);
            return probe.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            failed++;
            System.out.println("   [FAIL] harness threw:");
            t.printStackTrace(System.out);
        } finally {
            System.out.println();
            System.out.println("==== passed " + passed + ", failed " + failed + " ====");
            System.exit(failed > 0 ? 1 : 0);
        }
    }

    static void run(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();

        teacher = new Conn();
        teacher.login("teacher1", "teacher1!T");
        @SuppressWarnings("unchecked")
        List<Course> myCourses = (List<Course>) teacher.ask(RequestType.COURSE_LIST_MINE, null).getPayload();
        course = myCourses.get(0).getCourseCode();
        String subject = myCourses.get(0).getSubjectCode();

        System.out.println("0. seed a known bank: 6 Algebra easy, 4 Geometry hard");
        List<String> algebraEasy = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            algebraEasy.add(addQuestion("Algebra easy question " + i, "AlgebraX", DifficultyLevel.EASY));
        }
        for (int i = 0; i < 4; i++) {
            addQuestion("Geometry hard question " + i, "GeometryX", DifficultyLevel.HARD);
        }
        check("bank seeded", algebraEasy.size() == 6);

        System.out.println("1. MANUAL build");
        Response manual = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, algebraEasy.subList(0, 4)));
        check("manual build accepted", manual.isOk());
        Exam draft = (Exam) manual.getPayload();
        check("4 questions chosen", draft.getQuestionCount() == 4);
        check("points already add up to 100", draft.getTotalPoints() == 100);
        check("draft is not saved yet (no id)", draft.getExamId() == null);
        check("question versions were pinned", draft.getQuestions().stream()
                .allMatch(q -> q.getQuestionVersion() > 0));

        System.out.println("2. points split evenly with a remainder");
        Response three = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, algebraEasy.subList(0, 3)));
        Exam threeQ = (Exam) three.getPayload();
        List<Integer> pts = threeQ.getQuestions().stream().map(ExamQuestion::getPoints).toList();
        System.out.println("   3 questions -> " + pts);
        check("3 questions still total exactly 100", threeQ.getTotalPoints() == 100);
        check("remainder handed out, not dropped", pts.contains(34) && pts.contains(33));

        System.out.println("3. SAVE, and the 6-digit id format");
        draft.setName("M4Test exam " + System.nanoTime());
        draft.setDurationMinutes(90);
        draft.setInstructionsForStudents("Answer all questions.");
        draft.setNotesForTeacher("SECRET: question 3 was hard last year.");
        Response saved = teacher.ask(RequestType.EXAM_SAVE, draft);
        check("save accepted", saved.isOk());
        Exam savedExam = (Exam) saved.getPayload();
        System.out.println("   id = " + savedExam.getExamId());
        check("id is exactly 6 digits", savedExam.getExamId().matches("\\d{6}"));
        check("digits 2-3 are the course code",
                savedExam.getExamId().substring(2, 4).equals(course));
        check("digits 4-5 are the subject code",
                savedExam.getExamId().substring(4, 6).equals(subject));
        check("saved as PENDING_APPROVAL", savedExam.getStatus() == ExamStatus.PENDING_APPROVAL);

        System.out.println("4. the 100-point rule is enforced on the SERVER");
        Exam bad = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, algebraEasy.subList(0, 4))).getPayload();
        bad.setName("M4Test exam " + System.nanoTime());
        bad.setDurationMinutes(60);
        bad.getQuestions().get(0).setPoints(50);          // now 50+25+25+25 = 125
        Response tooMany = teacher.ask(RequestType.EXAM_SAVE, bad);
        check("125 points refused", !tooMany.isOk());
        System.out.println("   " + tooMany.getMessage());
        check("message names the actual total", tooMany.getMessage().contains("125"));

        System.out.println("4b. THE NAME IS COMPULSORY");
        // Asked for by the customer. The 6-digit number is unique and never changes,
        // but nobody remembers which exam "020101" is - and a name that MAY be left
        // blank is a name half the exams will not have.
        Exam nameless = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, algebraEasy.subList(0, 4))).getPayload();
        nameless.setDurationMinutes(60);
        for (var eq : nameless.getQuestions()) {
            eq.setPoints(25);
        }

        nameless.setName(null);
        Response noName = teacher.ask(RequestType.EXAM_SAVE, nameless);
        check("an exam with no name is refused", !noName.isOk());
        System.out.println("   " + noName.getMessage());
        check("and the refusal says what to type", noName.getMessage().contains("name"));

        nameless.setName("   ");
        check("spaces are not a name",
                !teacher.ask(RequestType.EXAM_SAVE, nameless).isOk());

        nameless.setName("x".repeat(200));
        Response tooLong = teacher.ask(RequestType.EXAM_SAVE, nameless);
        check("a name too long to fit a list is refused", !tooLong.isOk());
        System.out.println("   " + tooLong.getMessage());

        nameless.setName("   Algebra warm-up   ");
        Response named = teacher.ask(RequestType.EXAM_SAVE, nameless);
        check("with a name it saves", named.isOk());
        Exam savedNamed = (Exam) named.getPayload();
        check("the name is trimmed on the way in",
                "Algebra warm-up".equals(savedNamed.getName()));
        check("and the message uses it, not just the number",
                named.getMessage().contains("Algebra warm-up")
             && named.getMessage().contains(savedNamed.getExamId()));
        System.out.println("   " + named.getMessage());
        check("the 6-digit number is still there and still 6 digits",
                savedNamed.getExamId() != null && savedNamed.getExamId().length() == 6);
        check("and \"name . number\" is how it reads on a list",
                savedNamed.describe().equals("Algebra warm-up  ·  " + savedNamed.getExamId()));

        List<Exam> hers = (List<Exam>) teacher.ask(RequestType.EXAM_LIST_MINE, null).getPayload();
        boolean foundNamed = false;
        for (Exam e : hers) {
            if (e.getExamId().equals(savedNamed.getExamId())
                    && "Algebra warm-up".equals(e.getName())) {
                foundNamed = true;
            }
        }
        check("IT COMES BACK NAMED IN HER OWN LIST", foundNamed);

        System.out.println("5. duration and empty-exam rules");
        Exam zeroTime = (Exam) teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.manual(course, algebraEasy.subList(0, 4))).getPayload();
        zeroTime.setName("M4Test exam " + System.nanoTime());
        zeroTime.setDurationMinutes(0);
        check("zero duration refused", !teacher.ask(RequestType.EXAM_SAVE, zeroTime).isOk());
        zeroTime.setName("M4Test exam " + System.nanoTime());
        zeroTime.setDurationMinutes(-15);
        check("negative duration refused", !teacher.ask(RequestType.EXAM_SAVE, zeroTime).isOk());
        check("empty question list refused when building",
                !teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                        ExamBuildCriteria.manual(course, new ArrayList<>())).isOk());

        System.out.println("6. AUTOMATIC build by topic and difficulty");
        List<QuestionQuota> quotas = List.of(
                new QuestionQuota("AlgebraX", DifficultyLevel.EASY, 3),
                new QuestionQuota("GeometryX", DifficultyLevel.HARD, 2));
        Response auto = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.automatic(course, quotas));
        check("automatic build accepted", auto.isOk());
        Exam autoExam = (Exam) auto.getPayload();
        check("5 questions chosen", autoExam.getQuestionCount() == 5);
        long easyAlgebra = autoExam.getQuestions().stream()
                .filter(q -> "AlgebraX".equals(q.getQuestion().getTopic())
                          && q.getQuestion().getDifficulty() == DifficultyLevel.EASY).count();
        long hardGeom = autoExam.getQuestions().stream()
                .filter(q -> "GeometryX".equals(q.getQuestion().getTopic())
                          && q.getQuestion().getDifficulty() == DifficultyLevel.HARD).count();
        System.out.println("   easy AlgebraX: " + easyAlgebra + ", hard GeometryX: " + hardGeom);
        check("quota 1 honoured exactly", easyAlgebra == 3);
        check("quota 2 honoured exactly", hardGeom == 2);
        check("automatic points also total 100", autoExam.getTotalPoints() == 100);

        System.out.println("7. no question appears twice, even with overlapping quotas");
        Response overlap = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.automatic(course, List.of(
                        new QuestionQuota("AlgebraX", DifficultyLevel.EASY, 3),
                        new QuestionQuota(null, null, 3))));      // "any" can match the same ones
        check("overlapping quotas accepted", overlap.isOk());
        Exam overlapExam = (Exam) overlap.getPayload();
        Set<String> ids = new HashSet<>();
        for (ExamQuestion eq : overlapExam.getQuestions()) ids.add(eq.getQuestionId());
        check("6 questions, all distinct", overlapExam.getQuestionCount() == 6 && ids.size() == 6);

        System.out.println("8. requirement 29 - not enough questions, nothing created");
        int examsBefore = db.countRows("exam");
        Response short1 = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.automatic(course, List.of(
                        new QuestionQuota("GeometryX", DifficultyLevel.HARD, 99))));
        check("refused", !short1.isOk());
        System.out.println("   " + short1.getMessage().replace("\n", "  "));
        check("message names the failing line", short1.getMessage().contains("GeometryX"));
        check("message says how many were found", short1.getMessage().contains("found "));
        check("NO exam row was created", db.countRows("exam") == examsBefore);

        Response noSuchTopic = teacher.ask(RequestType.EXAM_BUILD_DRAFT,
                ExamBuildCriteria.automatic(course, List.of(
                        new QuestionQuota("NoSuchTopic", null, 1))));
        check("unknown topic refused", !noSuchTopic.isOk());
        check("says there are none at all", noSuchTopic.getMessage().contains("no questions matching"));

        System.out.println("9. EDIT creates a new version and keeps the old one");
        savedExam.setName("M4Test exam " + System.nanoTime());
        savedExam.setDurationMinutes(120);
        Response edited = teacher.ask(RequestType.EXAM_EDIT, savedExam);
        check("edit accepted", edited.isOk());
        System.out.println("   " + edited.getMessage());
        @SuppressWarnings("unchecked")
        List<Exam> versions = (List<Exam>) teacher.ask(RequestType.EXAM_VERSIONS,
                new ExamRef(savedExam.getExamId())).getPayload();
        check("two versions exist", versions.size() == 2);
        check("v2 is current", versions.get(0).getVersion() == 2 && versions.get(0).isCurrent());
        check("v1 still exists, no longer current",
                versions.get(1).getVersion() == 1 && !versions.get(1).isCurrent());
        Exam v1 = (Exam) teacher.ask(RequestType.EXAM_GET,
                new ExamRef(savedExam.getExamId(), 1)).getPayload();
        check("v1 kept its original 90-minute duration", v1.getDurationMinutes() == 90);

        System.out.println("10. VERSION PINNING - editing a question does not rewrite old exams");
        String pinnedId = v1.getQuestions().get(0).getQuestionId();
        int pinnedVersion = v1.getQuestions().get(0).getQuestionVersion();
        String originalText = v1.getQuestions().get(0).getQuestion().getText();
        System.out.println("   exam v1 uses question " + pinnedId + " v" + pinnedVersion);

        Question rewrite = new Question();
        rewrite.setQuestionId(pinnedId);
        rewrite.setText("COMPLETELY REWRITTEN after the exam was built");
        rewrite.setName("M4Test q " + System.nanoTime());
        rewrite.setTopic("AlgebraX");
        rewrite.setDifficulty(DifficultyLevel.HARD);
        List<Answer> newAnswers = new ArrayList<>();
        for (int i = 1; i <= 4; i++) newAnswers.add(new Answer(i, "new " + i, i == 1));
        rewrite.setAnswers(newAnswers);
        check("question edited to v2",
                teacher.ask(RequestType.QUESTION_EDIT, rewrite).isOk());

        Exam v1again = (Exam) teacher.ask(RequestType.EXAM_GET,
                new ExamRef(savedExam.getExamId(), 1)).getPayload();
        String textNow = v1again.getQuestions().get(0).getQuestion().getText();
        System.out.println("   exam v1 still shows: " + textNow);
        check("the old exam STILL shows the original wording", textNow.equals(originalText));
        check("and still points at the pinned version",
                v1again.getQuestions().get(0).getQuestionVersion() == pinnedVersion);

        System.out.println("11. hidden teacher notes are stored separately from student text");
        check("teacher notes survived the round trip",
                v1again.getNotesForTeacher() != null
                     && v1again.getNotesForTeacher().startsWith("SECRET"));
        check("student instructions are a different field",
                !v1again.getInstructionsForStudents().contains("SECRET"));

        System.out.println("12. requirement 20 - only your own courses");
        Conn other = new Conn();
        other.login("teacher5", "teacher5!T");
        check("another teacher cannot build for this course",
                !other.ask(RequestType.EXAM_BUILD_DRAFT,
                        ExamBuildCriteria.manual(course, algebraEasy.subList(0, 2))).isOk());
        check("another teacher cannot edit this exam",
                !other.ask(RequestType.EXAM_EDIT, savedExam).isOk());
        other.close();

        teacher.close();
        server.shutdown();
        db.disconnect();
    }

    static String addQuestion(String text, String topic, DifficultyLevel level) throws Exception {
        Question q = new Question();
        q.setCourseCode(course);
        q.setText(text);
        q.setName("M4Test q " + System.nanoTime());
        q.setTopic(topic);
        q.setDifficulty(level);
        List<Answer> answers = new ArrayList<>();
        for (int i = 1; i <= 4; i++) answers.add(new Answer(i, "option " + i, i == 2));
        q.setAnswers(answers);
        Response r = teacher.ask(RequestType.QUESTION_ADD, q);
        return ((Question) r.getPayload()).getQuestionId();
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(50);
        final HSTSClient client;
        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT,
                    m -> { if (m instanceof Response r) inbox.add(r); }, r -> {});
            client.openConnection();
        }
        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }
        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            return inbox.poll(15, TimeUnit.SECONDS);
        }
        void close() throws Exception { client.closeConnection(); Thread.sleep(120); }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
