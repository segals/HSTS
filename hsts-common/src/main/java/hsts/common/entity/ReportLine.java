package hsts.common.entity;

import java.io.Serializable;

/**
 * One row of a {@link Report} - a single exam, with its figures.
 *
 * <h2>The highlight</h2>
 *
 * <p>Two of the three reports compare classes with classes, and a row's
 * {@link #statistics} is the whole story. The student report is different: the
 * interesting comparison is <em>her</em> mark against the class that sat with her,
 * so the row carries her mark separately in {@link #highlight}.</p>
 *
 * <p>Deciles over one student's single mark would be a bar chart with one bar in
 * it, which is why her mark is a highlight against the class figures rather than
 * a set of figures of its own.</p>
 *
 * <p>Showing a class average beside her mark does not break requirement 55 - these
 * reports are the principal's (requirement 63) and the teacher's (requirement 59).
 * A student cannot reach them at all.</p>
 */
public class ReportLine implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The exam, in words: "Exam 010101 · Plane Geometry". */
    private final String label;

    /** Context under the label: who wrote it, when it was sat, how many sat it. */
    private final String detail;

    private final ExamStatistics statistics;

    /** One student's own mark, when the report is about her. Null otherwise. */
    private final Integer highlight;

    /** What the highlight means, e.g. "her mark". Null when there is none. */
    private final String highlightLabel;

    public ReportLine(String label, String detail, ExamStatistics statistics) {
        this(label, detail, statistics, null, null);
    }

    public ReportLine(String label, String detail, ExamStatistics statistics,
                      Integer highlight, String highlightLabel) {
        this.label = label;
        this.detail = detail;
        this.statistics = statistics;
        this.highlight = highlight;
        this.highlightLabel = highlightLabel;
    }

    public String getLabel()              { return label; }
    public String getDetail()             { return detail; }
    public ExamStatistics getStatistics() { return statistics; }
    public Integer getHighlight()         { return highlight; }
    public String getHighlightLabel()     { return highlightLabel; }

    public boolean hasHighlight() {
        return highlight != null;
    }

    /**
     * How far the highlighted mark sits above or below the class average.
     *
     * <p>Null when there is no highlight, or when the class has no approved marks
     * to compare against.</p>
     */
    public Double getDifferenceFromAverage() {
        if (highlight == null || statistics == null || statistics.getGradeCount() == 0) {
            return null;
        }
        return highlight - statistics.getAverage();
    }
}
