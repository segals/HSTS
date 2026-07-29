package hsts.common.entity;

import hsts.common.enums.KnowledgeSourceType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One thing a bot has been told to read - the {@code KnowledgeSource} of the
 * submitted class diagram.
 *
 * <p>Requirements 66 and 68. {@link #content} is always <b>text</b>, whatever it
 * arrived as: an uploaded Word document is turned into text when it is added, not
 * when it is used, so a file that cannot be read is refused while the teacher is
 * still looking at the screen rather than failing silently later.</p>
 */
public class KnowledgeSource implements Serializable {

    private static final long serialVersionUID = 1L;

    private int sourceId;
    private int botId;
    private KnowledgeSourceType type;
    private String title;
    private String content;

    /** Requirement 67: a colleague's additions are attributed, not anonymous. */
    private String addedBy;
    private String addedByName;
    private LocalDateTime addedAt;

    public KnowledgeSource() {
    }

    public int getSourceId()              { return sourceId; }
    public int getBotId()                 { return botId; }
    public KnowledgeSourceType getType()  { return type; }
    public String getTitle()              { return title; }
    public String getContent()            { return content; }
    public String getAddedBy()            { return addedBy; }
    public String getAddedByName()        { return addedByName; }
    public LocalDateTime getAddedAt()     { return addedAt; }

    public void setSourceId(int id)                  { this.sourceId = id; }
    public void setBotId(int botId)                  { this.botId = botId; }
    public void setType(KnowledgeSourceType type)    { this.type = type; }
    public void setTitle(String title)               { this.title = title; }
    public void setContent(String content)           { this.content = content; }
    public void setAddedBy(String userId)            { this.addedBy = userId; }
    public void setAddedByName(String name)          { this.addedByName = name; }
    public void setAddedAt(LocalDateTime at)         { this.addedAt = at; }

    /** Characters of text, for the screen - the content itself can be very long. */
    public int getLength() {
        return content == null ? 0 : content.length();
    }

    /** The opening of the text, so the teacher can see what she actually added. */
    public String getPreview(int characters) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= characters ? flat : flat.substring(0, characters) + "...";
    }

    @Override
    public String toString() {
        return title + "  ·  " + (type == null ? "" : type.getDisplayName());
    }
}
