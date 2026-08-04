import hsts.server.dao.DBController;
import java.sql.*;

public class WhoIsEnrolled {
    public static void main(String[] a) throws Exception {
        DBController db = DBController.getInstance();
        db.connect("localhost", 3306, "hsts", a[0], a.length > 1 ? a[1] : "");
        try (Statement st = db.getConnection().createStatement()) {
            System.out.println("=== students enrolled in course 01 (Plane Geometry, teacher1) ===");
            try (ResultSet rs = st.executeQuery(
                "SELECT u.username, u.user_id, u.full_name FROM users u "
              + "JOIN course_student cs ON cs.user_id = u.user_id "
              + "WHERE cs.course_code = '01' ORDER BY CAST(SUBSTRING(u.username,8) AS UNSIGNED)")) {
                while (rs.next())
                    System.out.printf("  %-10s  ID %-10s  %s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3));
            }
            System.out.println();
            System.out.println("=== how many students per course ===");
            try (ResultSet rs = st.executeQuery(
                "SELECT c.course_code, c.name, COUNT(*) FROM course c "
              + "JOIN course_student cs ON cs.course_code = c.course_code "
              + "GROUP BY c.course_code, c.name ORDER BY c.course_code")) {
                while (rs.next())
                    System.out.printf("  %s  %-18s %d students%n",
                            rs.getString(1), rs.getString(2), rs.getInt(3));
            }
        }
        db.disconnect();
    }
}
