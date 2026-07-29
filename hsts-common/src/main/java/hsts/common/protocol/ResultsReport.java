package hsts.common.protocol;

import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The marks of an exam, and the statistics over them.
 *
 * <p>מתווה scenario 10 asks for the same results in two shapes at once - a table
 * and a histogram - so they travel together. Sending them as two requests would
 * let the table and the chart disagree if a mark changed in between, which is
 * exactly the sort of thing that goes wrong in front of an audience.</p>
 *
 * <p>The statistics cover <b>approved</b> marks only, which is what
 * {@code GradeDAO.computeStatistics} does and what acceptance test 3.7 counts. The
 * table lists everything, so a teacher can see that four papers are still waiting
 * while the average speaks for the fourteen that are done.</p>
 */
public class ResultsReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** What is being reported on, in words, for the top of the screen. */
    private final String title;

    /** Where the marks came from - one sitting, or several. */
    private final String subtitle;

    private final List<Grade> grades;
    private final ExamStatistics statistics;

    public ResultsReport(String title, String subtitle, List<Grade> grades,
                         ExamStatistics statistics) {
        this.title = title;
        this.subtitle = subtitle;
        // A copy: a view returned by List.subList is not serialisable, and that has
        // already broken this project once.
        this.grades = (grades == null) ? new ArrayList<>() : new ArrayList<>(grades);
        this.statistics = statistics;
    }

    public String getTitle()               { return title; }
    public String getSubtitle()            { return subtitle; }
    public List<Grade> getGrades()         { return grades; }
    public ExamStatistics getStatistics()  { return statistics; }

    /** Papers handed in but not yet approved, so the table can say so. */
    public long getUnapprovedCount() {
        return grades.stream().filter(g -> !g.isApproved()).count();
    }
}
