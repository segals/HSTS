package hsts.server.control.strategy;

import hsts.common.entity.Report;
import hsts.common.enums.ReportType;
import hsts.common.protocol.ReportSubject;

import java.sql.SQLException;
import java.util.List;

/**
 * How to build one kind of statistical report.
 *
 * <p>The <b>Strategy</b> pattern from the submitted class diagram, and the second
 * of the two places the course asks to see it - {@link ExamBuildStrategy} is the
 * other. מתווה scenario 12 asks for three comparisons: a teacher's exams, a
 * course's exams, a student's exams. They differ only in <em>which marks go into
 * the report</em>. Everything after that - average, median, deciles - is the same
 * arithmetic, and lives in one place.</p>
 *
 * <p>So {@code ReportController} holds one of these and calls {@link #generate}.
 * It never asks which kind it has, and there is no {@code if (byCourse)} anywhere
 * in it.</p>
 *
 * <h2>Requirement 64</h2>
 *
 * <p><i>"המערכת תיבנה בצורה גמישה כך שהפקת דו"חות חדשים תדרוש עבודת פיתוח
 * מינימלית"</i> - a new report should take minimal work. With this arrangement it
 * takes exactly two things:</p>
 *
 * <ol>
 *   <li>one new value in {@link ReportType};</li>
 *   <li>one new class implementing this interface, registered in
 *       {@link ReportFactory}.</li>
 * </ol>
 *
 * <p>No existing strategy changes, the controller does not change, and the screen
 * does not change - which is why {@link #listSubjects()} is on the strategy rather
 * than the controller. A new report brings its own chooser with it.</p>
 */
public interface ReportStrategy {

    /** Which report this builds. Lets the factory be checked against itself. */
    ReportType getType();

    /** Shown on screen, so a reader can see which rule produced the figures. */
    String getName();

    /**
     * What this report can be run about: the teachers, the courses, the students.
     *
     * <p>On the strategy rather than the controller so that adding a report type
     * does not mean editing a chooser somewhere else.</p>
     */
    List<ReportSubject> listSubjects() throws SQLException;

    /**
     * Builds the report.
     *
     * @param subjectKey whatever {@link #listSubjects()} handed out - a user id,
     *                   a course code. Only this strategy knows what its own key
     *                   means.
     * @return the report, which may legitimately be empty when nothing has been
     *         approved yet. An empty report is not an error.
     */
    Report generate(String subjectKey) throws SQLException;
}
