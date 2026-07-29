package hsts.common.enums;

/**
 * Where a bot's knowledge came from.
 *
 * <p>Requirement 66 allows questions from the course bank and study material the
 * teacher uploads; requirement 68 fixes the uploads as <i>"קבצי PDF, מסמכי Word,
 * או טקסט חופשי"</i> - PDF, Word, or free text.</p>
 *
 * <p>Whatever the source, what is stored is <b>text</b>. The bot is given the text
 * as context with the student's question, so a file that cannot be turned into
 * readable text is of no use to it - which is why an unreadable upload is refused
 * at the point of adding rather than accepted and quietly ignored.</p>
 */
public enum KnowledgeSourceType {

    QUESTION_BANK("Questions from the course bank"),
    PDF("PDF document"),
    WORD("Word document"),
    FREE_TEXT("Text typed in");

    private final String displayName;

    KnowledgeSourceType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** True for the two that arrive as an uploaded file. */
    public boolean isUpload() {
        return this == PDF || this == WORD;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
