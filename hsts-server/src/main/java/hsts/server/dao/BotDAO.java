package hsts.server.dao;

import hsts.common.entity.Bot;
import hsts.common.entity.BotConversation;
import hsts.common.entity.KnowledgeSource;
import hsts.common.enums.BotStatus;
import hsts.common.enums.KnowledgeSourceType;
import hsts.common.protocol.BotUsage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** All SQL concerning study bots, their knowledge, and what students asked. */
public class BotDAO implements IDAO<Bot, Integer> {

    private java.sql.Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Bots
    // -----------------------------------------------------------------

    private String baseSelect() {
        return """
            SELECT b.bot_id, b.course_code, b.name, b.status, b.created_by,
                   u.full_name AS created_by_name, b.created_at, c.name AS course_name
            FROM bot b
            JOIN users u  ON u.user_id     = b.created_by
            JOIN course c ON c.course_code = b.course_code""";
    }

    private Bot readRow(ResultSet rs) throws SQLException {
        Bot bot = new Bot();
        bot.setBotId(rs.getInt("bot_id"));
        bot.setCourseCode(rs.getString("course_code"));
        bot.setCourseName(rs.getString("course_name"));
        bot.setName(rs.getString("name"));
        bot.setStatus(BotStatus.valueOf(rs.getString("status")));
        bot.setCreatedBy(rs.getString("created_by"));
        bot.setCreatedByName(rs.getString("created_by_name"));
        bot.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return bot;
    }

