package hsts.server.control.strategy;

import hsts.common.entity.Course;
import hsts.common.entity.Exam;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Report;
import hsts.common.entity.ReportLine;
import hsts.common.enums.ReportType;
import hsts.common.protocol.ReportSubject;
import hsts.server.dao.CourseDAO;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.GradeDAO;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <i>"בחינות שונות של אותו קורס"</i> - every exam set for one course.
 *
 * <p>Requirement 63. The comparison a head of department wants: were this term's
 * papers harder than last term's, and did the class move.</p>
 *
 * <p>Differs from {@link TeacherReportStrategy} only in which exams go in - by
 * course rather than by author. Everything downstream is identical, which is the
 * point of the Strategy pattern being here: the arithmetic is written once, on
 * {@link ExamStatistics}, and each strategy only decides what goes into it.</p>
 */
public class CourseReportStrategy implements ReportStrategy {

    private final CourseDAO courseDAO;
    private final ExamDAO examDAO;
    private final GradeDAO gradeDAO;

    public CourseReportStrategy(CourseDAO courseDAO, ExamDAO examDAO, GradeDAO gradeDAO) {
        this.courseDAO = courseDAO;
        this.examDAO = examDAO;
        this.gradeDAO = gradeDAO;
    }

    @Override
    public ReportType getType() {
        return ReportType.BY_COURSE;
    }

    @Override
    public String getName() {
        return "Compared across the exams of one course";
    }

    @Override
    public List<ReportSubject> listSubjects() throws SQLException {
        List<ReportSubject> subjects = new ArrayList<>();
        for (Course course : courseDAO.findAll()) {
            int exams = examDAO.findCurrentByCourse(course.getCourseCode()).size();
            if (exams > 0) {
                subjects.add(new ReportSubject(course.getCourseCode(), course.getName(),
                        exams + (exams == 1 ? " exam" : " exams")
                        + "  ·  " + course.getSubjectName()));
            }
        }
        return subjects;
    }

    @Override
    public Report generate(String courseCode) throws SQLException {
        Course course = courseDAO.findById(courseCode);
        String name = (course == null) ? courseCode : course.getName();

        List<ReportLine> lines = new ArrayList<>();
        List<Integer> everyMark = new ArrayList<>();

        for (Exam exam : examDAO.findCurrentByCourse(courseCode)) {
            ExamStatistics stats = gradeDAO.computeStatisticsForExam(exam.getExamId());
            if (stats.getGradeCount() == 0) {
                continue;
            }
            for (Grade g : gradeDAO.findByExam(exam.getExamId())) {
                if (g.isApproved()) {
                    everyMark.add(g.getFinalGrade());
                }
            }
            lines.add(new ReportLine(
                    "Exam " + exam.getExamId() + "  ·  written by " + exam.getAuthorName(),
                    stats.getGradeCount() + " approved mark(s)  ·  "
                        + exam.getDurationMinutes() + " minutes",
                    stats));
        }

        return new Report(ReportType.BY_COURSE, name,
                "Exams in " + name,
                "Every exam written for " + name + " that has been sat and approved, "
              + "whoever wrote it.",
                lines, ExamStatistics.over(everyMark), LocalDateTime.now());
    }
}
