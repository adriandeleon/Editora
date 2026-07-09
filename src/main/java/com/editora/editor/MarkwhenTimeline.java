package com.editora.editor;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import com.editora.markwhen.MwNode;
import com.editora.markwhen.Timeline;

/**
 * Renders a parsed Markwhen {@link Timeline} into a JavaFX timeline node for the in-editor preview — the
 * analog of {@link MarkdownRenderer#renderDocument} for Markdown. <b>Node-based</b> (a {@link Pane} of
 * positioned {@link Region} bars / {@link Circle} points / {@link Label}s), not a {@code Canvas}: a useful
 * subset is tens–low-hundreds of events, so nodes buy hover tooltips, easy text, and theme-tracking CSS
 * ({@code .markwhen-*} looked-up colors) that a Canvas would forfeit. Bounded at {@link #MAX_EVENTS} → a
 * placeholder, so the node count can't blow up.
 *
 * <p>Pure output builder (no I/O, no threads); the caller parses off-thread and calls this on the FX
 * thread. Horizontal zoom scales the time axis width (not font size — like the Mermaid diagram branch).
 */
public final class MarkwhenTimeline {

    private MarkwhenTimeline() {}

    private static final double AXIS_H = 30;
    private static final double ROW_H = 26;
    private static final double BAR_H = 14;
    private static final double POINT_R = 5;
    private static final double LEFT_GUTTER = 14;
    private static final double RIGHT_PAD = 28;
    private static final double MIN_AXIS_W = 560;
    private static final double INDENT = 14;
    private static final int MAX_EVENTS = 500;

    /** One rendered line: an event (a bar/point) or a {@code #}-header group (a label + optional band). */
    private record Row(MwNode node, int depth) {}

    /** Builds the timeline node. {@code zoomScale} scales the time-axis width; {@code viewportWidth} is the
     *  preview pane's current width (0 when not yet laid out → falls back to {@link #MIN_AXIS_W}). */
    public static Node build(Timeline model, double zoomScale, double viewportWidth) {
        List<Row> rows = new ArrayList<>();
        long[] domain = {Long.MAX_VALUE, Long.MIN_VALUE};
        int[] count = {0};
        flatten(model.nodes(), 0, rows, domain, count);

        if (count[0] == 0) {
            return placeholder(model.title(), "No dated events to plot yet.");
        }
        if (count[0] > MAX_EVENTS) {
            return placeholder(model.title(), "Timeline too large to preview (" + count[0] + " events).");
        }

        long min = domain[0];
        long max = domain[1];
        if (max <= min) {
            max = min + 1; // a single instant — give it a non-zero span
        }
        double base = Math.max(MIN_AXIS_W, (viewportWidth > 0 ? viewportWidth : MIN_AXIS_W) - LEFT_GUTTER - RIGHT_PAD);
        double axisW = base * (zoomScale > 0 ? zoomScale : 1);
        double totalW = LEFT_GUTTER + axisW + RIGHT_PAD;
        double totalH = AXIS_H + rows.size() * ROW_H + RIGHT_PAD;

        Pane pane = new Pane();
        pane.getStyleClass().add("markwhen-timeline");

        // Axis ticks + full-height gridlines.
        for (long[] tick : ticks(min, max)) {
            double x = xFor(tick[0], min, max, axisW);
            Region grid = new Region();
            grid.getStyleClass().add("markwhen-grid");
            sizeAt(grid, x, AXIS_H, 1, Math.max(1, totalH - AXIS_H));
            Label axisLabel = new Label(tickLabel(tick[0], tick[1]));
            axisLabel.getStyleClass().add("markwhen-axis-label");
            axisLabel.setLayoutX(x + 3);
            axisLabel.setLayoutY(7);
            pane.getChildren().addAll(grid, axisLabel);
        }

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double y = AXIS_H + i * ROW_H;
            if (r.node() instanceof MwNode.Group g) {
                renderGroup(pane, g, r.depth(), y, totalW);
            } else if (r.node() instanceof MwNode.Event e) {
                renderEvent(pane, e, y, min, max, axisW, model);
            }
        }

        pane.setPrefSize(totalW, totalH);
        pane.setMinSize(totalW, totalH); // keep width even under the ScrollPane's fitToWidth → horiz scroll

