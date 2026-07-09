package com.editora.editor;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.markwhen.MwNode;
import com.editora.markwhen.Timeline;

/**
 * Renders a parsed Markwhen {@link Timeline} as a <b>month-grid calendar</b> — the alternative preview to
 * {@link MarkwhenTimeline} (toggled per file). A vertical stack of month cards (7-column Sun–Sat grids,
 * numbered days); each event shows as a tag-colored chip on the day(s) it covers, with a hover tooltip.
 * Fits the preview width (vertical scroll), unlike the horizontally-scrolling timeline. Long-spanning
 * events (&gt; {@link #MAX_SPREAD_DAYS}) show only on their start day so a multi-month event doesn't paint
 * every cell; a range wider than {@link #MAX_MONTHS} falls back to a placeholder.
 *
 * <p>Pure output builder; the caller parses off-thread and calls this on the FX thread.
 */
public final class MarkwhenCalendar {

    private MarkwhenCalendar() {}

    private static final int MAX_MONTHS = 120; // ~10 years; beyond → placeholder (use the timeline)
    private static final int MAX_SPREAD_DAYS = 62; // wider events chip only on their start day
    private static final int MAX_CHIPS_PER_DAY = 4;
    private static final String[] WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private record Ev(long startDay, long endExclusive, String label, Color color, String tooltip) {}

    /** Builds the calendar node. {@code zoomScale} scales the font + cell height; {@code viewportWidth} is
     *  unused (the calendar fits the preview width and scrolls vertically). */
    public static Node build(Timeline model, double zoomScale, double viewportWidth) {
        List<Ev> events = new ArrayList<>();
        collect(model.nodes(), model, events);
        String title = model.title();
        if (events.isEmpty()) {
            return placeholder(title, "No dated events to show on a calendar yet.");
        }

        long minDay = Long.MAX_VALUE;
        long maxDay = Long.MIN_VALUE;
        for (Ev e : events) {
            minDay = Math.min(minDay, e.startDay());
            maxDay = Math.max(maxDay, e.endExclusive() - 1);
        }
        YearMonth first = YearMonth.from(LocalDate.ofEpochDay(minDay));
        YearMonth last = YearMonth.from(LocalDate.ofEpochDay(maxDay));
        long months = ChronoUnit.MONTHS.between(first, last) + 1;
        if (months > MAX_MONTHS) {
            return placeholder(
                    title, "Date range too large for the calendar (" + months + " months) — use the timeline view.");
        }

        Map<Long, List<Ev>> byDay = new HashMap<>();
        for (Ev e : events) {
            long lastCovered = e.endExclusive() - 1;
            if (lastCovered - e.startDay() > MAX_SPREAD_DAYS) {
                byDay.computeIfAbsent(e.startDay(), k -> new ArrayList<>()).add(e);
            } else {
                for (long d = e.startDay(); d <= lastCovered; d++) {
                    byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(e);
                }
            }
        }

        double scale = zoomScale > 0 ? zoomScale : 1;
        VBox root = new VBox(16 * scale);
        root.getStyleClass().add("markwhen-calendar");
        root.setStyle("-fx-font-size: " + (13 * scale) + "px;");
        if (title != null && !title.isBlank()) {
            Label t = new Label(title);
            t.getStyleClass().add("markwhen-title");
            root.getChildren().add(t);
        }
        for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) {
            root.getChildren().add(monthCard(ym, byDay, scale));
        }
        return root;
    }

    private static Node monthCard(YearMonth ym, Map<Long, List<Ev>> byDay, double scale) {
        VBox card = new VBox(4);
        card.getStyleClass().add("markwhen-cal-month");
        Label header = new Label(ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
        header.getStyleClass().add("markwhen-cal-header");
        card.getChildren().add(header);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("markwhen-cal-grid");
        for (int c = 0; c < 7; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 7);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
            Label w = new Label(WEEKDAYS[c]);
            w.getStyleClass().add("markwhen-cal-weekday");
            w.setMaxWidth(Double.MAX_VALUE);
            grid.add(w, c, 0);
        }

        int startCol = ym.atDay(1).getDayOfWeek().getValue() % 7; // Monday=1..Sunday=7 → Sunday=col 0
        int daysInMonth = ym.lengthOfMonth();
        double cellH = 56 * scale;
        for (int day = 1; day <= daysInMonth; day++) {
            int idx = startCol + day - 1;
            LocalDate date = ym.atDay(day);
            VBox cell = dayCell(day, byDay.get(date.toEpochDay()), cellH);
            GridPane.setHgrow(cell, Priority.ALWAYS);
            grid.add(cell, idx % 7, 1 + idx / 7);
        }
        card.getChildren().add(grid);
        return card;
    }

    private static VBox dayCell(int day, List<Ev> evs, double minHeight) {
        VBox cell = new VBox(2);
        cell.getStyleClass().add("markwhen-cal-day");
        cell.setMinHeight(minHeight);
        cell.setMaxWidth(Double.MAX_VALUE);
        Label num = new Label(String.valueOf(day));
        num.getStyleClass().add("markwhen-cal-daynum");
        cell.getChildren().add(num);
        if (evs != null) {
            int shown = Math.min(evs.size(), MAX_CHIPS_PER_DAY);
            for (int i = 0; i < shown; i++) {
                cell.getChildren().add(chip(evs.get(i)));
            }
            if (evs.size() > shown) {
                Label more = new Label("+" + (evs.size() - shown));
                more.getStyleClass().add("markwhen-cal-more");
                cell.getChildren().add(more);
            }
        }
        return cell;
    }

    private static Label chip(Ev e) {
        Label chip = new Label(e.label());
        chip.getStyleClass().add("markwhen-cal-chip");
        chip.setMaxWidth(Double.MAX_VALUE);
        if (e.color() != null) {
            chip.setStyle("-fx-background-color: " + MarkwhenPaint.toRgba(e.color()) + ";");
        }
        Tooltip.install(chip, new Tooltip(e.tooltip()));
        return chip;
    }

    private static void collect(List<MwNode> nodes, Timeline model, List<Ev> out) {
        for (MwNode n : nodes) {
            if (n instanceof MwNode.Group g) {
                collect(g.children(), model, out);
            } else if (n instanceof MwNode.Event e) {
                long s = e.start().startEpochDay();
                long en = (e.end() != null ? e.end() : e.start()).endEpochDayExclusive();
                String label = e.label().isBlank() ? "(untitled)" : e.label();
                out.add(new Ev(s, en, label, MarkwhenPaint.colorFor(e, model), tooltip(e, label)));
            }
        }
    }

    private static String tooltip(MwNode.Event e, String label) {
        String span = e.end() == null
                ? e.start().start().toString()
                : e.start().start() + " – " + e.end().start();
        StringBuilder sb = new StringBuilder(span).append('\n').append(label);
        if (!e.tags().isEmpty()) {
            sb.append("\n#").append(String.join(" #", e.tags()));
        }
        return sb.toString();
    }

    private static Node placeholder(String title, String message) {
        VBox box = new VBox(6);
        box.getStyleClass().add("markwhen-calendar");
        if (title != null && !title.isBlank()) {
            Label t = new Label(title);
            t.getStyleClass().add("markwhen-title");
            box.getChildren().add(t);
        }
        Label m = new Label(message);
        m.getStyleClass().add("markwhen-placeholder");
        box.getChildren().add(m);
        return box;
    }
}
