package hsts.server.dao;

import hsts.common.entity.Answer;
import hsts.common.entity.Question;
import hsts.common.enums.DifficultyLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * All SQL concerning questions and their answers.
 *
 * <p>This is where the versioning rule from מתווה scenario 2 item 2 actually
 * lives: {@link #createNewVersion} never updates a question in place.</p>
 */
public class QuestionDAO implements IDAO<Question, String> {

    private Connection conn() {
        return DBController.getInstance().getConnection();
    }

    // -----------------------------------------------------------------
    //  Identifier generation
    // -----------------------------------------------------------------

    /**
     * Builds the next free 5-digit question id for a course.
     *
     * <p>Format fixed by system description §3.1: digits 0-2 are the question
     * number, digits 3-4 are the course code. Question 7 of course 05 is
     * {@code 00705}.</p>
     *
     * <p>The maximum is taken across <em>all versions</em>, not just current ones.
     * A number belonging to a deleted or superseded question must never be handed
     * out again, or two different questions would share an identifier and every
     * exam that referenced the old one would silently change meaning.</p>
     */
    public String generateNextQuestionId(String courseCode) throws SQLException {
        String sql = """
            SELECT MAX(CAST(SUBSTRING(question_id, 1, 3) AS UNSIGNED))
            FROM question WHERE course_code = ?""";

        int next = 1;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    next = rs.getInt(1) + 1;   // getInt returns 0 for SQL NULL
                }
            }
        }
        if (next > 999) {
            throw new SQLException(
                "Course " + courseCode + " already has 999 questions, which is all the "
              + "5-digit format allows (3 digits for the question number).");
        }
        return String.format("%03d%s", next, courseCode);
    }

    // -----------------------------------------------------------------
    //  Writing
    // -----------------------------------------------------------------

    /** Saves a brand-new question as version 1. */
    @Override
    public void insert(Question question) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            question.setVersion(1);
            question.setCurrent(true);
            question.setDeleted(false);
            question.setCreatedAt(LocalDateTime.now());

            insertRow(question);
            insertAnswers(question);

            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /**
     * Saves an edit as a <b>new version</b>, leaving the old one in place.
     *
     * <p>Three steps, and all three must happen together or none of them:</p>
     * <ol>
     *   <li>find the highest version this question already has;</li>
     *   <li>clear {@code is_current} on every existing row;</li>
     *   <li>insert the new row as {@code version + 1} with {@code is_current} set.</li>
     * </ol>
     *
     * <p>They run in one transaction. Halfway through step 2 the question has no
     * current version at all - if the connection died there, it would vanish from
     * the bank while still existing in the database, which is exactly the sort of
     * corruption nobody notices until a demo.</p>
     *
     * @return the version number that was written
     */
    public int createNewVersion(Question edited) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int latest = findLatestVersion(edited.getQuestionId());
            if (latest == 0) {
                throw new SQLException("No question with id " + edited.getQuestionId());
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE question SET is_current = FALSE WHERE question_id = ?")) {
                ps.setString(1, edited.getQuestionId());
                ps.executeUpdate();
            }

            edited.setVersion(latest + 1);
            edited.setCurrent(true);
            edited.setCreatedAt(LocalDateTime.now());

            insertRow(edited);
            insertAnswers(edited);

            c.commit();
            return edited.getVersion();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /**
     * Removes a question from the bank - <b>softly</b>.
     *
     * <p>Every version is flagged {@code is_deleted}, so the question disappears
     * from the bank and from future exam building. It is not removed from the
     * database, because exams that already contain it must keep working: a
     * student's marked paper from last month has to keep showing the question she
     * actually answered. A real {@code DELETE} would either fail on the foreign
     * key or destroy that history.</p>
     */
    @Override
    public void delete(String questionId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE question SET is_deleted = TRUE WHERE question_id = ?")) {
            ps.setString(1, questionId);
            ps.executeUpdate();
        }
    }

    @Override
    public void update(Question question) {
        // Refusing this is deliberate. An in-place update would silently destroy
        // the previous wording, which מתווה scenario 2 item 2 forbids.
        throw new UnsupportedOperationException(
                "Questions are versioned - use createNewVersion(question).");
    }

    private void insertRow(Question q) throws SQLException {
        String sql = """
            INSERT INTO question (question_id, version, name, course_code, text, instructions,
                                  topic, difficulty, image, is_current, is_deleted,
                                  author_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, q.getQuestionId());
            ps.setInt(2, q.getVersion());
            ps.setString(3, q.getName() == null ? "" : q.getName());
            ps.setString(4, q.getCourseCode());
            ps.setString(5, q.getText());
            ps.setString(6, q.getInstructions());
            ps.setString(7, q.getTopic());
            ps.setString(8, q.getDifficulty().name());
            ps.setBytes(9, q.getImage());
            ps.setBoolean(10, q.isCurrent());
            ps.setBoolean(11, q.isDeleted());
            ps.setString(12, q.getAuthorId());
            ps.setTimestamp(13, Timestamp.valueOf(q.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    private void insertAnswers(Question q) throws SQLException {
        String sql = """
            INSERT INTO answer (question_id, question_version, answer_no, text, is_correct)
            VALUES (?, ?, ?, ?, ?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (Answer a : q.getAnswers()) {
                ps.setString(1, q.getQuestionId());
                ps.setInt(2, q.getVersion());
                ps.setInt(3, a.getAnswerNo());
                ps.setString(4, a.getText());
                ps.setBoolean(5, a.isCorrect());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int findLatestVersion(String questionId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT MAX(version) FROM question WHERE question_id = ?")) {
            ps.setString(1, questionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // -----------------------------------------------------------------
    //  Reading
    // -----------------------------------------------------------------

    /**
     * The live question bank for one course: current versions, not deleted.
     *
     * <p>Images are deliberately <b>not</b> loaded here. A picture can be
     * hundreds of kilobytes, and a list of 25 questions would drag every one of
     * them across the network to show a table of text. The image is fetched only
     * when a single question is opened.</p>
     */
    public List<Question> findCurrentByCourse(String courseCode) throws SQLException {
        String sql = """
            SELECT q.question_id, q.version, q.name, q.course_code, q.text, q.instructions,
                   q.topic, q.difficulty, q.is_current, q.is_deleted,
                   q.author_id, u.full_name AS author_name, q.created_at
            FROM question q
            JOIN users u ON u.user_id = q.author_id
            WHERE q.course_code = ? AND q.is_current = TRUE AND q.is_deleted = FALSE
            ORDER BY q.question_id""";

        List<Question> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs, false));
                }
            }
        }
        for (Question q : list) {
            q.setAnswers(findAnswers(q.getQuestionId(), q.getVersion()));
        }
        return list;
    }

    /** One exact version, with its answers and its image. */
    public Question findByIdAndVersion(String questionId, int version) throws SQLException {
        String sql = """
            SELECT q.question_id, q.version, q.name, q.course_code, q.text, q.instructions,
                   q.topic, q.difficulty, q.image, q.is_current, q.is_deleted,
                   q.author_id, u.full_name AS author_name, q.created_at
            FROM question q
            JOIN users u ON u.user_id = q.author_id
            WHERE q.question_id = ? AND q.version = ?""";

        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, questionId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Question q = readRow(rs, true);
                q.setAnswers(findAnswers(questionId, version));
                return q;
            }
        }
    }

    /** The current version of a question, whatever number it has reached. */
    @Override
    public Question findById(String questionId) throws SQLException {
        int latest = findLatestVersion(questionId);
        return latest == 0 ? null : findByIdAndVersion(questionId, latest);
    }

    /**
     * Every version of one question, newest first.
     *
     * <p>This is what proves to the course staff that editing kept the old copy -
     * מתווה scenario 2 item 2.</p>
     */
    public List<Question> findAllVersions(String questionId) throws SQLException {
        String sql = """
            SELECT q.question_id, q.version, q.name, q.course_code, q.text, q.instructions,
                   q.topic, q.difficulty, q.is_current, q.is_deleted,
                   q.author_id, u.full_name AS author_name, q.created_at
            FROM question q
            JOIN users u ON u.user_id = q.author_id
            WHERE q.question_id = ?
            ORDER BY q.version DESC""";

        List<Question> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, questionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs, false));
                }
            }
        }
        for (Question q : list) {
            q.setAnswers(findAnswers(q.getQuestionId(), q.getVersion()));
        }
        return list;
    }

    /**
     * Topics already used in a course.
     *
     * <p>Feeds the combo box on the question screen. Offering what already exists
     * is what stops "Fractions", "fractions" and "Fraction" becoming three
     * different topics - which would quietly break automatic exam building,
     * because it selects by exact topic.</p>
     */
    public List<String> findTopicsByCourse(String courseCode) throws SQLException {
        String sql = """
            SELECT DISTINCT topic FROM question
            WHERE course_code = ? AND is_deleted = FALSE
            ORDER BY topic""";
        List<String> topics = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, courseCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    topics.add(rs.getString(1));
                }
            }
        }
        return topics;
    }

    @Override
    public List<Question> findAll() throws SQLException {
        String sql = """
            SELECT q.question_id, q.version, q.name, q.course_code, q.text, q.instructions,
                   q.topic, q.difficulty, q.is_current, q.is_deleted,
                   q.author_id, u.full_name AS author_name, q.created_at
            FROM question q
            JOIN users u ON u.user_id = q.author_id
            WHERE q.is_current = TRUE AND q.is_deleted = FALSE
            ORDER BY q.course_code, q.question_id""";

        List<Question> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(readRow(rs, false));
            }
        }
        return list;
    }

    private List<Answer> findAnswers(String questionId, int version) throws SQLException {
        String sql = """
            SELECT answer_no, text, is_correct FROM answer
            WHERE question_id = ? AND question_version = ?
            ORDER BY answer_no""";
        List<Answer> answers = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, questionId);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    answers.add(new Answer(rs.getInt(1), rs.getString(2), rs.getBoolean(3)));
                }
            }
        }
        return answers;
    }

    private Question readRow(ResultSet rs, boolean withImage) throws SQLException {
        Question q = new Question();
        q.setQuestionId(rs.getString("question_id"));
        q.setVersion(rs.getInt("version"));
        q.setCourseCode(rs.getString("course_code"));
        q.setName(rs.getString("name"));
        q.setText(rs.getString("text"));
        q.setInstructions(rs.getString("instructions"));
        q.setTopic(rs.getString("topic"));
        q.setDifficulty(DifficultyLevel.valueOf(rs.getString("difficulty")));
        q.setCurrent(rs.getBoolean("is_current"));
        q.setDeleted(rs.getBoolean("is_deleted"));
        q.setAuthorId(rs.getString("author_id"));
        q.setAuthorName(rs.getString("author_name"));
        q.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        if (withImage) {
            q.setImage(rs.getBytes("image"));
        }
        return q;
    }
}