        VBox root = new VBox(6);
        root.getStyleClass().add("markwhen-root");
        if (model.title() != null && !model.title().isBlank()) {
            Label title = new Label(model.title());
            title.getStyleClass().add("markwhen-title");
            root.getChildren().add(title);
        }
        root.getChildren().add(pane);
        return root;
    }

    private static void renderGroup(Pane pane, MwNode.Group g, int depth, double y, double totalW) {
        if (g.isSection()) {
            Region band = new Region();
            band.getStyleClass().add("markwhen-section-band");
            sizeAt(band, 0, y, totalW, ROW_H);
            pane.getChildren().add(band);
        }
        Label name = new Label(g.name());
        name.getStyleClass().add(g.isSection() ? "markwhen-section" : "markwhen-group");
        name.setLayoutX(LEFT_GUTTER + depth * INDENT);
        name.setLayoutY(y + 4);
        pane.getChildren().add(name);
    }

    private static void renderEvent(
            Pane pane, MwNode.Event e, double y, long min, long max, double axisW, Timeline model) {
        long startDay = e.start().startEpochDay();
        long endDay = (e.end() != null ? e.end() : e.start()).endEpochDayExclusive();
        double x1 = xFor(startDay, min, max, axisW);
        double x2 = xFor(endDay, min, max, axisW);
        double w = x2 - x1;
        Color color = MarkwhenPaint.colorFor(e, model);
        String text = e.label().isBlank() ? "(untitled)" : e.label();

        Node mark;
        double labelX;
        if (w < 2 * POINT_R) {
            Circle point = new Circle(POINT_R);
            point.setLayoutX(x1);
            point.setLayoutY(y + ROW_H / 2);
            if (color != null) {
                point.setFill(color);
            } else {
                point.getStyleClass().add("markwhen-point");
            }
            mark = point;
            labelX = x1 + POINT_R + 5;
        } else {
            Region bar = new Region();
            sizeAt(bar, x1, y + (ROW_H - BAR_H) / 2, w, BAR_H);
            if (color != null) {
                bar.setStyle("-fx-background-color: " + MarkwhenPaint.toRgba(color) + "; -fx-background-radius: 3;");
            } else {
                bar.getStyleClass().add("markwhen-bar");
            }
            mark = bar;
            labelX = x2 + 5;
        }
        Tooltip.install(mark, new Tooltip(tooltipText(e)));
        pane.getChildren().add(mark);

        Label label = new Label(text);
        label.getStyleClass().add("markwhen-label");
        label.setLayoutX(labelX);
        label.setLayoutY(y + 4);
        pane.getChildren().add(label);
    }

    private static void flatten(List<MwNode> nodes, int depth, List<Row> rows, long[] domain, int[] count) {
        for (MwNode n : nodes) {
            if (n instanceof MwNode.Group g) {
                rows.add(new Row(g, depth));
                flatten(g.children(), depth + 1, rows, domain, count);
            } else if (n instanceof MwNode.Event e) {
                rows.add(new Row(e, depth));
                count[0]++;
                domain[0] = Math.min(domain[0], e.start().startEpochDay());
                domain[1] = Math.max(domain[1], (e.end() != null ? e.end() : e.start()).endEpochDayExclusive());
            }
        }
    }

    private static double xFor(long epochDay, long min, long max, double axisW) {
        double frac = (double) (epochDay - min) / (double) (max - min);
        return LEFT_GUTTER + Math.max(0, Math.min(1, frac)) * axisW;
    }

    /** Axis ticks as {@code [epochDay, unit]} where unit 0=year, 1=month, 2=day (drives the label form).
     *  Picks the unit by span and caps the count by stepping the interval up. */
    static List<long[]> ticks(long min, long max) {
        List<long[]> out = new ArrayList<>();
        LocalDate lo = LocalDate.ofEpochDay(min);
        LocalDate hi = LocalDate.ofEpochDay(max);
        long span = max - min;
        if (span > 366L * 5) {
            int years = hi.getYear() - lo.getYear() + 1;
            int step = Math.max(1, (int) Math.ceil(years / 30.0));
            for (int y = lo.getYear(); y <= hi.getYear(); y += step) {
                out.add(new long[] {LocalDate.of(y, 1, 1).toEpochDay(), 0});
            }
        } else if (span > 62) {
            long months = (hi.getYear() - lo.getYear()) * 12L + (hi.getMonthValue() - lo.getMonthValue()) + 1;
            int step = Math.max(1, (int) Math.ceil(months / 24.0));
            LocalDate d = LocalDate.of(lo.getYear(), lo.getMonthValue(), 1);
            while (!d.isAfter(hi)) {
                out.add(new long[] {d.toEpochDay(), 1});
                d = d.plusMonths(step);
            }
        } else {
            long step = Math.max(1, span / 12);
            for (long e = min; e <= max; e += step) {
                out.add(new long[] {e, 2});
            }
        }
        return out;
    }

    private static String tickLabel(long epochDay, long unit) {
        LocalDate d = LocalDate.ofEpochDay(epochDay);
        return switch ((int) unit) {
            case 0 -> String.valueOf(d.getYear());
            case 1 -> d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
            default -> d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
        };
    }

    private static String tooltipText(MwNode.Event e) {
        StringBuilder sb = new StringBuilder(dateSpan(e));
        if (!e.label().isBlank()) {
            sb.append('\n').append(e.label());
        }
        if (!e.tags().isEmpty()) {
            sb.append("\n#").append(String.join(" #", e.tags()));
        }
        return sb.toString();
    }

    private static String dateSpan(MwNode.Event e) {
        String start = e.start().start().toString();
        return e.end() == null ? start : start + " – " + e.end().start();
    }

    private static void sizeAt(Region r, double x, double y, double w, double h) {
        r.setLayoutX(x);
        r.setLayoutY(y);
        r.setMinSize(w, h);
        r.setPrefSize(w, h);
        r.setMaxSize(w, h);
    }

    private static Node placeholder(String title, String message) {
        VBox box = new VBox(6);
        box.getStyleClass().add("markwhen-root");
        if (title != null && !title.isBlank()) {
            Label t = new Label(title);
            t.getStyleClass().add("markwhen-title");
            box.getChildren().add(t);
        }
        Label msg = new Label(message);
        msg.getStyleClass().add("markwhen-placeholder");
        box.getChildren().add(msg);
        return box;
    }
}
