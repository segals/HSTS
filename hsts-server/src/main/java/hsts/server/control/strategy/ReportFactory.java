package hsts.server.control.strategy;

import hsts.common.enums.ReportType;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.UserDAO;

import java.util.EnumMap;
import java.util.Map;

/**
 * Turns a {@link ReportType} into the {@link ReportStrategy} that builds it.
 *
 * <p>The <b>Factory</b> pattern from the submitted class diagram. Without it,
 * {@code ReportController} would carry a chain of {@code if (type == BY_COURSE)}
 * that grows by one branch every time a report is added - and requirement 64 says
 * adding a report should take minimal work.</p>
 *
 * <h2>A map, not a switch</h2>
 *
 * <p>The strategies are built once and held in an {@link EnumMap}. Two things fall
 * out of that which a {@code switch} would not give:</p>
 *
 * <ul>
 *   <li>{@link #isComplete()} can check at start-up that every {@code ReportType}
 *       has a strategy, so a value added to the enum and then forgotten is caught
 *       immediately rather than by a user picking it and getting nothing;</li>
 *   <li>{@link #all()} lets the controller offer the list of reports without
 *       knowing what is on it.</li>
 * </ul>
 *
 * <p><b>Adding a fourth report</b> - "compare subjects", say - is one enum value,
 * one class implementing {@code ReportStrategy}, and one line in the constructor
 * below. Nothing else in the system changes: not the controller, not the screen,
 * not the other strategies.</p>
 */
public class ReportFactory {

    private final Map<ReportType, ReportStrategy> strategies = new EnumMap<>(ReportType.class);

    public ReportFactory(ExamDAO examDAO, GradeDAO gradeDAO, UserDAO userDAO,
                         CourseDAO courseDAO) {
        register(new TeacherReportStrategy(examDAO, gradeDAO, userDAO));
        register(new CourseReportStrategy(courseDAO, examDAO, gradeDAO));
        register(new StudentReportStrategy(gradeDAO, userDAO));
    }

    /** Keyed by the strategy's own type, so the two cannot be wired up crossed. */
    private void register(ReportStrategy strategy) {
        strategies.put(strategy.getType(), strategy);
    }

    /**
     * The strategy for one report type.
     *
     * @return null when the type has no strategy - which {@link #isComplete()}
     *         is there to make impossible in practice.
     */
    public ReportStrategy createStrategy(ReportType type) {
        return (type == null) ? null : strategies.get(type);
    }

    /** Every report the system can produce. */
    public Map<ReportType, ReportStrategy> all() {
        return strategies;
    }

    /**
     * True when every {@link ReportType} has a strategy behind it.
     *
     * <p>Checked by a test rather than trusted. A new enum value with no strategy
     * would otherwise show up on the screen as a report that quietly does nothing.</p>
     */
    public boolean isComplete() {
        for (ReportType type : ReportType.values()) {
            if (!strategies.containsKey(type)) {
                return false;
            }
        }
        return true;
    }
}
