package hsts.server.control.strategy;

import hsts.common.entity.Question;
import hsts.common.protocol.ExamBuildCriteria;
import hsts.common.protocol.QuestionQuota;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * SUC-4: the system chooses the questions from criteria.
 *
 * <p>Requirement 28 and מתווה scenario 3: a total number of questions, split by
 * topic and difficulty. Each {@link QuestionQuota} is one line of that request,
 * and this class fills them one at a time from the course's bank.</p>
 *
 * <h2>Two rules that matter more than the selection itself</h2>
 *
 * <p><b>Nothing is created unless everything can be satisfied.</b> Requirement 29
 * is explicit: if the bank cannot meet the criteria the system says so and does
 * not create an exam. So the quotas are checked and filled in full, and any
 * shortfall throws before a single row is written. A partly-built exam handed to
 * a class would be far worse than a refusal.</p>
 *
 * <p><b>A question is never used twice in one exam.</b> Quotas can overlap - "3
 * easy questions on Fractions" and "5 questions on any topic" could both match the
 * same question - so each one is removed from consideration as it is taken.
 * Without that, an exam could ask the same question twice and the points would
 * still add to 100, which is the kind of fault nobody notices until a student
 * does.</p>
 *
 * <p>Tighter quotas are filled first, for the same reason you would do it by hand:
 * a line demanding a specific topic <em>and</em> a specific difficulty has the
 * fewest candidates, so satisfying it before the loose lines eat the bank avoids
 * failing a request that was actually satisfiable.</p>
 */
public class AutomaticBuildStrategy implements ExamBuildStrategy {

    private final Random random;

    /** Production use: genuinely varied selection. */
    public AutomaticBuildStrategy() {
        this.random = new Random();
    }

    /** Tests pass a fixed seed so a failure can be reproduced exactly. */
    public AutomaticBuildStrategy(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public String getName() {
        return "automatic";
    }

    @Override
    public List<Question> selectQuestions(ExamBuildCriteria criteria, List<Question> pool)
            throws InsufficientQuestionsException {

        List<QuestionQuota> quotas = criteria.getQuotas();
        if (quotas.isEmpty()) {
            throw new InsufficientQuestionsException(
                    "Describe what the exam should contain - at least one line of "
                  + "topic, difficulty and how many questions.");
        }
        for (QuestionQuota quota : quotas) {
            if (quota.getCount() <= 0) {
                throw new InsufficientQuestionsException(
                        "Every line must ask for at least one question.");
            }
        }

        // Most specific first: a line fixing both topic and difficulty has the
        // fewest candidates, so it should get first pick.
        List<QuestionQuota> ordered = new ArrayList<>(quotas);
        ordered.sort((a, b) -> Integer.compare(specificity(b), specificity(a)));

        List<Question> remaining = new ArrayList<>(pool);
        List<Question> chosen = new ArrayList<>();

        for (QuestionQuota quota : ordered) {
            List<Question> candidates = matching(remaining, quota);

            if (candidates.size() < quota.getCount()) {
                throw new InsufficientQuestionsException(buildShortfallMessage(quota, candidates.size()));
            }

            Collections.shuffle(candidates, random);
            List<Question> take = candidates.subList(0, quota.getCount());

            chosen.addAll(take);
            // Taken out of circulation so a later quota cannot pick them again.
            remaining.removeAll(take);
        }

        // Shuffle the finished paper so it does not run in quota order, which would
        // group all the hard questions together and tell the student what to expect.
        Collections.shuffle(chosen, random);
        return chosen;
    }

    /** How constrained a quota is: 2 = topic and difficulty, 1 = one of them, 0 = neither. */
    private int specificity(QuestionQuota quota) {
        return (quota.isAnyTopic() ? 0 : 1) + (quota.isAnyDifficulty() ? 0 : 1);
    }

    private List<Question> matching(List<Question> pool, QuestionQuota quota) {
        List<Question> result = new ArrayList<>();
        for (Question question : pool) {
            boolean topicOk = quota.isAnyTopic()
                    || quota.getTopic().equalsIgnoreCase(question.getTopic());
            boolean levelOk = quota.isAnyDifficulty()
                    || quota.getDifficulty() == question.getDifficulty();
            if (topicOk && levelOk) {
                result.add(question);
            }
        }
        return result;
    }

    /**
     * Explains precisely which line failed and by how much.
     *
     * <p>Acceptance test 1.3 only requires "there are not enough questions". That
     * tells a teacher nothing about which criterion to relax, so the message names
     * the line, what was asked for, and what the bank could actually offer.</p>
     */
    private String buildShortfallMessage(QuestionQuota quota, int found) {
        return "Not enough questions in the bank for: " + quota.describe() + ".\n"
             + "Asked for " + quota.getCount() + ", found " + found + "."
             + (found == 0 ? "  There are no questions matching that combination at all." : "")
             + "\nNo exam was created. Change the criteria, or add questions to the bank first.";
    }
}
