package hsts.server.control.strategy;

import hsts.common.entity.Question;
import hsts.common.protocol.ExamBuildCriteria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SUC-3: the teacher picked the questions herself.
 *
 * <p>The work here is not choosing - she has already chosen - but checking that
 * what she chose is real. The list of ids arrived from a client, and a client can
 * send anything, so every id is looked up in the course's own bank. An id that is
 * not there means either a stale screen (the question was deleted while she was
 * working) or a client that should not be trusted; both are refused the same way.</p>
 */
public class ManualBuildStrategy implements ExamBuildStrategy {

    @Override
    public String getName() {
        return "manual";
    }

    @Override
    public List<Question> selectQuestions(ExamBuildCriteria criteria, List<Question> pool)
            throws InsufficientQuestionsException {

        List<String> wanted = criteria.getManualQuestionIds();
        if (wanted.isEmpty()) {
            // Acceptance test 1.4: an exam with no questions cannot be saved.
            throw new InsufficientQuestionsException(
                    "Choose at least one question. An exam cannot be empty.");
        }

        // Keyed by id so lookup is one step rather than a scan per question.
        Map<String, Question> available = new LinkedHashMap<>();
        for (Question question : pool) {
            available.put(question.getQuestionId(), question);
        }

        List<Question> chosen = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String id : wanted) {
            Question question = available.get(id);
            if (question == null) {
                missing.add(id);
            } else if (chosen.contains(question)) {
                throw new InsufficientQuestionsException(
                        "Question " + id + " was chosen twice. Each question may appear "
                      + "only once in an exam.");
            } else {
                chosen.add(question);
            }
        }

        if (!missing.isEmpty()) {
            throw new InsufficientQuestionsException(
                    "These questions are no longer in the bank: " + String.join(", ", missing)
                  + ". Someone may have deleted them. Reopen the course and try again.");
        }
        return chosen;
    }
}
