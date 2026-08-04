import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.DifficultyLevel;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * Milestone 3 verification: question bank, versioning, soft delete, permissions.
 * Usage: java -cp "G1_Server.jar;G1_Client.jar;." M3Test <mysqlUser> <mysqlPassword>
 */
public class M3Test {

    private static final int PORT = freePort();
    private static int passed = 0, failed = 0;
    private static Conn teacher;   // teacher1, teaches course 01

    /**
     * Always exits, even when something throws.
     *
     * <p>Without this the harness hangs: if the body throws, the main thread dies
     * but the server's listening thread is not a daemon, so the JVM stays alive
     * forever with no output. That cost a four-minute timeout once already.</p>
     */
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
            System.out.println("   [FAIL] the harness itself threw:");
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

        int questionsBefore = db.countRows("question");
        System.out.println("starting with " + questionsBefore + " question row(s) in the database");

        System.out.println("1. requests are refused before signing in");
        Conn anon = new Conn();
        check("refused when not signed in",
                !anon.ask(RequestType.COURSE_LIST_MINE, null).isOk());
        anon.close();

        teacher = new Conn();
        check("teacher1 signed in", teacher.login("teacher1", "teacher1!T").isOk());

        System.out.println("2. the teacher's courses come from the database");
        Response courses = teacher.ask(RequestType.COURSE_LIST_MINE, null);
        @SuppressWarnings("unchecked")
        List<Course> myCourses = (List<Course>) courses.getPayload();
        System.out.println("   " + myCourses.size() + " course(s): " + myCourses);
        check("teacher1 has at least one course", !myCourses.isEmpty());
        String course = myCourses.get(0).getCourseCode();

        System.out.println("0. A QUESTION NEEDS A NAME");
        // Asked for alongside the exam name: a list of forty questions showing their
        // full text is a wall to read, and one showing "00101" says nothing at all.
        Question unnamed = new Question();
        unnamed.setCourseCode(course);
        unnamed.setText("Which of these is a prime number?");
        unnamed.setTopic("M3 names");
        unnamed.setDifficulty(DifficultyLevel.EASY);
        List<Answer> options = new ArrayList<>();
        for (int n = 1; n <= 4; n++) options.add(new Answer(n, "option " + n, n == 2));
        unnamed.setAnswers(options);

        Response noName = teacher.ask(RequestType.QUESTION_ADD, unnamed);
        check("a question with no name is refused", !noName.isOk());
        System.out.println("   " + noName.getMessage());
        check("and it says to give it one", noName.getMessage().contains("name"));

        unnamed.setName("   ");
        check("spaces are not a name", !teacher.ask(RequestType.QUESTION_ADD, unnamed).isOk());

        unnamed.setName("x".repeat(200));
        check("and one too long for a list is refused",
                !teacher.ask(RequestType.QUESTION_ADD, unnamed).isOk());

        unnamed.setName("  Prime numbers  ");
        Response namedQuestion = teacher.ask(RequestType.QUESTION_ADD, unnamed);
        check("with a name it is added", namedQuestion.isOk());
        Question savedQ = (Question) namedQuestion.getPayload();
        check("trimmed on the way in", "Prime numbers".equals(savedQ.getName()));
        check("the 5-digit number is still there",
                savedQ.getQuestionId() != null && savedQ.getQuestionId().length() == 5);
        check("and a list row reads \"name · number\"",
                savedQ.describe().equals("Prime numbers  ·  " + savedQ.getQuestionId()));
        System.out.println("   " + namedQuestion.getMessage());

        System.out.println("3. add a question");
        Question q = build("What is the sum of the angles in a triangle?",
                "Fractions", DifficultyLevel.EASY,
                new String[]{"90", "180", "270", "360"}, 2);
        q.setCourseCode(course);
        Response added = teacher.ask(RequestType.QUESTION_ADD, q);
        check("add accepted", added.isOk());
        Question saved = (Question) added.getPayload();
        System.out.println("   id = " + saved.getQuestionId() + ", version = " + saved.getVersion());
        check("id is exactly 5 digits", saved.getQuestionId().matches("\\d{5}"));
        check("digits 3-4 are the course code",
                saved.getQuestionId().substring(3).equals(course));
        check("digits 0-2 are a question number",
                saved.getQuestionId().substring(0, 3).matches("\\d{3}"));
        check("starts at version 1", saved.getVersion() == 1);

