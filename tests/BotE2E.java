import hsts.client.net.HSTSClient;
import hsts.common.entity.*;
import hsts.common.enums.BotStatus;
import hsts.common.protocol.*;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.*;
import java.util.concurrent.*;

/**
 * The bot end to end, through the real server and the real Gemini.
 *
 * <p>{@code M14Test} proves every rule against a stub. This proves the wiring: a
 * teacher builds a bot, switches it on, and a student on a client connection gets a
 * genuine answer back. One real API call.</p>
 *
 * <p>Run by hand, not as part of the regression - the suites must not depend on the
 * network or spend anything.</p>
 */
public class BotE2E {

    private static final int PORT = freePort();

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

    public static void main(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(l -> {});
        server.setPort(PORT);
        server.listen();       // the REAL Gemini service, not a stub

        System.out.println("bot service : " + server.getStudyBotService().getDescription());
        System.out.println();

        String course = "02";                  // Algebra: coordinator1 and teacher2
        wipeBot(db, course);

        Conn teacher = new Conn();
        teacher.login("coordinator1", "coordinator1!C");

        System.out.println("1. teacher creates the bot");
        Response created = teacher.ask(RequestType.BOT_CREATE,
                new BotCreateRequest(course, "Algebra helper"));
        say(created);
        Bot bot = (Bot) created.getPayload();

        System.out.println("2. teacher gives it the course question bank");
        say(teacher.ask(RequestType.BOT_ADD_SOURCE,
                SourceRequest.questionBank(bot.getBotId(), "Algebra question bank")));

        System.out.println("3. teacher adds her own notes");
        say(teacher.ask(RequestType.BOT_ADD_SOURCE, SourceRequest.text(bot.getBotId(),
                "Solving linear equations",
                "To solve a linear equation, do the same thing to both sides until x is "
              + "alone. Add or subtract first, then divide. For example 3x + 6 = 18: "
              + "subtract 6 from both sides to get 3x = 12, then divide both sides by 3 "
              + "to get x = 4. Always check by putting the answer back in.")));

        System.out.println("4. teacher switches it on");
        say(teacher.ask(RequestType.BOT_SET_STATUS,
                new BotStatusRequest(bot.getBotId(), BotStatus.ACTIVE)));

        System.out.println("5. a student asks it something real");
        String username = enrolledIn(db, course);
        Conn student = new Conn();
        Student me = (Student) student.login(username, username + "!S").getPayload();
        System.out.println("   signed in as " + me.getFullName());

        String question = "How do I solve 5x + 3 = 23? Show the steps.";
        System.out.println("   Q: " + question);
        long started = System.currentTimeMillis();
        Response answered = student.ask(RequestType.BOT_ASK,
                new BotQuestion(course, question));

        if (!answered.isOk()) {
            System.out.println();
            System.out.println("   REFUSED: " + answered.getMessage());
            System.out.println();
            System.out.println("==== end to end FAILED ====");
            System.exit(1);
        }

        BotConversation reply = (BotConversation) answered.getPayload();
        System.out.println("   A (" + (System.currentTimeMillis() - started) + " ms):");
        System.out.println();
        for (String line : reply.getAnswer().split("\n")) {
            System.out.println("      " + line);
        }
        System.out.println();

        System.out.println("6. it was stored, and she can read it back (requirement 74)");
        @SuppressWarnings("unchecked")
        List<BotConversation> history = (List<BotConversation>)
                student.ask(RequestType.BOT_MY_HISTORY, null).getPayload();
        System.out.println("   " + history.size() + " in her history");
        System.out.println("   most recent: " + history.get(0).getQuestion());

        System.out.println("7. the teacher sees usage, with no names (requirement 75)");
        BotUsage usage = (BotUsage) teacher.ask(RequestType.BOT_USAGE, bot.getBotId())
                .getPayload();
        System.out.println("   " + usage.getTotalQuestions() + " question(s) from "
                         + usage.getDistinctStudents() + " student(s)");
        boolean anyName = usage.getRecent().stream()
                .anyMatch(c -> c.getStudentName() != null || c.getStudentId() != null);
        System.out.println("   any identity leaked? " + (anyName ? "YES - BUG" : "no"));

        System.out.println();
        System.out.println(anyName ? "==== end to end FAILED ===="
                                   : "==== end to end WORKS ====");

        teacher.close();
        student.close();
        server.stopListening();
        server.close();
        System.exit(anyName ? 1 : 0);
    }

    static void say(Response r) {
        System.out.println("   " + (r.isOk() ? "ok: " : "REFUSED: ") + r.getMessage());
        if (!r.isOk()) {
            System.exit(1);
        }
    }

    static void wipeBot(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("DELETE bc FROM bot_conversation bc JOIN bot b "
                    + "ON b.bot_id = bc.bot_id WHERE b.course_code = '" + courseCode + "'");
            st.executeUpdate("DELETE ks FROM knowledge_source ks JOIN bot b "
                    + "ON b.bot_id = ks.bot_id WHERE b.course_code = '" + courseCode + "'");
            st.executeUpdate("DELETE FROM bot WHERE course_code = '" + courseCode + "'");
        }
    }

    static String enrolledIn(DBController db, String courseCode) throws Exception {
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT u.username FROM users u "
                     + "JOIN course_student cs ON cs.user_id = u.user_id "
                     + "WHERE cs.course_code='" + courseCode + "' ORDER BY u.username LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static class Conn {
        final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(200);
        final HSTSClient client;

        Conn() throws Exception {
            client = new HSTSClient("localhost", PORT, m -> {
                if (m instanceof Response r) inbox.add(r);
            }, r -> {});
            client.openConnection();
        }

        Response login(String u, String p) throws Exception {
            return ask(RequestType.LOGIN, new Credentials(u, p));
        }

        Response ask(RequestType t, Object payload) throws Exception {
            client.sendToServer(new Request(t, payload, "r"));
            Response r = inbox.poll(60, TimeUnit.SECONDS);
            if (r == null) {
                throw new IllegalStateException("no reply to " + t);
            }
            return r;
        }

        void close() throws Exception { client.closeConnection(); Thread.sleep(150); }
    }
}
