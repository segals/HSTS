import hsts.server.dao.DBController;

/** Runs the schema step against an older database and reports what it did. */
public class MigrateCheck {
    public static void main(String[] args) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", args[0], args.length > 1 ? args[1] : "");
        db.initialiseSchema();
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery(
                 "SELECT table_name, column_name, column_type FROM information_schema.columns "
               + "WHERE table_schema = DATABASE() AND (column_name = 'results_seen_at' "
               + "   OR (table_name = 'grade' AND column_name = 'approved_at'))")) {
            while (rs.next()) {
                System.out.println("   " + rs.getString(1) + "." + rs.getString(2)
                        + " = " + rs.getString(3));
            }
        }
        db.disconnect();
    }
}