        System.out.println("4. validation is enforced on the SERVER, not just the screen");
        check("empty text refused",
                !teacher.ask(RequestType.QUESTION_ADD,
                        courseOf(build("", "T", DifficultyLevel.EASY, four(), 1), course)).isOk());
        check("empty topic refused",
                !teacher.ask(RequestType.QUESTION_ADD,
                        courseOf(build("text", "", DifficultyLevel.EASY, four(), 1), course)).isOk());
        Question threeAnswers = courseOf(build("text","T",DifficultyLevel.EASY, four(), 1), course);
        threeAnswers.getAnswers().remove(3);
        check("three answers refused",
                !teacher.ask(RequestType.QUESTION_ADD, threeAnswers).isOk());
        Question noneCorrect = courseOf(build("text","T",DifficultyLevel.EASY, four(), 0), course);
        check("no correct answer refused",
                !teacher.ask(RequestType.QUESTION_ADD, noneCorrect).isOk());
        Question twoCorrect = courseOf(build("text","T",DifficultyLevel.EASY, four(), 1), course);
        twoCorrect.getAnswers().get(2).setCorrect(true);
        Response twoResp = teacher.ask(RequestType.QUESTION_ADD, twoCorrect);
        check("two correct answers refused", !twoResp.isOk());
        System.out.println("   message: " + twoResp.getMessage());

        System.out.println("5. EDIT creates a new version and keeps the old one");
        Question edit = build("What is the sum of the angles in a triangle? (revised)",
                "Angles", DifficultyLevel.MEDIUM,
                new String[]{"90", "180", "270", "360"}, 2);
        edit.setQuestionId(saved.getQuestionId());
        Response edited = teacher.ask(RequestType.QUESTION_EDIT, edit);
        check("edit accepted", edited.isOk());
        System.out.println("   " + edited.getMessage());

        Response versions = teacher.ask(RequestType.QUESTION_VERSIONS,
                new QuestionRef(saved.getQuestionId()));
        @SuppressWarnings("unchecked")
        List<Question> allVersions = (List<Question>) versions.getPayload();
        check("two versions now exist", allVersions.size() == 2);
        check("newest is version 2 and is current",
                allVersions.get(0).getVersion() == 2 && allVersions.get(0).isCurrent());
        check("version 1 still exists and is no longer current",
                allVersions.get(1).getVersion() == 1 && !allVersions.get(1).isCurrent());

        System.out.println("6. the OLD version still holds its ORIGINAL wording");
        Response v1 = teacher.ask(RequestType.QUESTION_GET,
                new QuestionRef(saved.getQuestionId(), 1));
        Question original = (Question) v1.getPayload();
        System.out.println("   v1 text : " + original.getText());
        System.out.println("   v1 topic: " + original.getTopic());
        check("v1 text unchanged",
                original.getText().equals("What is the sum of the angles in a triangle?"));
        check("v1 topic unchanged", original.getTopic().equals("Fractions"));
        check("v1 difficulty unchanged", original.getDifficulty() == DifficultyLevel.EASY);

        System.out.println("7. the bank shows only the CURRENT version, once");
        Response list = teacher.ask(RequestType.QUESTION_LIST_BY_COURSE, course);
        @SuppressWarnings("unchecked")
        List<Question> bank = (List<Question>) list.getPayload();
        long appearances = bank.stream()
                .filter(x -> x.getQuestionId().equals(saved.getQuestionId())).count();
        check("appears exactly once in the bank", appearances == 1);
        check("and it is version 2", bank.stream()
                .filter(x -> x.getQuestionId().equals(saved.getQuestionId()))
                .findFirst().get().getVersion() == 2);

        System.out.println("8. topics list picks up the new topic");
        Response topics = teacher.ask(RequestType.QUESTION_TOPICS, course);
        @SuppressWarnings("unchecked")
        List<String> topicList = (List<String>) topics.getPayload();
        System.out.println("   " + topicList);
        check("contains the edited topic", topicList.contains("Angles"));

