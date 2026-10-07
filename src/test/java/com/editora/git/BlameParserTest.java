package com.editora.git;

import java.util.List;

import com.editora.git.BlameParser.BlameLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void sha256RepositoriesHaveSixtyFourDigitIds() {
        // E7: the header was matched as exactly 40 hex digits, so a SHA-256 repository blamed to nothing.
        String id = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        String parent = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
        String out = id + " 1 1 1\n"
                + "author Ada\n"
                + "author-time 1700000000\n"
                + "summary Rename it\n"
                + "previous " + parent + " old name.txt\n"
                + "filename new name.txt\n"
                + "\tcontent\n"
                + "0".repeat(64) + " 2 2 1\n"
                + "author Not Committed Yet\n"
                + "filename new name.txt\n"
                + "\tedited\n";
        List<BlameLine> lines = BlameParser.parse(out);
        assertEquals(2, lines.size());
        assertEquals(id, lines.get(0).hash());
        assertEquals("Ada", lines.get(0).author());
        assertEquals("new name.txt", lines.get(0).path());
        assertEquals("old name.txt", lines.get(0).previousPath());
        assertFalse(lines.get(0).uncommitted());
        assertTrue(lines.get(1).uncommitted());
    }

    @Test
    void plainPorcelainDescribesACommitOnlyOnce() {
        // E1: --porcelain (far smaller than --line-porcelain) gives the fields in a commit's first block only;
        // a later, non-adjacent block of the same commit is just a header and the content line.
        String a = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";
        String b = "2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c";
        String out = a + " 1 1 1\n"
                + "author Ada\n"
                + "author-time 1700000000\n"
                + "summary First\n"
                + "filename Foo.java\n"
                + "\tone\n"
                + b + " 2 2 1\n"
                + "author Grace\n"
                + "author-time 1800000000\n"
                + "summary Second\n"
                + "previous " + a + " Old.java\n"
                + "filename Foo.java\n"
                + "\ttwo\n"
                + a + " 3 3 1\n"
                + "\tthree\n";
        List<BlameLine> lines = BlameParser.parse(out);
        assertEquals(3, lines.size());
        assertEquals("Grace", lines.get(1).author());
        assertEquals("Old.java", lines.get(1).previousPath());
        BlameLine third = lines.get(2);
        assertEquals(a, third.hash());
        assertEquals("Ada", third.author());
        assertEquals(1700000000L, third.epochSeconds());
        assertEquals("First", third.summary());
        assertEquals("Foo.java", third.path());
        assertEquals(null, third.previousPath());
    }
}
