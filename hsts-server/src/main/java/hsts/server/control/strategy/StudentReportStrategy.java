package hsts.server.control.strategy;

import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Report;
import hsts.common.entity.ReportLine;
import hsts.common.entity.User;
import hsts.common.enums.ReportType;
import hsts.common.enums.UserRole;
import hsts.common.protocol.ReportSubject;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.UserDAO;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <i>"בחינות שונות של אותה תלמידה"</i> - how one student did across her exams.
 *
 * <p>Requirement 63. The report a principal runs when a parent asks whether a girl
 * is improving.</p>
 *
 * <h2>Why this one looks different</h2>
 *
 * <p>The other two compare classes with classes. Here every row is a single mark,
 * and deciles over one mark would be a chart with one bar in it. So each row
 * carries the <b>class</b> figures for the sitting she was in, with <b>her</b> mark
 * highlighted against them - which is the comparison that actually says something:
 * 70 means one thing in a class averaging 55 and another in a class averaging 85.
 * {@code ReportLine.getDifferenceFromAverage()} is that gap.</p>
 *
 * <p>Only approved marks appear. An unapproved mark is not a result yet
 * (requirement 53), and including it would let a report reveal something the
 * student herself has not been told.</p>
 */
public class StudentReportStrategy implements ReportStrategy {

    private final GradeDAO gradeDAO;
    private final UserDAO userDAO;

    public StudentReportStrategy(GradeDAO gradeDAO, UserDAO userDAO) {
        this.gradeDAO = gradeDAO;
        this.userDAO = userDAO;
    }

    @Override
    public ReportType getType() {
        return ReportType.BY_STUDENT;
    }

    @Override
    public String getName() {
        return "Compared across the exams one student sat";
    }

    @Override
    public List<ReportSubject> listSubjects() throws SQLException {
        List<ReportSubject> subjects = new ArrayList<>();
        for (User user : userDAO.findAll()) {
            if (user.getRole() != UserRole.STUDENT) {
                continue;
            }
            int sat = gradeDAO.findApprovedForStudent(user.getUserId()).size();
            if (sat > 0) {
                subjects.add(new ReportSubject(user.getUserId(), user.getFullName(),
                        sat + (sat == 1 ? " exam sat" : " exams sat")
                        + "  ·  ID " + user.getUserId()));
            }
        }
        return subjects;
    }

    @Override
    public Report generate(String studentId) throws SQLException {
        User student = userDAO.findById(studentId);
        String name = (student == null) ? studentId : student.getFullName();

        List<ReportLine> lines = new ArrayList<>();
        List<Integer> herMarks = new ArrayList<>();

        for (Grade her : gradeDAO.findApprovedForStudent(studentId)) {
            // The class she sat with, not every class that ever sat this exam -
            // she is being compared with the people beside her.
            ExamStatistics classStats = gradeDAO.computeStatistics(her.getExecutionId());
            herMarks.add(her.getFinalGrade());

            Double gap = null;
            if (classStats.getGradeCount() > 0) {
                gap = her.getFinalGrade() - classStats.getAverage();
            }

            lines.add(new ReportLine(
                    "Exam " + her.getExamId() + "  ·  " + her.getCourseName(),
                    "her mark " + her.getFinalGrade()
                        + (gap == null ? ""
                           : String.format("  ·  class average %.1f  ·  %s%.1f",
                                   classStats.getAverage(), gap >= 0 ? "+" : "", gap))
                        + (her.getActualDuration() == null ? ""
                           : "  ·  took " + her.getActualDuration() + " min"),
                    classStats,
                    her.getFinalGrade(), "her mark"));
        }

        return new Report(ReportType.BY_STUDENT, name,
                "Exams sat by " + name,
                "Every approved mark " + name + " has, with the class she sat with "
              + "beside it. The figures on the right describe the class; the "
              + "highlighted number is hers.",
                lines, ExamStatistics.over(herMarks), LocalDateTime.now());
    }
}
