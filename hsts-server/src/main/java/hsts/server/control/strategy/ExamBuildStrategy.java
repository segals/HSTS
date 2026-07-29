package hsts.server.control.strategy;

import hsts.common.entity.Question;
import hsts.common.protocol.ExamBuildCriteria;

import java.util.List;

/**
 * How to choose the questions for an exam.
 *
 * <p>The <b>Strategy</b> pattern from the submitted class diagram, and one of the
 * two places the course asks to see it. There are two ways to build an exam -
 * by hand (SUC-3) and automatically from criteria (SUC-4) - and they differ only
 * in how the questions are picked. Everything after that is identical.</p>
 *
 * <p>So {@code ExamBuilderController} holds one of these and calls
 * {@link #selectQuestions}. It never asks which kind it has, and there is no
 * {@code if (automatic)} anywhere in it. Adding a third way to build an exam -
 * "the same questions as last year's paper", say - means writing one new class
 * and changing nothing that already works, which is what NFR 19 asks for.</p>
 */
public interface ExamBuildStrategy {

    /**
     * Chooses questions from the bank.
     *
     * @param criteria what the teacher asked for
     * @param pool     every current, non-deleted question in the course
     * @return the chosen questions, in the order they should appear
     * @throws InsufficientQuestionsException when the bank cannot satisfy the
     *         request. Requirement 29 and מתווה scenario 3 note 2 both insist that
     *         the system says so and creates <em>nothing</em> - a half-built exam
     *         would be worse than none.
     */
    List<Question> selectQuestions(ExamBuildCriteria criteria, List<Question> pool)
            throws InsufficientQuestionsException;

    /** Shown to the teacher, so she can see which rule was applied. */
    String getName();
}
