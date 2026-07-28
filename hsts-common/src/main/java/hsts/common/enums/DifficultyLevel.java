package hsts.common.enums;

/**
 * How hard a question is.
 *
 * <p>Automatic exam building selects questions by topic <em>and</em> difficulty
 * (requirement 28, מתווה scenario 3), so this is not decoration - it is one of
 * the two axes the selection algorithm filters on.</p>
 */
public enum DifficultyLevel {

    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard");

    private final String displayName;

    DifficultyLevel(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
