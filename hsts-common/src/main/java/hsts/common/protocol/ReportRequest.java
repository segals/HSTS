package hsts.common.protocol;

import hsts.common.enums.ReportType;

import java.io.Serializable;

/**
 * Asks for one report: which kind, and about whom.
 *
 * <p>{@link #getSubjectKey()} is whatever the chosen {@link ReportType} needs -
 * a teacher's id, a course code, a student's id. The server does not interpret it;
 * it hands it to the strategy the factory produced, which is the only thing that
 * knows what its own key means.</p>
 */
public class ReportRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ReportType type;
    private final String subjectKey;

    public ReportRequest(ReportType type, String subjectKey) {
        this.type = type;
        this.subjectKey = subjectKey;
    }

    public ReportType getType()    { return type; }
    public String getSubjectKey()  { return subjectKey; }
}
