package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import com.editora.editor.EditorFx;
import com.editora.i18n.Messages;
import com.editora.markwhen.MarkwhenParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markwhen calendar and timeline ({@link MarkwhenCalendar}, {@link MarkwhenTimeline}): what each shows when
 * there is nothing to plot or too much, how the calendar caps a crowded day and a long event, where a tag's
 * declared colour wins over the palette, and how the timeline draws a section, a single instant and a span of
 * years.
 */
@Tag("fx")
class MarkwhenViewsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static List<Node> nodes(Node root, Predicate<Node> test) {
        List<Node> out = new ArrayList<>();
        collect(root, test, out);
        return out;
    }

    private static void collect(Node node, Predicate<Node> test, List<Node> out) {
        if (test.test(node)) {
            out.add(node);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, test, out);
            }
        }
    }

    private static List<Label> labels(Node root, String styleClass) {
        return nodes(root, n -> n instanceof Label && n.getStyleClass().contains(styleClass)).stream()
                .map(n -> (Label) n)
                .toList();
    }

    private static List<String> texts(Node root) {
        return nodes(root, n -> n instanceof Label).stream()
                .map(n -> ((Label) n).getText())
                .toList();
    }

    private static Node calendar(String text) throws Exception {
        return EditorFx.callFx(() -> MarkwhenCalendar.build(MarkwhenParser.parse(text), 1.0, 700));
    }

    private static Node timeline(String text) throws Exception {
        return EditorFx.callFx(() -> MarkwhenTimeline.build(MarkwhenParser.parse(text), 1.0, 700));
    }

    @Test
    void withNothingDatedBothViewsSaySoUnderTheTitle() throws Exception {
        String undated = "title: Someday\n\nJust a note, no dates.\n";
        Node cal = calendar(undated);
        assertEquals(List.of("Someday", Messages.tr("markwhen.calendar.empty")), texts(cal));
        Node line = timeline(undated);
        assertEquals(List.of("Someday", Messages.tr("markwhen.timeline.empty")), texts(line));
    }

    @Test
    void tooMuchFallsBackToAPlaceholderInsteadOfAHugeView() throws Exception {
        Node cal = calendar("2000-01-01: Start\n2015-01-01: End\n");
        assertEquals(
                List.of(Messages.tr("markwhen.calendar.tooLarge", 181L)),
                texts(cal),
                "181 months is past the calendar's ten years");

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            many.append("2026-01-01: Event ").append(i).append('\n');
        }
        Node line = timeline(many.toString());
        assertEquals(List.of(Messages.tr("markwhen.timeline.tooLarge", 501)), texts(line));
    }

    @Test
    void aCrowdedDayShowsFourChipsAndACountAndALongEventOnlyItsStartDay() throws Exception {
        StringBuilder text = new StringBuilder("title: Plan\n#Plan: #ff0000\n#Bad: not-a-colour\n\n");
        text.append("2026-01-05 - 2026-04-30: Long haul #Plan\n");
        for (int i = 1; i <= 5; i++) {
            text.append("2026-03-02: Busy ").append(i).append('\n');
        }
        text.append("# Work\n2026-03-10: Inside a group #Bad\n");
        Node cal = calendar(text.toString());

        List<Label> chips = labels(cal, "markwhen-cal-chip");
        assertEquals(
                1,
                chips.stream()
                        .filter(c -> c.getText().equals("Long haul #Plan"))
                        .count(),
                "an event over two months chips only on its start day: "
                        + chips.stream().map(Label::getText).toList());
        assertEquals(
                4, chips.stream().filter(c -> c.getText().startsWith("Busy")).count(), "four chips a day");
        assertEquals(
                List.of("+1"),
                labels(cal, "markwhen-cal-more").stream().map(Label::getText).toList());

        Label planned = chips.stream()
                .filter(c -> c.getText().equals("Long haul #Plan"))
                .findFirst()
                .orElseThrow();
        assertTrue(planned.getStyle().contains("rgba(255,0,0,"), "the declared #Plan colour: " + planned.getStyle());
        Label grouped = chips.stream()
                .filter(c -> c.getText().equals("Inside a group #Bad"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("an event inside a group is on the calendar"));
        assertTrue(
                grouped.getStyle().contains("rgba("),
                "a tag whose colour does not parse falls back to the palette: " + grouped.getStyle());
        // Tooltip.install keeps the tooltip in the node's properties, not in Control#tooltipProperty.
        Tooltip tip = (Tooltip) grouped.getProperties().get("javafx.scene.control.Tooltip");
        assertNotNull(tip, "a chip names its dates and tags on hover");
        assertTrue(tip.getText().contains("2026-03-10") && tip.getText().contains("#Bad"), tip.getText());
    }

    @Test
    void theTimelineDrawsASectionBandAnInstantAndYearTicksForALongSpan() throws Exception {
        Node section = timeline("# Travel\nstyle: section\n2026-03-02: Trip\n");
        assertFalse(nodes(section, n -> n.getStyleClass().contains("markwhen-section-band"))
                .isEmpty());
        assertEquals(
                List.of("Travel"),
                labels(section, "markwhen-section").stream().map(Label::getText).toList());
        assertTrue(texts(section).contains("Trip"), "a single instant still plots: " + texts(section));

        Node years = timeline("2010-01-01: Founded\n2026-06-01: Today\n");
        List<String> ticks = texts(years);
        assertTrue(ticks.contains("2010") && ticks.contains("2026"), "a span of years ticks by year: " + ticks);
    }
}
