package com.editora.diff;

import java.util.List;

import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictParserTest {

    private static final List<String> SAMPLE =
            List.of("line 1", "<<<<<<< HEAD", "ours a", "ours b", "=======", "theirs a", ">>>>>>> feature", "line 2");

    @Test
    void detectsMarkers() {
        assertTrue(ConflictParser.hasConflictMarkers(String.join("\n", SAMPLE)));
        assertFalse(ConflictParser.hasConflictMarkers("just\nplain\ntext"));
        assertFalse(ConflictParser.hasConflictMarkers(""));
    }

    @Test
    void parsesOursTheirsAndLabels() {
        ConflictFile f = ConflictParser.parse(SAMPLE);
        assertEquals(1, f.conflictCount());
        assertEquals(3, f.segments().size()); // plain, conflict, plain
        Conflict c = ((ConflictSegment) f.segments().get(1)).conflict();
        assertEquals("HEAD", c.oursLabel());
        assertEquals("feature", c.theirsLabel());
        assertEquals(List.of("ours a", "ours b"), c.ours());
        assertEquals(List.of("theirs a"), c.theirs());
    }

    @Test
    void resolveOursTheirsBothAndUnresolved() {
        ConflictFile f = ConflictParser.parse(SAMPLE);
        assertEquals(List.of("line 1", "ours a", "ours b", "line 2"), ConflictParser.resolve(f, List.of(Choice.OURS)));
        assertEquals(List.of("line 1", "theirs a", "line 2"), ConflictParser.resolve(f, List.of(Choice.THEIRS)));
        assertEquals(
                List.of("line 1", "ours a", "ours b", "theirs a", "line 2"),
                ConflictParser.resolve(f, List.of(Choice.BOTH)));
        // Unresolved → markers preserved (still valid conflict text).
        assertEquals(SAMPLE, ConflictParser.resolve(f, List.of(Choice.UNRESOLVED)));
        assertEquals(SAMPLE, ConflictParser.resolve(f, List.of())); // missing choice = unresolved
    }

    @Test
    void capturesThreeWayBaseRegion() {
        List<String> diff3 = List.of("<<<<<<< ours", "x", "||||||| base", "original", "=======", "y", ">>>>>>> theirs");
        ConflictFile f = ConflictParser.parse(diff3);
        assertTrue(f.hasBase());
        Conflict c = ((ConflictSegment) f.segments().get(0)).conflict();
        assertEquals(List.of("x"), c.ours());
        assertEquals(List.of("y"), c.theirs());
        assertTrue(c.hasBase());
        assertEquals("base", c.baseLabel());
        assertEquals(List.of("original"), c.base()); // base is captured, not skipped
    }

    @Test
    void twoWayConflictHasEmptyBase() {
        Conflict c = ((ConflictSegment) ConflictParser.parse(SAMPLE).segments().get(1)).conflict();
        assertFalse(c.hasBase());
        assertTrue(c.base().isEmpty());
        assertFalse(ConflictParser.parse(SAMPLE).hasBase());
    }

    @Test
    void resolveFromBaseAndRoundTripsDiff3Markers() {
        List<String> diff3 = List.of("<<<<<<< ours", "x", "||||||| base", "original", "=======", "y", ">>>>>>> theirs");
        ConflictFile f = ConflictParser.parse(diff3);
        assertEquals(List.of("original"), ConflictParser.resolve(f, List.of(Choice.BASE)));
        // Unresolved → the full diff3 markers (incl. the base region) are preserved verbatim.
        assertEquals(diff3, ConflictParser.resolve(f, List.of(Choice.UNRESOLVED)));
    }

    @Test
    void emptyDiff3BaseStillCountsAsPresent() {
        List<String> diff3 = List.of("<<<<<<< ours", "x", "||||||| base", "=======", "y", ">>>>>>> theirs");
        ConflictFile f = ConflictParser.parse(diff3);
        Conflict c = ((ConflictSegment) f.segments().get(0)).conflict();

        assertTrue(c.hasBase());
        assertTrue(c.base().isEmpty());
        assertEquals(diff3, ConflictParser.resolve(f, List.of(Choice.UNRESOLVED)));
    }

    /**
     * A reStructuredText / Markdown heading underline is a run of "=" longer than seven. Treating any line
     * that merely starts with seven marker characters as a marker ended "ours" at the underline, so a
     * marker-fallback merge of a document file lost or duplicated text.
     */
    @Test
    void aHeadingUnderlineInsideAConflictIsContentNotASeparator() {
        List<String> lines = List.of(
                "<<<<<<< HEAD",
                "Our Title",
                "==========",
                "our body",
                "=======",
                "Their Title",
                "===========",
                "their body",
                ">>>>>>> feature",
                "tail");
        ConflictFile f = ConflictParser.parse(lines);

        assertEquals(1, f.conflictCount());
        Conflict c = ((ConflictSegment) f.segments().get(0)).conflict();
        assertEquals(List.of("Our Title", "==========", "our body"), c.ours());
        assertEquals(List.of("Their Title", "===========", "their body"), c.theirs());
        assertEquals(
                List.of("Our Title", "==========", "our body", "tail"),
                ConflictParser.resolve(f, List.of(Choice.OURS)));
        assertEquals(lines, ConflictParser.resolve(f, List.of(Choice.UNRESOLVED)));
    }

    @Test
    void markersAreExactlySevenCharactersThenSpaceOrEndOfLine() {
        // Longer runs and runs glued to text are ordinary lines everywhere a marker is looked for.
        assertFalse(ConflictParser.hasConflictMarkers("<<<<<<<<< not a marker\nplain"));
        assertFalse(ConflictParser.hasConflictMarkers("<<<<<<<HEAD"));
        assertTrue(ConflictParser.hasConflictMarkers("<<<<<<<\nours\n=======\ntheirs\n>>>>>>>"));
        assertTrue(ConflictParser.hasConflictMarkers("<<<<<<< HEAD\r\nours"));

        List<String> lines = List.of(
                "<<<<<<< HEAD",
                "<<<<<<<<< quoted",
                "||||||||| table rule",
                "======= trailing words",
                ">>>>>>>>> quoted",
                "||||||| base",
                "was",
                "=======",
                ">>>>>>>>>> still theirs",
                ">>>>>>> feature");
        Conflict c = ((ConflictSegment) ConflictParser.parse(lines).segments().get(0)).conflict();
        assertEquals(
                List.of("<<<<<<<<< quoted", "||||||||| table rule", "======= trailing words", ">>>>>>>>> quoted"),
                c.ours());
        assertEquals(List.of("was"), c.base());
        assertEquals(List.of(">>>>>>>>>> still theirs"), c.theirs());
        assertEquals("feature", c.theirsLabel());
    }
}
