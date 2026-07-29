package hsts.common.entity;

import hsts.common.enums.BotStatus;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A course's study bot - the {@code Bot} of the submitted class diagram.
 *
 * <p>SUC-13. One bot per course, which the schema enforces with a UNIQUE key on
 * {@code course_code}. That is requirement 67 written into the data model rather
 * than into code: <i>"אם לקורס יש יותר ממורה אחת ובוט קיים, מורה נוספת יכולה
 * להוסיף לבוט הקיים"</i> - a second teacher adds to the existing bot, so there
 * must not be a second bot for her to add to instead.</p>
 */
public class Bot implements Serializable {

    private static final long serialVersionUID = 1L;

    private int botId;
    private String courseCode;
    private String courseName;
    private String name;
    private BotStatus status = BotStatus.INACTIVE;

    /** Who created it. Not who may edit it - that is anyone teaching the course. */
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;

    private List<KnowledgeSource> sources = new ArrayList<>();

    public Bot() {
    }

    public int getBotId()                 { return botId; }
    public String getCourseCode()         { return courseCode; }
    public String getCourseName()         { return courseName; }
    public String getName()               { return name; }
    public BotStatus getStatus()          { return status; }
    public String getCreatedBy()          { return createdBy; }
    public String getCreatedByName()      { return createdByName; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public List<KnowledgeSource> getSources() { return sources; }

    public void setBotId(int botId)                { this.botId = botId; }
    public void setCourseCode(String code)         { this.courseCode = code; }
    public void setCourseName(String name)         { this.courseName = name; }
    public void setName(String name)               { this.name = name; }
    public void setStatus(BotStatus status)        { this.status = status; }
    public void setCreatedBy(String userId)        { this.createdBy = userId; }
    public void setCreatedByName(String name)      { this.createdByName = name; }
    public void setCreatedAt(LocalDateTime at)     { this.createdAt = at; }

    public void setSources(List<KnowledgeSource> sources) {
        // A copy, not the caller's list: a view returned by List.subList is not
        // serialisable, and that has already broken this project once.
        this.sources = (sources == null) ? new ArrayList<>() : new ArrayList<>(sources);
    }

    public boolean isActive() {
        return status == BotStatus.ACTIVE;
    }

    /** A bot with nothing to read from has nothing to answer with. */
    public boolean hasKnowledge() {
        return !sources.isEmpty();
    }

    @Override
    public String toString() {
        return name + "  ·  " + courseName + "  ·  " + status.getDisplayName();
    }
}
