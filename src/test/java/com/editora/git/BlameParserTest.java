package com.editora.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.git.BlameParser.BlameLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@code git blame --line-porcelain} parser. */
class BlameParserTest {

    @Test
    void parsesCommittedAndUncommittedLines() {
        String out = """
                1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b 1 1 2
                author Adrian De Leon
                author-mail <adrian@example.com>
                author-time 1700000000
                author-tz +0000
                committer Adrian De Leon
                committer-time 1700000000
                summary Add the thing
                filename Foo.java
                \tpackage com.editora;
                1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b 2 2
                \timport java.util.List;
                0000000000000000000000000000000000000000 3 3 1
                author Not Committed Yet
                author-time 1800000000
                summary Uncommitted changes
                filename Foo.java
                \tString work = null;
                """;
        List<BlameLine> lines = BlameParser.parse(out);
        assertEquals(3, lines.size());

        BlameLine first = lines.get(0);
        assertEquals("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b", first.hash());
        assertEquals("Adrian De Leon", first.author());
        assertEquals(1700000000L, first.epochSeconds());
        assertEquals("Add the thing", first.summary());
        assertFalse(first.uncommitted());

        // The second line repeats the same commit (a grouped block carries the header but no fields).
        assertEquals("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b", lines.get(1).hash());

        BlameLine third = lines.get(2);
        assertTrue(third.uncommitted());
        assertEquals("Not Committed Yet", third.author());
    }

    @Test
    void emptyOrNullInputYieldsEmptyList() {
        assertTrue(BlameParser.parse("").isEmpty());
        assertTrue(BlameParser.parse(null).isEmpty());
    }
}
