package hsts.common.entity;

import hsts.common.enums.ReportType;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A finished statistical report - the {@code Report} of the submitted class diagram.
 *
 * <p>One {@link ReportLine} per exam being compared, plus the same figures taken
 * over everything in the report at once. That is what מתווה scenario 12 asks for:
 * average, median and decile distribution, <em>compared</em> across several exams
 * rather than read one at a time.</p>
 *
 * <p>Built by a {@code ReportStrategy}, which is chosen by {@code ReportFactory}.
 * This class knows nothing about which of the three it came from beyond the
 * {@link #type} it carries for the heading.</p>
 */
public class Report implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ReportType type;

    /** Who or what the report is about: a teacher's name, a course, a student. */
    private final String subjectName;

    private final String title;

    /** A sentence saying what is being compared, so the screen need not guess. */
    private final String description;

    private final List<ReportLine> lines;

    /** The same figures over every mark in the report at once. */
    private final ExamStatistics overall;

    private final LocalDateTime generatedAt;

    public Report(ReportType type, String subjectName, String title, String description,
                  List<ReportLine> lines, ExamStatistics overall, LocalDateTime generatedAt) {
        this.type = type;
        this.subjectName = subjectName;
        this.title = title;
        this.description = description;
        // A copy: a view returned by List.subList is not serialisable, and that has
        // already broken this project once.
        this.lines = (lines == null) ? new ArrayList<>() : new ArrayList<>(lines);
        this.overall = overall;
        this.generatedAt = generatedAt;
    }

    public ReportType getType()          { return type; }
    public String getSubjectName()       { return subjectName; }
    public String getTitle()             { return title; }
    public String getDescription()       { return description; }
    public List<ReportLine> getLines()   { return lines; }
    public ExamStatistics getOverall()   { return overall; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }

    /** True when there was nothing to report on - no approved marks anywhere. */
    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
