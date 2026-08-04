import hsts.client.net.HSTSClient;
import hsts.common.protocol.Credentials;
import hsts.common.protocol.Request;
import hsts.common.protocol.RequestType;
import hsts.common.protocol.Response;
import hsts.server.HSTSServer;
import hsts.server.dao.DBController;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Milestone 1 end-to-end check: a real client process talking to a real server
 * over a real TCP socket, with a real MySQL behind it.
 *
 * Talks to HSTSClient directly rather than through ClientController, because
 * ClientController wraps every callback in Platform.runLater and that needs a
 * running JavaFX toolkit. The GUI run exercises that wrapper; this exercises
 * the network and database path underneath it.
 *
 * Usage: java -cp "G1_Server.jar;G1_Client.jar;." E2ETest <mysqlUser> <mysqlPassword>
 */
public class E2ETest {

    private static final int TEST_PORT = 15555;

    private static int passed = 0;
    private static int failed = 0;

    private static final BlockingQueue<Response> inbox = new ArrayBlockingQueue<>(20);

    public static void main(String[] args) throws Exception {
        String user = args[0];
        String password = args.length > 1 ? args[1] : "";

        System.out.println("1. server side: connect to MySQL and start listening");
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", user, password);
        db.ensureSkeletonSchema();

        HSTSServer server = HSTSServer.getInstance();
        server.setLogSink(line -> System.out.println("   server> " + line));
        server.setPort(TEST_PORT);
        server.listen();
        check("server is listening", server.isListening());

        System.out.println("2. client side: open a TCP connection");
        HSTSClient client = new HSTSClient(
                "localhost", TEST_PORT,
                msg -> { if (msg instanceof Response r) inbox.add(r); },
                reason -> System.out.println("   client> connection closed: " + reason));
        client.openConnection();
        check("client is connected", client.isConnected());
        check("server counted the client", server.getNumberOfClients() == 1);

        System.out.println("3. PING - full round trip through the database");
        client.sendToServer(new Request(RequestType.PING, null, "ping"));
        Response ping = await();
        System.out.println("   payload: " + (ping == null ? "<timeout>" : ping.getPayload()));
        check("ping answered", ping != null);
        check("ping is OK", ping != null && ping.isOk());
        check("ping carried the database row",
                ping != null && String.valueOf(ping.getPayload()).contains("walking skeleton"));
        check("requestId echoed back", ping != null && "ping".equals(ping.getRequestId()));

        System.out.println("4. LOGIN with the correct password");
        client.sendToServer(new Request(RequestType.LOGIN,
                new Credentials("teacher1", "teacher1!T"), "login"));
        Response good = await();
        System.out.println("   message: " + (good == null ? "<timeout>" : good.getMessage()));
        check("login accepted", good != null && good.isOk());
        check("full name returned", good != null && "Test Teacher One".equals(good.getPayload()));

        System.out.println("5. LOGIN with a wrong password");
        client.sendToServer(new Request(RequestType.LOGIN,
                new Credentials("teacher1", "wrong-password"), "login"));
        Response bad = await();
        System.out.println("   message: " + (bad == null ? "<timeout>" : bad.getMessage()));
        check("login refused", bad != null && !bad.isOk());
        check("reason does not leak which field was wrong",
                bad != null && bad.getMessage().equals("Incorrect username or password."));

        System.out.println("6. a junk message must not kill the connection");
        client.sendToServer("this is not a Request object");
        Response junk = await();
        check("junk answered with an error", junk != null && !junk.isOk());
        check("client still connected afterwards", client.isConnected());

        System.out.println("7. shutdown");
        client.closeConnection();
        server.shutdown();
        db.disconnect();
        check("server stopped listening", !server.isListening());

        System.out.println();
        System.out.println("==== passed " + passed + ", failed " + failed + " ====");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static Response await() throws InterruptedException {
        return inbox.poll(10, TimeUnit.SECONDS);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("   [PASS] " + what);
        } else {
            failed++;
            System.out.println("   [FAIL] " + what);
        }
    }
}
