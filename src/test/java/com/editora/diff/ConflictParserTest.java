package com.editora.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    void skipsThreeWayBaseRegion() {
        List<String> diff3 = List.of("<<<<<<< ours", "x", "||||||| base", "original", "=======", "y", ">>>>>>> theirs");
        Conflict c = ((ConflictSegment) ConflictParser.parse(diff3).segments().get(0)).conflict();
        assertEquals(List.of("x"), c.ours());
        assertEquals(List.of("y"), c.theirs()); // base ("original") is skipped
    }
}
