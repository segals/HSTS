package hsts.common.entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * Average, median and decile spread for one sitting.
 *
 * <p>Requirement 54 asks for all three. Requirement 55 says a student may never
 * see any of them - so this object only ever goes to a teacher or the principal,
 * and there is a test that a student asking for it is refused.</p>
 *
 * <h2>The decile buckets</h2>
 *
 * <p>Acceptance test 3.14 fixes them as 0-10, 11-20, ... , 91-100. That is ten
 * buckets, the first holding eleven possible marks and the rest ten each. An odd
 * split, but it is the one already written into the submitted test plan, so it is
 * the one implemented.</p>
 */
public class ExamStatistics implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int DECILE_COUNT = 10;

    private int executionId;
    private double average;
    private double median;

    /** How many marks fell in each bucket, lowest first. */
    private int[] deciles = new int[DECILE_COUNT];

    private int gradeCount;
    private LocalDateTime computedAt;

    public ExamStatistics() {
    }

    /**
     * Works the figures out over a list of marks.
     *
     * <p>Lives here rather than in a DAO because four different callers need it -
     * one sitting, one whole exam, and the three reports of מתווה 12 - and a demo
     * where the class average and the report average are produced by two separate
     * pieces of arithmetic is a demo waiting to embarrass somebody.</p>
     *
     * @param marks the marks to describe. Sorted in place, so the median can be
     *              read off directly; callers hand in a list they own.
     */
    public static ExamStatistics over(java.util.List<Integer> marks) {
        ExamStatistics stats = new ExamStatistics();
        stats.setComputedAt(LocalDateTime.now());

        if (marks == null || marks.isEmpty()) {
            stats.setGradeCount(0);
            return stats;
        }
        java.util.Collections.sort(marks);
        stats.setGradeCount(marks.size());

        double sum = 0;
        int[] buckets = new int[DECILE_COUNT];
        for (int mark : marks) {
            sum += mark;
            buckets[bucketFor(mark)]++;
        }
        stats.setAverage(sum / marks.size());
        stats.setDeciles(buckets);

        // Odd count: the middle one. Even count: the mean of the middle two,
        // which is the ordinary definition and matches acceptance test 3.8.
        int middle = marks.size() / 2;
        stats.setMedian(marks.size() % 2 == 1
                ? marks.get(middle)
                : (marks.get(middle - 1) + marks.get(middle)) / 2.0);

        return stats;
    }

    public int getExecutionId()          { return executionId; }
    public double getAverage()           { return average; }
    public double getMedian()            { return median; }
    public int[] getDeciles()            { return deciles; }
    public int getGradeCount()           { return gradeCount; }
    public LocalDateTime getComputedAt() { return computedAt; }

    public void setExecutionId(int id)          { this.executionId = id; }
    public void setAverage(double average)      { this.average = average; }
    public void setMedian(double median)        { this.median = median; }
    public void setGradeCount(int count)        { this.gradeCount = count; }
    public void setComputedAt(LocalDateTime at) { this.computedAt = at; }

    public void setDeciles(int[] deciles) {
        this.deciles = (deciles == null) ? new int[DECILE_COUNT]
                                         : Arrays.copyOf(deciles, DECILE_COUNT);
    }

    /**
     * Which bucket a mark belongs to, per acceptance test 3.14.
     *
     * <p>0-10 is bucket 0; after that each bucket is ten wide. Note that 100 lands
     * in the last bucket rather than falling off the end - the mistake a plain
     * {@code grade / 10} makes.</p>
     */
    public static int bucketFor(int grade) {
        if (grade <= 10) {
            return 0;
        }
        return Math.min(DECILE_COUNT - 1, (grade - 1) / 10);
    }

    /** Readable label for one bucket, e.g. "11-20". */
    public static String bucketLabel(int index) {
        return index == 0 ? "0-10" : (index * 10 + 1) + "-" + ((index + 1) * 10);
    }

    public int getLargestBucket() {
        int largest = 0;
        for (int count : deciles) {
            largest = Math.max(largest, count);
        }
        return largest;
    }
}
