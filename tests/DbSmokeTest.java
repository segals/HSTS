import hsts.server.dao.DBController;

/**
 * Milestone 1 verification of the database half, with no GUI involved.
 * Usage: java -cp "G1_Server.jar;." DbSmokeTest <mysqlUser> <mysqlPassword>
 */
public class DbSmokeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String user = args[0];
        String password = args.length > 1 ? args[1] : "";

        DBController db = DBController.getInstance();

        System.out.println("1. connect + CREATE DATABASE IF NOT EXISTS");
        db.connect("localhost", 3306, "hsts", user, password);
        check("connected", db.isConnected());
        System.out.println("   url = " + db.getDescribedUrl());

        System.out.println("2. MySQL version");
        String version = db.getServerVersion();
        System.out.println("   " + version);
        check("version is not blank", version != null && !version.isBlank());

        System.out.println("3. create skeleton schema + seed test user");
        db.ensureSkeletonSchema();
        System.out.println("   ok");

        System.out.println("4. read the seeded row back");
        String row = db.readSkeletonRow();
        System.out.println("   " + row);
        check("row found", row != null && row.contains("walking skeleton"));

        System.out.println("5. salted login, CORRECT password");
        String name = db.checkSkeletonLogin("teacher1", "teacher1!T");
        System.out.println("   returned: " + name);
        check("correct password accepted", "Test Teacher One".equals(name));

        System.out.println("6. salted login, WRONG password");
        String bad = db.checkSkeletonLogin("teacher1", "not-the-password");
        System.out.println("   returned: " + bad);
        check("wrong password rejected", bad == null);

        System.out.println("7. salted login, user that does not exist");
        String missing = db.checkSkeletonLogin("nobody", "whatever");
        check("unknown user rejected", missing == null);

        System.out.println("8. re-running the seed is safe (idempotent)");
        db.ensureSkeletonSchema();
        check("second run still logs in", "Test Teacher One".equals(
                db.checkSkeletonLogin("teacher1", "teacher1!T")));

        db.disconnect();
        check("disconnected cleanly", !db.isConnected());

        System.out.println();
        System.out.println("==== passed " + passed + ", failed " + failed + " ====");
        if (failed > 0) {
            System.exit(1);
        }
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
