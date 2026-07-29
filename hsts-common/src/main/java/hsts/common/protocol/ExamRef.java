package hsts.common.protocol;

import java.io.Serializable;

/**
 * Points at one exam, optionally at one exact version of it.
 *
 * <p>Exams are versioned, so "which exam" is two pieces of information. A version
 * of {@code 0} means "whichever version is current".</p>
 */
public class ExamRef implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CURRENT = 0;

    private final String examId;
    private final int version;

    public ExamRef(String examId, int version) {
        this.examId = examId;
        this.version = version;
    }

    public ExamRef(String examId) {
        this(examId, CURRENT);
    }

    public String getExamId() { return examId; }
    public int getVersion()   { return version; }

    @Override
    public String toString() {
        return examId + (version == CURRENT ? " (current)" : " v" + version);
    }
}
