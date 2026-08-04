import hsts.server.dao.DBController;
import hsts.server.seed.SeedRunner;

public class ResetNow {
    public static void main(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();
        System.out.println("before: users=" + db.countRows("users")
                + " questions=" + db.countRows("question")
                + " exams=" + db.countRows("exam"));
        System.out.println(SeedRunner.resetAndSeed());
        System.out.println("after:  users=" + db.countRows("users")
                + " questions=" + db.countRows("question")
                + " exams=" + db.countRows("exam")
                + " subjects=" + db.countRows("subject")
                + " courses=" + db.countRows("course"));
        db.disconnect();
    }
}
