package hsts.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * One thing a member of staff did, with the moment it happened.
 *
 * <h2>What is recorded, and what is not</h2>
 *
 * <p>Only actions that <b>change</b> something, and only by a teacher or a subject
 * coordinator. Reading a screen is not an action; a student sitting an exam is her
 * own business and already recorded against her paper; and the principal changes
 * nothing at all (system description §7.3), so a log of her would always be empty.</p>
 *
 * <p>The detail is the sentence the system gave the person at the time - "Exam
 * 010101 approved. It can now be released to a class." - rather than a second
 * description invented for the log. If the two ever disagreed, one of them would
 * be wrong, and it would be this one.</p>
 */
public class ActivityEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private long entryId;
    private LocalDateTime at;

    private String userId;
    private String userName;
    private String role;

    /** A short label: "Approved an exam", "Released an exam". */
    private String action;

    /** What the person was told at the time. */
    private String detail;

    public ActivityEntry() {
    }

    public long getEntryId()          { return entryId; }
    public LocalDateTime getAt()      { return at; }
    public String getUserId()         { return userId; }
    public String getUserName()       { return userName; }
    public String getRole()           { return role; }
    public String getAction()         { return action; }
    public String getDetail()         { return detail; }

    public void setEntryId(long id)              { this.entryId = id; }
    public void setAt(LocalDateTime at)          { this.at = at; }
    public void setUserId(String userId)         { this.userId = userId; }
    public void setUserName(String name)         { this.userName = name; }
    public void setRole(String role)             { this.role = role; }
    public void setAction(String action)         { this.action = action; }
    public void setDetail(String detail)         { this.detail = detail; }

    @Override
    public String toString() {
        return at + "  " + userName + "  " + action;
    }
}
