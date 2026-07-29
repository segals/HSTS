package hsts.server.control;

import hsts.common.entity.Principal;
import hsts.common.entity.Report;
import hsts.common.entity.Teacher;
import hsts.common.entity.User;
import hsts.common.enums.ReportType;
import hsts.common.protocol.ReportRequest;
import hsts.common.protocol.ReportSubject;
import hsts.common.protocol.Response;
import hsts.server.control.strategy.ReportFactory;
import hsts.server.control.strategy.ReportStrategy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SUC-11 and SUC-12 / מתווה scenario 12: the statistical reports.
 *
 * <p><i>"ניתן לראות ממוצע, חציון והתפלגות עשרונית של בחינות ולהשוות בין: בחינות
 * שונות של אותה מורה, בחינות שונות של אותו קורס, בחינות שונות של אותה תלמידה"</i></p>
 *
 * <h2>No if-chain anywhere in here</h2>
 *
 * <p>This controller never asks which report it is producing. It hands the type to
 * {@link ReportFactory}, gets a {@link ReportStrategy}, and calls it. That is the
 * <b>Factory</b> and <b>Strategy</b> pair from the submitted class diagram, and it
 * is what requirement 64 - <i>"הפקת דו"חות חדשים תדרוש עבודת פיתוח מינימלית"</i> -
 * is asking for.</p>
 *
 * <h2>Who may run what</h2>
 *
 * <p>Requirement 63 gives the <b>principal</b> all three comparisons, about anyone.</p>
 *
 * <p>Requirement 59 gives a <b>teacher</b> a report on <i>"כל הבחינות שכתבה"</i> -
 * the exams she wrote. That is the by-teacher report with her own id, so she gets
 * exactly that one, and {@link #generate} <b>overrides whatever key she sends</b>
 * with her own. She cannot ask for a colleague's report by editing a request, and
 * she is not offered the course or student comparisons, which requirement 63 gives
 * to the principal alone.</p>
 *
 * <p>A student reaches none of it - requirement 55.</p>
 *
 * <p>There are <b>no acceptance tests</b> for SUC-11 or SUC-12 in the submitted
 * Assignment 1, which covers SUC-3, 7, 9 and 10 only.</p>
 */
public class ReportController {

    private final ReportFactory factory;

    public ReportController(ReportFactory factory) {
        this.factory = factory;
    }

    /** Which reports this user may run at all. */
    public Response listTypes(User user) {
        List<ReportType> allowed = allowedFor(user);
        if (allowed.isEmpty()) {
            return Response.error("Reports are for teachers and the principal.");
        }
        return Response.ok(new ArrayList<>(allowed), allowed.size() == 1
                ? "One report is available to you."
                : allowed.size() + " reports are available to you.");
    }

    /**
     * What a report can be run about.
     *
     * <p>The list comes from the strategy, not from here - so a new report type
     * arrives with its own chooser and this method never changes.</p>
     */
    public Response listSubjects(User user, ReportType type) {
        String refusal = refuse(user, type);
        if (refusal != null) {
            return Response.error(refusal);
        }
        try {
            // Requirement 59: a teacher's only subject is herself.
            if (isPlainTeacher(user)) {
                return Response.ok(List.of(new ReportSubject(user.getUserId(),
                        user.getFullName(), "the exams you wrote")),
                        "This report covers the exams you wrote.");
            }
            List<ReportSubject> subjects = factory.createStrategy(type).listSubjects();
            return Response.ok(subjects, subjects.isEmpty()
                    ? "There is nothing with approved marks to report on yet."
                    : subjects.size() + " to choose from.");
        } catch (SQLException e) {
            return Response.error("Could not load the list: " + e.getMessage());
        }
    }

    /** Builds the report. An empty one is a valid answer, not an error. */
    public Response generate(User user, ReportRequest request) {
        if (request == null) {
            return Response.error("No report was asked for.");
        }
        String refusal = refuse(user, request.getType());
        if (refusal != null) {
            return Response.error(refusal);
        }

        // Requirement 59 again, and this is the check that counts: whatever key a
        // teacher sends, the report is about her. Nothing she can edit changes it.
        String key = isPlainTeacher(user) ? user.getUserId() : request.getSubjectKey();
        if (key == null || key.isBlank()) {
            return Response.error("Choose a " + request.getType().getSubjectNoun().toLowerCase()
                                + " to report on.");
        }

        try {
            Report report = factory.createStrategy(request.getType()).generate(key);
            return Response.ok(report, report.isEmpty()
                    ? "Nothing to compare yet - no approved marks."
                    : report.getLines().size() + " exam(s) compared, "
                      + report.getOverall().getGradeCount() + " mark(s) in all.");
        } catch (SQLException e) {
            return Response.error("Could not produce the report: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------

    /**
     * A teacher who is not also the principal. Coordinators are teachers too - they
     * extend {@code Teacher} - and get the same one report about their own exams.
     */
    private boolean isPlainTeacher(User user) {
        return user instanceof Teacher;
    }

    private List<ReportType> allowedFor(User user) {
        if (user instanceof Principal) {
            return List.of(ReportType.values());      // requirement 63: all three
        }
        if (user instanceof Teacher) {
            return List.of(ReportType.BY_TEACHER);    // requirement 59: her own exams
        }
        return List.of();                             // requirement 55: not a student
    }

    private String refuse(User user, ReportType type) {
        if (type == null) {
            return "No report was chosen.";
        }
        if (!allowedFor(user).contains(type)) {
            return user instanceof Teacher
                    ? "That report is the principal's. Yours covers the exams you wrote."
                    : "Reports are for teachers and the principal.";
        }
        if (factory.createStrategy(type) == null) {
            // Only reachable if a ReportType is added without a strategy, which
            // ReportFactory.isComplete() and its test exist to prevent.
            return "That report is not available.";
        }
        return null;
    }
}
