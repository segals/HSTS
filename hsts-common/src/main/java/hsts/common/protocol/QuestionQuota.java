package hsts.common.protocol;

import hsts.common.enums.DifficultyLevel;

import java.io.Serializable;

/**
 * One line of an automatic exam request: "five medium questions on Fractions".
 *
 * <p>מתווה scenario 3 and requirement 28 ask for automatic building by
 * "מספר שאלות כולל, פילוח על פי נושאים ורמת קושי" - a total number, split by
 * topic and difficulty. A list of these expresses exactly that: each line fixes
 * a topic, a difficulty and a count, and the total is their sum.</p>
 *
 * <p>Either constraint may be left blank to mean "any", so a teacher who only
 * cares about difficulty is not forced to name topics she does not mind about.</p>
 */
public class QuestionQuota implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Null or blank means any topic. */
    private final String topic;

    /** Null means any difficulty. */
    private final DifficultyLevel difficulty;

    private final int count;

    public QuestionQuota(String topic, DifficultyLevel difficulty, int count) {
        this.topic = topic;
        this.difficulty = difficulty;
        this.count = count;
    }

    public String getTopic()               { return topic; }
    public DifficultyLevel getDifficulty() { return difficulty; }
    public int getCount()                  { return count; }

    public boolean isAnyTopic() {
        return topic == null || topic.isBlank();
    }

    public boolean isAnyDifficulty() {
        return difficulty == null;
    }

    /** Wording used both on screen and inside the "not enough questions" message. */
    public String describe() {
        String topicPart = isAnyTopic() ? "any topic" : "\"" + topic + "\"";
        String levelPart = isAnyDifficulty() ? "any difficulty" : difficulty.getDisplayName();
        return count + " × " + topicPart + " / " + levelPart;
    }

    @Override
    public String toString() {
        return describe();
    }
}
