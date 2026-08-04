package hsts.server.dao;

import hsts.common.entity.ActivityEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The record of what the staff have done.
 *
 * <p>Written once per action and never updated - a log that can be edited is not a
 * log. Nothing deletes from it either; the principal reads the most recent
 * entries and the rest stays.</p>
 */
public class ActivityDAO {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    /**
     * Records one action.
     *
     * <p>Deliberately swallows its own failure into an exception the caller is
     * expected to log and ignore: a full disk must not be the reason a teacher
     * cannot approve a mark. The action has already happened when this runs.</p>
     */
    public void record(String userId, String role, String action, String detail)
            throws SQLException {
        String sql = """
            INSERT INTO activity_log (at, user_id, role, action, detail)
            VALUES (NOW(3), ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, role);
            ps.setString(3, action);
            // Long enough to be useful, short enough not to fill the column with a
            // stack trace if a message ever grows one.
            ps.setString(4, detail == null ? null
                    : detail.length() > 400 ? detail.substring(0, 400) : detail);
            ps.executeUpdate();
        }
    }

    /** The most recent entries, newest first. */
    public List<ActivityEntry> recent(int howMany) throws SQLException {
        String sql = """
            SELECT a.entry_id, a.at, a.user_id, a.role, a.action, a.detail,
                   u.full_name AS user_name
            FROM activity_log a
            JOIN users u ON u.user_id = a.user_id
            ORDER BY a.at DESC, a.entry_id DESC
            LIMIT ?""";
        List<ActivityEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, howMany);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ActivityEntry entry = new ActivityEntry();
                    entry.setEntryId(rs.getLong("entry_id"));
                    entry.setAt(rs.getTimestamp("at").toLocalDateTime());
                    entry.setUserId(rs.getString("user_id"));
                    entry.setUserName(rs.getString("user_name"));
                    entry.setRole(rs.getString("role"));
                    entry.setAction(rs.getString("action"));
                    entry.setDetail(rs.getString("detail"));
                    list.add(entry);
                }
            }
        }
        return list;
    }
}
