package hsts.client.gui;

import hsts.common.entity.ExamStatistics;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The decile distribution of a set of marks, drawn as bars.
 *
 * <p>מתווה scenario 10 asks for the marks <i>"בטבלה וכן בצורת היסטוגרמה"</i> - in a
 * table and as a histogram - and requirement 54 defines the ten buckets: 0-10,
 * 11-20, and so on to 91-100.</p>
 *
 * <h2>Drawn by hand rather than with a chart control</h2>
 *
 * <p>JavaFX has a {@code BarChart}, but it brings its own axes, legend, animation
 * and stylesheet, and fighting all four into the look of the rest of the
 * application costs more than ten rectangles are worth. Ten bars with a label
 * above and a range below is the whole requirement.</p>
 *
 * <p>Bar heights are relative to the <b>largest</b> bucket, not to the number of
 * students, so a class of eleven still fills the box. An empty bucket is drawn as
 * a thin line rather than nothing at all, so ten columns are always visible and
 * "no students scored 0-10" reads differently from "the chart is broken".</p>
 */
public class HistogramView extends VBox {

    /** How tall the tallest bar is drawn. */
    private static final double CHART_HEIGHT = 170;

    /** So an empty bucket is still visibly a bucket. */
    private static final double EMPTY_BAR_HEIGHT = 2;

    private final HBox bars = new HBox(6);
    private final Label caption = new Label();

    public HistogramView() {
        setSpacing(8);
        bars.setAlignment(Pos.BOTTOM_CENTER);
        bars.setMinHeight(CHART_HEIGHT + 42);      // room for the labels above and below
        caption.getStyleClass().add("caption");
        caption.setWrapText(true);
        getChildren().addAll(bars, caption);
    }

    /** Draws the distribution, or an explanation if there is nothing to draw. */
    public void show(ExamStatistics statistics) {
        bars.getChildren().clear();

        if (statistics == null || statistics.getGradeCount() == 0) {
            caption.setText("No approved marks yet, so there is nothing to plot. "
                          + "The histogram counts approved marks only.");
            return;
        }

        int[] deciles = statistics.getDeciles();
        int largest = Math.max(1, statistics.getLargestBucket());

        for (int bucket = 0; bucket < ExamStatistics.DECILE_COUNT; bucket++) {
            bars.getChildren().add(column(bucket, deciles[bucket], largest,
                                          statistics.getGradeCount()));
        }

        caption.setText(String.format(
                "%d approved mark(s)  ·  average %.1f  ·  median %.1f  ·  "
              + "each column is one ten-point band",
                statistics.getGradeCount(), statistics.getAverage(), statistics.getMedian()));
    }

    private VBox column(int bucket, int count, int largest, int total) {
        Label above = new Label(count == 0 ? "" : String.valueOf(count));
        above.getStyleClass().add("caption");

        Region bar = new Region();
        bar.setPrefHeight(count == 0
                ? EMPTY_BAR_HEIGHT
                : Math.max(4, CHART_HEIGHT * count / (double) largest));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add(count == 0 ? "histogram-bar-empty" : "histogram-bar");

        Label below = new Label(ExamStatistics.bucketLabel(bucket));
        below.getStyleClass().add("caption");
        below.setWrapText(true);
        below.setAlignment(Pos.CENTER);
        below.setMaxWidth(Double.MAX_VALUE);

        VBox column = new VBox(4, above, bar, below);
        column.setAlignment(Pos.BOTTOM_CENTER);
        HBox.setHgrow(column, Priority.ALWAYS);
        column.setMaxWidth(Double.MAX_VALUE);

        String share = total == 0 ? "" : String.format("  (%.0f%%)", 100.0 * count / total);
        Tooltip.install(column, new Tooltip(ExamStatistics.bucketLabel(bucket) + ":  "
                + count + (count == 1 ? " student" : " students") + share));
        return column;
    }
}
