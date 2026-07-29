package hsts.common.protocol;

import hsts.common.enums.KnowledgeSourceType;

import java.io.Serializable;

/**
 * Adding one knowledge source to a bot (requirements 66 and 68).
 *
 * <p>An upload travels as <b>bytes</b>, not as text the client has already
 * extracted. The server does the extraction, for two reasons: the client would
 * otherwise need the same parsing code, and the rule about what counts as readable
 * material has to be enforced somewhere a client cannot talk its way past.</p>
 *
 * <p>{@link #getText()} is used for free text and for the question bank, where
 * there is nothing to extract. Exactly one of the two is set.</p>
 */
public class SourceRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int botId;
    private final KnowledgeSourceType type;
    private final String title;
    private final String text;
    private final byte[] fileBytes;

    private SourceRequest(int botId, KnowledgeSourceType type, String title,
                          String text, byte[] fileBytes) {
        this.botId = botId;
        this.type = type;
        this.title = title;
        this.text = text;
        this.fileBytes = fileBytes;
    }

    /** Free text the teacher typed. */
    public static SourceRequest text(int botId, String title, String text) {
        return new SourceRequest(botId, KnowledgeSourceType.FREE_TEXT, title, text, null);
    }

    /** Every current question in the bot's course, pulled in by the server. */
    public static SourceRequest questionBank(int botId, String title) {
        return new SourceRequest(botId, KnowledgeSourceType.QUESTION_BANK, title, null, null);
    }

    /** A PDF or Word file, still in its original bytes. */
    public static SourceRequest upload(int botId, KnowledgeSourceType type,
                                       String title, byte[] fileBytes) {
        return new SourceRequest(botId, type, title, null, fileBytes);
    }

    public int getBotId()                  { return botId; }
    public KnowledgeSourceType getType()   { return type; }
    public String getTitle()               { return title; }
    public String getText()                { return text; }
    public byte[] getFileBytes()           { return fileBytes; }
}