    /**
     * Creates a bot for a course.
     *
     * <p>A course may have several - a general one and a revision one, say - but
     * only one may be active at a time, which {@code BotController} enforces. That
     * keeps requirement 70 unambiguous for a student: "the course bot" is whichever
     * one is on.</p>
     *
     * @return the new bot with its sources loaded (none yet)
     */
    public Bot insertBot(String courseCode, String name, String createdBy)
            throws SQLException {
        String sql = """
            INSERT INTO bot (course_code, name, status, created_by, created_at)
            VALUES (?, ?, 'INACTIVE', ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, courseCode);
            ps.setString(2, name);
            ps.setString(3, createdBy);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return findById(keys.getInt(1));
            }
        }
    }

    @Override
    public Bot findById(Integer botId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                baseSelect() + " WHERE b.bot_id = ?")) {
            ps.setInt(1, botId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Bot bot = readRow(rs);
                bot.setSources(findSources(bot.getBotId()));
                return bot;
            }
        }
    }

    /**
     * The bot a student would actually reach: the <b>active</b> one, if any.
     *
     * <p>Requirement 70 speaks of "the course bot" in the singular. With several
     * bots allowed per course, the one that answers is the one switched on - and
     * only one may be, so this is unambiguous.</p>
     */
    public Bot findActiveByCourse(String courseCode) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(baseSelect()
                + " WHERE b.course_code = ? AND b.status = 'ACTIVE' LIMIT 1")) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Bot bot = readRow(rs);
                bot.setSources(findSources(bot.getBotId()));
                return bot;
            }
        }
    }

    /** Every bot on one course, active first then by name - the teacher's list. */
    public List<Bot> findAllByCourse(String courseCode) throws SQLException {
        List<Bot> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(baseSelect()
                + " WHERE b.course_code = ? ORDER BY b.status, b.name")) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs));
                }
            }
        }
        for (Bot bot : list) {
            bot.setSources(findSources(bot.getBotId()));
        }
        return list;
    }

    @Override
    public List<Bot> findAll() throws SQLException {
        List<Bot> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                baseSelect() + " ORDER BY c.name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs));
            }
        }
        for (Bot bot : list) {
            bot.setSources(findSources(bot.getBotId()));
        }
        return list;
    }

    /** Requirement 60: turn it on, turn it off. */
    public void setStatus(int botId, BotStatus status) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE bot SET status = ? WHERE bot_id = ?")) {
            ps.setString(1, status.name());
            ps.setInt(2, botId);
            ps.executeUpdate();
        }
    }

    /**
     * Switches off every other bot on the course.
     *
     * <p>Only one bot per course may be active. Done in one statement rather than a
     * read-then-write loop, so two teachers pressing "Turn on" at the same moment
     * cannot leave two of them on.</p>
     *
     * @return how many were switched off, so the teacher can be told
     */
    public int deactivateOthers(String courseCode, int keepBotId) throws SQLException {
        String sql = """
            UPDATE bot SET status = 'INACTIVE'
            WHERE course_code = ? AND bot_id <> ? AND status = 'ACTIVE'""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            ps.setInt(2, keepBotId);
            return ps.executeUpdate();
        }
    }

    /** How many questions have been asked of one bot - what a delete would destroy. */
    public int countConversations(int botId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM bot_conversation WHERE bot_id = ?")) {
            ps.setInt(1, botId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Removes a bot, its material and its conversation history.
     *
     * <p><b>This loses the history, and that is a real cost.</b> Requirement 73 says
     * the system keeps the questions and the answers, and deleting a bot throws its
     * away. The alternative - refusing to delete anything that has ever been used -
     * was rejected by the customer, who asked for a plain delete. So the count is
     * shown before the deletion happens and the tension is recorded in
     * {@code docs/03_document_updates.md} rather than glossed over.</p>
     *
     * <p>Conversations first: {@code knowledge_source} cascades but
     * {@code bot_conversation} does not, and its foreign key would otherwise refuse
     * the delete. All three in one transaction, so a failure leaves the bot whole
     * rather than half-deleted.</p>
     *
     * @return how many conversations were destroyed
     */
    public int deleteBotAndHistory(int botId) throws SQLException {
        java.sql.Connection conn = conn();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int lost;
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM bot_conversation WHERE bot_id = ?")) {
                ps.setInt(1, botId);
                lost = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM knowledge_source WHERE bot_id = ?")) {
                ps.setInt(1, botId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM bot WHERE bot_id = ?")) {
                ps.setInt(1, botId);
                ps.executeUpdate();
            }
            conn.commit();
            return lost;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    public void renameBot(int botId, String name) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE bot SET name = ? WHERE bot_id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, botId);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    //  Knowledge sources
    // -----------------------------------------------------------------

    public List<KnowledgeSource> findSources(int botId) throws SQLException {
        String sql = """
            SELECT k.source_id, k.bot_id, k.type, k.title, k.content, k.added_by,
                   u.full_name AS added_by_name, k.added_at
            FROM knowledge_source k
            JOIN users u ON u.user_id = k.added_by
            WHERE k.bot_id = ? ORDER BY k.added_at, k.source_id""";
        List<KnowledgeSource> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, botId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    KnowledgeSource s = new KnowledgeSource();
                    s.setSourceId(rs.getInt("source_id"));
                    s.setBotId(rs.getInt("bot_id"));
                    s.setType(KnowledgeSourceType.valueOf(rs.getString("type")));
                    s.setTitle(rs.getString("title"));
                    s.setContent(rs.getString("content"));
                    s.setAddedBy(rs.getString("added_by"));
                    s.setAddedByName(rs.getString("added_by_name"));
                    s.setAddedAt(rs.getTimestamp("added_at").toLocalDateTime());
                    list.add(s);
                }
            }
        }
        return list;
    }

    public int insertSource(int botId, KnowledgeSourceType type, String title,
                            String content, String addedBy) throws SQLException {
        String sql = """
            INSERT INTO knowledge_source (bot_id, type, title, content, added_by, added_at)
            VALUES (?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, botId);
            ps.setString(2, type.name());
            ps.setString(3, title);
            ps.setString(4, content);
            ps.setString(5, addedBy);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    public KnowledgeSource findSource(int sourceId) throws SQLException {
        String sql = """
            SELECT k.source_id, k.bot_id, k.type, k.title, k.content, k.added_by,
                   u.full_name AS added_by_name, k.added_at
            FROM knowledge_source k
            JOIN users u ON u.user_id = k.added_by
            WHERE k.source_id = ?""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                KnowledgeSource s = new KnowledgeSource();
                s.setSourceId(rs.getInt("source_id"));
                s.setBotId(rs.getInt("bot_id"));
                s.setType(KnowledgeSourceType.valueOf(rs.getString("type")));
                s.setTitle(rs.getString("title"));
                s.setContent(rs.getString("content"));
                s.setAddedBy(rs.getString("added_by"));
                s.setAddedByName(rs.getString("added_by_name"));
                s.setAddedAt(rs.getTimestamp("added_at").toLocalDateTime());
                return s;
            }
        }
    }

    /** מתווה 13 item 2: sources can be edited, which includes removing one. */
    public boolean deleteSource(int sourceId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM knowledge_source WHERE source_id = ?")) {
            ps.setInt(1, sourceId);
            return ps.executeUpdate() > 0;
        }
    }

    public void updateSource(int sourceId, String title, String content) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE knowledge_source SET title = ?, content = ? WHERE source_id = ?")) {
            ps.setString(1, title);
            ps.setString(2, content);
            ps.setInt(3, sourceId);
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    //  Conversations
    // -----------------------------------------------------------------

    /** Requirement 73: the question and the answer are both kept. */
    public int insertConversation(int botId, String studentId, String question,
                                  String answer) throws SQLException {
        String sql = """
            INSERT INTO bot_conversation (bot_id, student_id, question_text,
                                          answer_text, asked_at)
            VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, botId);
            ps.setString(2, studentId);
            ps.setString(3, question);
            ps.setString(4, answer);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /** Requirement 74: her own history, newest first. */
    public List<BotConversation> findByStudent(String studentId) throws SQLException {
        String sql = """
            SELECT bc.conv_id, bc.bot_id, bc.student_id, u.full_name AS student_name,
                   bc.question_text, bc.answer_text, bc.asked_at, c.name AS course_name
            FROM bot_conversation bc
            JOIN users u  ON u.user_id     = bc.student_id
            JOIN bot b    ON b.bot_id      = bc.bot_id
            JOIN course c ON c.course_code = b.course_code
            WHERE bc.student_id = ? ORDER BY bc.asked_at DESC, bc.conv_id DESC""";
        return queryConversations(sql, ps -> ps.setString(1, studentId), false);
    }

    /**
     * Requirement 75: what a teacher may see - counts and wordings, never names.
     *
     * <p>The anonymising happens here, in the query and in
     * {@link BotConversation#anonymise()}, so a name is never on the object that
     * goes onto the wire. A screen that simply does not draw the name would leave
     * it sitting in the client's memory, which is not the same thing.</p>
     */
    public BotUsage usageOf(Bot bot) throws SQLException {
        int total = 0;
        int distinct = 0;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) , COUNT(DISTINCT student_id) "
              + "FROM bot_conversation WHERE bot_id = ?")) {
            ps.setInt(1, bot.getBotId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getInt(1);
                    distinct = rs.getInt(2);
                }
            }
        }

        List<BotUsage.CommonQuestion> common = new ArrayList<>();
        String commonSql = """
            SELECT question_text, COUNT(*) AS times
            FROM bot_conversation WHERE bot_id = ?
            GROUP BY question_text ORDER BY times DESC, question_text LIMIT 20""";
        try (PreparedStatement ps = conn().prepareStatement(commonSql)) {
            ps.setInt(1, bot.getBotId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    common.add(new BotUsage.CommonQuestion(
                            rs.getString("question_text"), rs.getInt("times")));
                }
            }
        }

        String recentSql = """
            SELECT bc.conv_id, bc.bot_id, bc.student_id, u.full_name AS student_name,
                   bc.question_text, bc.answer_text, bc.asked_at, c.name AS course_name
            FROM bot_conversation bc
            JOIN users u  ON u.user_id     = bc.student_id
            JOIN bot b    ON b.bot_id      = bc.bot_id
            JOIN course c ON c.course_code = b.course_code
            WHERE bc.bot_id = ? ORDER BY bc.asked_at DESC, bc.conv_id DESC LIMIT 50""";
        List<BotConversation> recent = queryConversations(recentSql,
                ps -> ps.setInt(1, bot.getBotId()), true);

        return new BotUsage(bot.getName(), bot.getCourseName(), total, distinct,
                common, recent);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<BotConversation> queryConversations(String sql, Binder binder,
                                                     boolean anonymise)
            throws SQLException {
        List<BotConversation> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BotConversation c = new BotConversation();
                    c.setConvId(rs.getInt("conv_id"));
                    c.setBotId(rs.getInt("bot_id"));
                    c.setCourseName(rs.getString("course_name"));
                    c.setStudentId(rs.getString("student_id"));
                    c.setStudentName(rs.getString("student_name"));
                    c.setQuestion(rs.getString("question_text"));
                    c.setAnswer(rs.getString("answer_text"));
                    c.setAskedAt(rs.getTimestamp("asked_at").toLocalDateTime());
                    if (anonymise) {
                        c.anonymise();
                    }
                    list.add(c);
                }
            }
        }
        return list;
    }

    // -----------------------------------------------------------------

    @Override
    public void insert(Bot bot) {
        throw new UnsupportedOperationException(
                "Bots are created by insertBot(courseCode, name, createdBy).");
    }

    @Override
    public void update(Bot bot) {
        throw new UnsupportedOperationException("Use setStatus or renameBot.");
    }

    /** Use {@link #deleteBotAndHistory}, which reports what the deletion destroyed. */
    @Override
    public void delete(Integer botId) throws SQLException {
        deleteBotAndHistory(botId);
    }
}
