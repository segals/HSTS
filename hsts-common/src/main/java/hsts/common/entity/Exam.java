package hsts.common.entity;

import hsts.common.enums.ExamStatus;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One exam: a set of questions with points, a duration, and instructions.
 *
 * <h2>The identifier</h2>
 *
 * <p>Six digits, fixed by system description §3.2: digits 0-1 are the exam code,
 * digits 2-3 the course code, digits 4-5 the subject code. Exam 3 of course 05
 * in subject 02 is therefore {@code 030502}. This is the only identifier ever
 * shown on screen.</p>
 *
 * <h2>Versioning</h2>
 *
 * <p>Editing an exam never overwrites it - מתווה scenario 3 item 5 requires
 * "המבחן הקודם נשאר במאגר". A new row is written with {@code version + 1} and
 * {@code isCurrent} moves to it. Any sitting that already happened points at the
 * exact version it used, so it keeps showing the paper the students actually
 * took.</p>
 *
 * <h2>Two kinds of free text, and why the difference matters</h2>
 *
 * <p>{@link #instructionsForStudents} is part of the paper. {@link #notesForTeacher}
 * must <b>never</b> reach a student - system description §3.2 calls it
 * "ממל שלא נראה ע"י הנבחנת", and acceptance test 4.10 checks it stays hidden.
 * They are separate fields precisely so that the exam sent to a student can have
 * one stripped out.</p>
 */
public class Exam implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The total every exam must add up to (מתווה scenario 3, note 3). */
    public static final int REQUIRED_TOTAL_POINTS = 100;

    private String examId;                 // 6 digits: 2 exam + 2 course + 2 subject
    private int version = 1;

    private String courseCode;
    private String subjectCode;
    private String courseName;             // for display only
    private int durationMinutes;

    private String instructionsForStudents;
    private String notesForTeacher;        // never sent to a student

    private String authorId;
    private String authorName;             // for display only

    private ExamStatus status = ExamStatus.PENDING_APPROVAL;
    private String rejectionReason;
    private String approvedBy;
    private LocalDateTime approvedAt;

    /**
     * What the teacher calls it: "Plane Geometry mid-term".
     *
     * <p>Not in the submitted documents. Requirements 22 to 26 define an exam as its
     * questions, its 6-digit number, a duration, points, two blocks of free text and
     * the author's name - and none of them gives it a name, so every screen showed
     * "020101" and a reader had to remember which one that was. Asked for from the
     * screen and added as a derived requirement; see docs/03_document_updates.md.</p>
     *
     * <p>The number is unchanged and still shown everywhere beside it. It is what
     * requirement 23 fixes the format of, and what everybody types into a form.</p>
     */
    private String name;

    private boolean current = true;

    /** Display only: see {@link #isNewlyApproved()}. Never stored. */
    private boolean newlyApproved;
    private LocalDateTime createdAt;

    private List<ExamQuestion> questions = new ArrayList<>();

    public Exam() {
    }

    public String getExamId()                    { return examId; }
    public int getVersion()                      { return version; }
    public String getCourseCode()                { return courseCode; }
    public String getSubjectCode()               { return subjectCode; }
    public String getCourseName()                { return courseName; }
    public int getDurationMinutes()              { return durationMinutes; }
    public String getInstructionsForStudents()   { return instructionsForStudents; }
    public String getNotesForTeacher()           { return notesForTeacher; }
    public String getAuthorId()                  { return authorId; }
    public String getAuthorName()                { return authorName; }
    public ExamStatus getStatus()                { return status; }
    public String getRejectionReason()           { return rejectionReason; }
    public String getApprovedBy()                { return approvedBy; }
    public LocalDateTime getApprovedAt()         { return approvedAt; }
    public String getName()                      { return name; }

    /**
     * "Mid-term  ·  020101", for a list row.
     *
     * <p>In one place so every screen reads the same way round: the name first,
     * because that is what she is looking for, and the number after it, because that
     * is what she has to type on a form.</p>
     */
    public String describe() {
        return (name == null || name.isBlank())
                ? examId
                : name + "  ·  " + examId;
    }

    public boolean isCurrent()                   { return current; }

    /**
     * True when the author has not yet seen that this exam was approved.
     *
     * <p>Display only, and filled in by the release list alone - the same kind of
     * derived flag as {@code Grade.isMarked()}. It is a fact about a <em>reader</em>,
     * not about the exam, so it is never stored and is false everywhere else.</p>
     */
    public boolean isNewlyApproved()             { return newlyApproved; }

    public void setNewlyApproved(boolean flag)   { this.newlyApproved = flag; }
    public LocalDateTime getCreatedAt()          { return createdAt; }
    public List<ExamQuestion> getQuestions()     { return questions; }

    public void setExamId(String examId)             { this.examId = examId; }
    public void setVersion(int version)              { this.version = version; }
    public void setCourseCode(String c)              { this.courseCode = c; }
    public void setSubjectCode(String s)             { this.subjectCode = s; }
    public void setCourseName(String n)              { this.courseName = n; }
    public void setDurationMinutes(int m)            { this.durationMinutes = m; }
    public void setInstructionsForStudents(String s) { this.instructionsForStudents = s; }
    public void setNotesForTeacher(String s)         { this.notesForTeacher = s; }
    public void setAuthorId(String id)               { this.authorId = id; }
    public void setAuthorName(String n)              { this.authorName = n; }
    public void setStatus(ExamStatus status)         { this.status = status; }
    public void setRejectionReason(String reason)    { this.rejectionReason = reason; }
    public void setApprovedBy(String by)             { this.approvedBy = by; }
    public void setApprovedAt(LocalDateTime at)      { this.approvedAt = at; }
    public void setName(String name)                 { this.name = name; }
    public void setCurrent(boolean current)          { this.current = current; }
    public void setCreatedAt(LocalDateTime at)       { this.createdAt = at; }

    /**
     * Copies the list rather than keeping the caller's.
     *
     * <p>An {@code Exam} crosses the network, so its fields have to be
     * serializable. Views such as {@code List.subList(...)} and
     * {@code List.of(...)} are not, and storing one would make the whole exam
     * unsendable with an error that names the network layer rather than the real
     * cause.</p>
     */
    public void setQuestions(List<ExamQuestion> questions) {
        this.questions = (questions == null) ? new ArrayList<>() : new ArrayList<>(questions);
    }

    /** The sum of every question's points. Must be exactly 100 before saving. */
    public int getTotalPoints() {
        int total = 0;
        for (ExamQuestion eq : questions) {
            total += eq.getPoints();
        }
        return total;
    }

    public int getQuestionCount() {
        return questions.size();
    }

    /**
     * Spreads 100 points as evenly as the question count allows.
     *
     * <p>Most counts do not divide 100. With 3 questions the split is 34, 33, 33 -
     * the remainder goes one point at a time to the earliest questions rather than
     * being dropped, so the total is exactly 100 whatever the count. The teacher
     * can then adjust individual values by hand.</p>
     */
    public void distributePointsEvenly() {
        int count = questions.size();
        if (count == 0) {
            return;
        }
        int base = REQUIRED_TOTAL_POINTS / count;
        int remainder = REQUIRED_TOTAL_POINTS % count;

        for (int i = 0; i < count; i++) {
            questions.get(i).setPoints(base + (i < remainder ? 1 : 0));
        }
    }

    /**
     * "Plane Geometry (01)" - the course the way it should be read.
     *
     * <p>The two digits are inside this exam's own six-digit number, so they are
     * worth keeping in front of her; they are not what she calls the course.</p>
     */
    public String describeCourse() {
        return (courseName == null || courseName.isBlank())
                ? courseCode : courseName + " (" + courseCode + ")";
    }

    /** Exam-code part of the id - digits 0 and 1. */
    public String getExamCodePart() {
        return (examId == null || examId.length() != 6) ? "" : examId.substring(0, 2);
    }

    public String getSummary() {
        return examId + " (v" + version + ")   " + getQuestionCount() + " questions, "
             + durationMinutes + " min   ·   " + status.getDisplayName();
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
