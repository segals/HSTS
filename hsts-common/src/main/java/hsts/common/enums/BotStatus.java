package hsts.common.enums;

/**
 * Whether a course's bot is available to students.
 *
 * <p>Requirement 60 lets the teacher turn it on and off; requirement 70 says a
 * student may use it <em>only</em> while it is on. A bot starts {@link #INACTIVE}
 * so that a half-configured one - created but with no knowledge sources yet -
 * cannot answer anybody.</p>
 */
public enum BotStatus {

    ACTIVE("Active"),
    INACTIVE("Not active");

    private final String displayName;

    BotStatus(String displayName) {
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
