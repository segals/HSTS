package hsts.server.control.strategy;

import hsts.common.entity.Exam;
import hsts.common.entity.ExamStatistics;
import hsts.common.entity.Grade;
import hsts.common.entity.Report;
import hsts.common.entity.ReportLine;
import hsts.common.entity.User;
import hsts.common.enums.ReportType;
import hsts.common.enums.UserRole;
import hsts.common.protocol.ReportSubject;
import hsts.server.dao.ExamDAO;
import hsts.server.dao.GradeDAO;
import hsts.server.dao.UserDAO;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <i>"בחינות שונות של אותה מורה"</i> - every exam one teacher wrote, side by side.
 *
 * <p>Serves two requirements at once. Requirement 63 gives the principal this
 * comparison for any teacher. Requirement 59 gives a teacher the same figures for
 * <b>her own</b> exams - <i>"כל הבחינות שכתבה (גם אם בוצעו על-ידי מורות אחרות)"</i>.
 * The two differ only in whose id is passed in, so they are one strategy, and the
 * rule about whose id a teacher may pass is enforced in {@code ReportController}
 * where it belongs.</p>
 *
 * <p>Each row covers <b>every sitting</b> of that exam. The question this report
 * answers is "how do her papers behave", and a paper handed out three times is one
 * paper.</p>
 */
public class TeacherReportStrategy implements ReportStrategy {

    private final ExamDAO examDAO;
    private final GradeDAO gradeDAO;
    private final UserDAO userDAO;

    public TeacherReportStrategy(ExamDAO examDAO, GradeDAO gradeDAO, UserDAO userDAO) {
        this.examDAO = examDAO;
        this.gradeDAO = gradeDAO;
        this.userDAO = userDAO;
    }

    @Override
    public ReportType getType() {
        return ReportType.BY_TEACHER;
    }

    @Override
    public String getName() {
        return "Compared across the exams one teacher wrote";
    }

    @Override
    public List<ReportSubject> listSubjects() throws SQLException {
        List<ReportSubject> subjects = new ArrayList<>();
        for (User user : userDAO.findAll()) {
            if (user.getRole() == UserRole.TEACHER || user.getRole() == UserRole.COORDINATOR) {
                int written = examDAO.findCurrentByAuthor(user.getUserId()).size();
                if (written > 0) {
                    subjects.add(new ReportSubject(user.getUserId(), user.getFullName(),
                            written + (written == 1 ? " exam written" : " exams written")));
                }
            }
        }
        return subjects;
    }

    @Override
    public Report generate(String teacherId) throws SQLException {
        User teacher = userDAO.findById(teacherId);
        String name = (teacher == null) ? teacherId : teacher.getFullName();

        List<ReportLine> lines = new ArrayList<>();
        List<Integer> everyMark = new ArrayList<>();

        for (Exam exam : examDAO.findCurrentByAuthor(teacherId)) {
            ExamStatistics stats = gradeDAO.computeStatisticsForExam(exam.getExamId());
            if (stats.getGradeCount() == 0) {
                continue;                    // never sat, or nothing approved yet
            }
            for (Grade g : gradeDAO.findByExam(exam.getExamId())) {
                if (g.isApproved()) {
                    everyMark.add(g.getFinalGrade());
                }
            }
            lines.add(new ReportLine(
                    "Exam " + exam.getExamId() + "  ·  " + exam.getCourseName(),
                    stats.getGradeCount() + " approved mark(s)  ·  "
                        + exam.getStatus().getDisplayName(),
                    stats));
        }

        return new Report(ReportType.BY_TEACHER, name,
                "Exams written by " + name,
                "Every exam " + name + " wrote that has been sat and approved, "
              + "including sittings run by other teachers (requirement 59).",
                lines, ExamStatistics.over(everyMark), LocalDateTime.now());
    }
}
