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
}