        System.out.println("9. pictures survive the round trip as bytes");
        byte[] png = new byte[]{(byte)0x89,'P','N','G',13,10,26,10, 1,2,3,4,5,6,7,8};
        Question withImage = courseOf(build("Question with a picture","Angles",
                DifficultyLevel.HARD, four(), 1), course);
        withImage.setImage(png);
        Response imgAdded = teacher.ask(RequestType.QUESTION_ADD, withImage);
        check("question with a picture accepted", imgAdded.isOk());
        Question back = (Question) teacher.ask(RequestType.QUESTION_GET,
                new QuestionRef(((Question) imgAdded.getPayload()).getQuestionId())).getPayload();
        check("picture came back byte-for-byte", Arrays.equals(png, back.getImage()));
        check("list view omits pictures to stay small", ((List<Question>)
                teacher.ask(RequestType.QUESTION_LIST_BY_COURSE, course).getPayload())
                .stream().noneMatch(Question::hasImage));

        System.out.println("10. SOFT delete - gone from the bank, still in the database");
        int rowsBeforeDelete = db.countRows("question");
        Response deleted = teacher.ask(RequestType.QUESTION_DELETE, saved.getQuestionId());
        check("delete accepted", deleted.isOk());
        @SuppressWarnings("unchecked")
        List<Question> afterDelete = (List<Question>)
                teacher.ask(RequestType.QUESTION_LIST_BY_COURSE, course).getPayload();
        check("no longer in the bank", afterDelete.stream()
                .noneMatch(x -> x.getQuestionId().equals(saved.getQuestionId())));
        check("rows were NOT removed from the database",
                db.countRows("question") == rowsBeforeDelete);
        check("its versions are still retrievable for old exams",
                teacher.ask(RequestType.QUESTION_VERSIONS,
                        new QuestionRef(saved.getQuestionId())).isOk());

        System.out.println("11. a deleted number is never handed out again");
        Question fresh = courseOf(build("A later question","Angles",
                DifficultyLevel.EASY, four(), 1), course);
        Question freshSaved = (Question) teacher.ask(RequestType.QUESTION_ADD, fresh).getPayload();
        System.out.println("   new id " + freshSaved.getQuestionId()
                         + " vs deleted " + saved.getQuestionId());
        check("new id differs from the deleted one",
                !freshSaved.getQuestionId().equals(saved.getQuestionId()));

        System.out.println("12. requirement 14 - only your own courses");
        Conn other = new Conn();
        other.login("teacher5", "teacher5!T");        // teaches course 05, not 01
        Response refused = other.ask(RequestType.QUESTION_LIST_BY_COURSE, course);
        check("another teacher cannot read this course's bank", !refused.isOk());
        System.out.println("   message: " + refused.getMessage());
        Response refusedAdd = other.ask(RequestType.QUESTION_ADD,
                courseOf(build("sneaky","T",DifficultyLevel.EASY, four(), 1), course));
        check("another teacher cannot add to this course", !refusedAdd.isOk());
        other.close();

        System.out.println("13. a student has no question bank at all");
        Conn student = new Conn();
        student.login("student1", "student1!S");
        check("student refused the course list",
                !student.ask(RequestType.COURSE_LIST_MINE, null).isOk());
        student.close();

        teacher.close();
        server.shutdown();
        db.disconnect();
    }

    static String[] four() { return new String[]{"a","b","c","d"}; }

    static Question courseOf(Question q, String course) { q.setCourseCode(course); return q; }

    /** correctIndex is 1-based; 0 means none correct. */
    static Question build(String text, String topic, DifficultyLevel d,
                          String[] answers, int correctIndex) {
        Question q = new Question();
        q.setText(text);
        q.setName("M3Test q " + System.nanoTime());
        q.setTopic(topic);
        q.setDifficulty(d);
        List<Answer> list = new ArrayList<>();
        for (int i = 0; i < answers.length; i++) {
            list.add(new Answer(i + 1, answers[i], (i + 1) == correctIndex));
        }
        q.setAnswers(list);
        return q;
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(30);
        final HSTSClient client;
        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT,
                    m -> { if (m instanceof Response r) inbox.add(r); }, r -> {});
            client.openConnection();
        }
        Response login(String u, String p) throws Exception {
            return send(RequestType.LOGIN, new Credentials(u, p));
        }
        Response ask(RequestType t, Object payload) throws Exception { return send(t, payload); }
        private Response send(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            return inbox.poll(10, TimeUnit.SECONDS);
        }
        void close() throws Exception { client.closeConnection(); Thread.sleep(120); }
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("   [PASS] " + what); }
        else    { failed++; System.out.println("   [FAIL] " + what); }
    }
}
