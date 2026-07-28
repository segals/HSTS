package hsts.server.dao;

import java.sql.SQLException;
import java.util.List;

/**
 * The <b>DAO pattern</b> from the submitted class diagram.
 *
 * <p>Every entity that is stored gets a DAO, and all SQL for that entity lives
 * inside it. Controllers never contain SQL and never see a {@code ResultSet} -
 * they ask for objects and get objects.</p>
 *
 * <h2>Why this is worth the extra interface</h2>
 *
 * <p>Requirement 8 says the move to a web-based version 2 must be cheap. If SQL
 * were scattered through the controllers, that move would mean touching every
 * controller. With the SQL confined to this layer, the controllers do not care
 * whether the data came from MySQL, from a web service, or from a test double.</p>
 *
 * @param <T>  the entity type
 * @param <ID> the type of that entity's identifier
 */
public interface IDAO<T, ID> {

    void insert(T entity) throws SQLException;

    void update(T entity) throws SQLException;

    /**
     * Removes an entity.
     *
     * <p>Some DAOs implement this as a <em>soft</em> delete - {@code QuestionDAO}
     * will, because a question that already appears in a marked exam must never
     * really disappear.</p>
     */
    void delete(ID id) throws SQLException;

    T findById(ID id) throws SQLException;

    List<T> findAll() throws SQLException;
}
