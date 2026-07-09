package com.editora.markwhen;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the tolerant Markwhen document parser. */
class MarkwhenParserTest {

    private static MwNode.Event event(MwNode n) {
        return assertInstanceOf(MwNode.Event.class, n);
    }

    private static MwNode.Group group(MwNode n) {
        return assertInstanceOf(MwNode.Group.class, n);
    }

    @Test
    void unfencedHeaderTitleAndTagColors() {
        Timeline t = MarkwhenParser.parse(
                "title: My Timeline\n#Travel: blue\n#Economics: #abc // hex color\n\n2023: New Year\n");
        assertEquals("My Timeline", t.title());
        assertEquals(2, t.tagColors().size());
        assertEquals("Travel", t.tagColors().get(0).name());
        assertEquals("blue", t.tagColors().get(0).color());
        assertEquals("#abc", t.tagColors().get(1).color()); // trailing // comment stripped
        assertEquals(1, t.nodes().size());
        assertEquals("New Year", event(t.nodes().get(0)).label());
    }

    @Test
    void fencedHeader() {
        Timeline t = MarkwhenParser.parse("---\ntitle: Fenced\n#A: red\n---\n2020: x\n");
        assertEquals("Fenced", t.title());
        assertEquals(1, t.tagColors().size());
        assertEquals(1, t.nodes().size());
    }

    @Test
    void dateKeyIsAnEventNotHeader() {
        // No real header: a leading "2023: ..." must be an event, not consumed as a header key.
        Timeline t = MarkwhenParser.parse("2023: New Year\n2024: Next\n");
        assertNull(t.title());
        assertEquals(2, t.nodes().size());
        assertEquals("New Year", event(t.nodes().get(0)).label());
    }

    @Test
    void singleAndRangeEventsAndSeparators() {
        Timeline t = MarkwhenParser.parse(
                "2023-04-09: single\n2023 - 2024: dash range\n2023-01-22 / 2026-10-24: slash range\n");
        MwNode.Event single = event(t.nodes().get(0));
        assertNull(single.end());
        MwNode.Event dash = event(t.nodes().get(1));
        assertNotNull(dash.end());
        assertEquals(MwDate.Granularity.YEAR, dash.start().granularity());
        MwNode.Event slash = event(t.nodes().get(2));
        assertNotNull(slash.end());
        assertEquals(java.time.LocalDate.of(2026, 10, 24), slash.end().start());
    }

    @Test
    void internalDashNotSplitAsRange() {
        // "2023-04-09" must stay one date (internal '-' has no surrounding spaces).
        MwNode.Event e = event(MarkwhenParser.parse("2023-04-09: x\n").nodes().get(0));
        assertNull(e.end());
        assertEquals(java.time.LocalDate.of(2023, 4, 9), e.start().start());
    }

    @Test
    void tagsExtractedIncludingHyphen() {
        MwNode.Event e = event(
                MarkwhenParser.parse("2023: launch #v1 #hot-fix\n").nodes().get(0));
        assertEquals(List.of("v1", "hot-fix"), e.tags());
    }

    @Test
    void emptyLabelOk() {
        MwNode.Event e = event(MarkwhenParser.parse("2023:\n").nodes().get(0));
        assertEquals("", e.label());
    }

    @Test
    void headingSectionsNestByLevel() {
        Timeline t = MarkwhenParser.parse("# Outer\n2023: a\n## Inner\n2024: b\n# Sibling\n2025: c\n");
        assertEquals(2, t.nodes().size()); // Outer, Sibling
        MwNode.Group outer = group(t.nodes().get(0));
        assertEquals("Outer", outer.name());
        // Outer children: event a, then group Inner
        assertEquals(2, outer.children().size());
        assertEquals("a", event(outer.children().get(0)).label());
        MwNode.Group inner = group(outer.children().get(1));
        assertEquals("Inner", inner.name());
        assertEquals("b", event(inner.children().get(0)).label());
        assertEquals("Sibling", group(t.nodes().get(1)).name());
    }

    @Test
    void styleSectionMarksSection() {
        Timeline t = MarkwhenParser.parse("# Era\nstyle: section\n2023: a\n");
        MwNode.Group g = group(t.nodes().get(0));
        assertTrue(g.isSection());
        assertEquals(1, g.children().size()); // the style line is consumed, not an event
    }

    @Test
    void plainHeaderIsGroupNotSection() {
        assertFalse(
                group(MarkwhenParser.parse("# Era\n2023: a\n").nodes().get(0)).isSection());
    }

    @Test
    void tagDeclVsHeadingDisambiguatedBySpace() {
        // "#Travel:" (no space) is a tag color in the header; "# Travel" (space) is a section.
        Timeline t = MarkwhenParser.parse("#Travel: blue\n\n# Travel\n2023: a\n");
        assertEquals(1, t.tagColors().size());
        assertEquals(1, t.nodes().size());
        assertEquals("Travel", group(t.nodes().get(0)).name());
    }

    @Test
    void commentsAndBlanksAndGarbageSkipped() {
        Timeline t = MarkwhenParser.parse("// a comment\n\n2023: real\nthis is not an event\n// trailing\n");
        assertEquals(1, t.nodes().size());
        assertEquals("real", event(t.nodes().get(0)).label());
    }

    @Test
    void crlfHandled() {
        Timeline t = MarkwhenParser.parse("title: X\r\n\r\n2023: a\r\n2024: b\r\n");
        assertEquals("X", t.title());
        assertEquals(2, t.nodes().size());
    }

    @Test
    void neverThrowsOnArbitraryText() {
        String[] junk = {
            "#####",
            ":",
            " - ",
            "2023-",
            "-",
            "###### only hashes with space \n",
            "\n\n\n",
            "a:b:c:d",
            "# \n## \nstyle:section\n:::",
            "2023/ /2024: broken",
            null,
            ""
        };
        for (String s : junk) {
            Timeline t = MarkwhenParser.parse(s);
            assertNotNull(t); // no exception, always a (possibly empty) timeline
        }
    }

    @Test
    void missingEndAutoClosesAtEof() {
        // No explicit close needed (heading-based); the group just runs to EOF.
        Timeline t = MarkwhenParser.parse("# Open\n2023: a\n2024: b\n");
        MwNode.Group g = group(t.nodes().get(0));
        assertEquals(2, g.children().size());
    }
}
