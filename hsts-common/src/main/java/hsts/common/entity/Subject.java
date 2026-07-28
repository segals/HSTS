package hsts.common.entity;

import java.io.Serializable;

/**
 * A school subject, such as Mathematics.
 *
 * <p>Courses belong to subjects (system description §2: "כל קורס מיושך למקצוע").
 * Subjects and courses are managed by an external system and are never created
 * or edited inside HSTS (requirement 11) - they are simply seeded.</p>
 *
 * <p>The 2-digit code is not decoration: it forms the last two digits of every
 * 6-digit exam number.</p>
 */
public class Subject implements Serializable {

    private static final long serialVersionUID = 1L;

    private String subjectCode;   // exactly 2 digits
    private String name;

    public Subject() {
    }

    public Subject(String subjectCode, String name) {
        this.subjectCode = subjectCode;
        this.name = name;
    }

    public String getSubjectCode() { return subjectCode; }
    public String getName()        { return name; }

    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }
    public void setName(String name)               { this.name = name; }

    @Override
    public String toString() {
        return name;
    }
}
