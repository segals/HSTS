package hsts.common.protocol;

import hsts.common.entity.Grade;
import hsts.common.entity.StudentExam;

import java.io.Serializable;

/**
 * One student's paper, marked: the attempt, and the mark that goes with it.
 *
 * <p>Used by two different screens with the same shape - the teacher marking it,
 * and the student reading it afterwards. The difference is not in the class but in
 * <em>when</em> the server will hand one over: a student gets hers only once the
 * mark is approved (requirement 53).</p>
 *
 * <p>Unlike the copy sent while she is sitting the exam, this one <b>does</b>
 * carry which option was correct. That is the point of it - requirement 53 says
 * she sees her paper with the wrong questions marked, which is impossible without
 * showing what the right answer was.</p>
 */
public class MarkedExam implements Serializable {

    private static final long serialVersionUID = 1L;

    private final StudentExam attempt;
    private final Grade grade;

    public MarkedExam(StudentExam attempt, Grade grade) {
        this.attempt = attempt;
        this.grade = grade;
    }

    public StudentExam getAttempt() { return attempt; }
    public Grade getGrade()         { return grade; }
}
