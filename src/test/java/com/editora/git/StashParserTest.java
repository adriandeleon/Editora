package com.editora.git;

import java.util.List;

import com.editora.git.StashParser.StashEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure {@code git stash list} parser. */
class StashParserTest {

    @Test
    void parsesWipAndNamedStashes() {
        String out = """
                stash@{0}: WIP on master: 1a2b3c4 Fix the gutter
                stash@{1}: On feature/git: my saved work
                """;
        List<StashEntry> list = StashParser.parse(out);
        assertEquals(2, list.size());

        StashEntry a = list.get(0);
        assertEquals(0, a.index());
        assertEquals("stash@{0}", a.ref());
        assertEquals("master", a.branch());
        assertEquals("1a2b3c4 Fix the gutter", a.subject());

        StashEntry b = list.get(1);
        assertEquals(1, b.index());
        assertEquals("feature/git", b.branch());
        assertEquals("my saved work", b.subject());
    }

    @Test
    void fallbackForUnexpectedShape() {
        List<StashEntry> list = StashParser.parse("stash@{0}: something weird without the on-branch part");
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).index());
        assertEquals("stash@{0}", list.get(0).ref());
        assertEquals("", list.get(0).branch());
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertTrue(StashParser.parse("").isEmpty());
        assertTrue(StashParser.parse(null).isEmpty());
    }
}
