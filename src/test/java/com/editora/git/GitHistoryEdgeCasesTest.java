package com.editora.git;

import java.util.List;

import com.editora.git.RelativeTime.Span;
import com.editora.git.RelativeTime.Unit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edge cases of the history helpers: links at a line's start, ages at a bucket's edge, odd diff headers, blame zones. */
class GitHistoryEdgeCasesTest {

    private static final long DAY = 86_400;

    @Test
    void aLinkLookupAtTheVeryStartOfTextThatOpensWithANewlineFindsNothingAndDoesNotThrow() {
        assertNull(GitOutputLinks.at("\n+++ b/src/Main.java", 0));
        assertNull(GitOutputLinks.at("\n", 0));
        assertNull(GitOutputLinks.at("\n\n", 1));
        GitOutputLinks.Link link = GitOutputLinks.at("\n+++ b/src/Main.java", 8);
        assertEquals("src/Main.java", link == null ? null : link.file());
        GitOutputLinks.Link first = GitOutputLinks.at("+++ b/src/Main.java\n", 0);
        assertNull(first, "offset 0 is the '+', not part of the file name");
        GitOutputLinks.Link start = GitOutputLinks.at("+++ b/src/Main.java", 6);
        assertEquals("src/Main.java", start == null ? null : start.file());
    }

    @Test
    void agesAtTheEdgeOfABucketReadAsTheNextUnit() {
        assertEquals(new Span(Unit.WEEKS, 3), RelativeTime.of(0, 27 * DAY));
        assertEquals(new Span(Unit.MONTHS, 1), RelativeTime.of(0, 28 * DAY), "not 4 weeks");
        assertEquals(new Span(Unit.MONTHS, 1), RelativeTime.of(0, 29 * DAY));
        assertEquals(new Span(Unit.MONTHS, 11), RelativeTime.of(0, 359 * DAY));
        assertEquals(new Span(Unit.YEARS, 1), RelativeTime.of(0, 360 * DAY), "not 12 months");
        assertEquals(new Span(Unit.YEARS, 1), RelativeTime.of(0, 364 * DAY));
        assertEquals(new Span(Unit.YEARS, 2), RelativeTime.of(0, 730 * DAY));
    }

    @Test
    void aTimeAheadOfTheClockIsNowOnlyWithinALittleSkew() {
        assertFalse(RelativeTime.isFuture(1_000 + 60, 1_000), "a minute of skew is now");
        assertEquals(new Span(Unit.NOW, 0), RelativeTime.of(1_000 + 60, 1_000));
        assertFalse(RelativeTime.isFuture(1_000 + RelativeTime.SKEW_SECONDS, 1_000));
        assertTrue(RelativeTime.isFuture(1_000 + RelativeTime.SKEW_SECONDS + 1, 1_000));
        assertTrue(RelativeTime.isFuture(1_000 + 30 * DAY, 1_000));
        assertFalse(RelativeTime.isFuture(0, 1_000));
    }

    @Test
    void diffHeadersAreReadWithAnyPrefixOrNone() {
        assertEquals("src/f.txt", GitChanges.headerPath("a/src/f.txt b/src/f.txt"));
        assertEquals("src/f.txt", GitChanges.headerPath("src/f.txt src/f.txt"), "diff.noprefix");
        assertEquals("src/f.txt", GitChanges.headerPath("i/src/f.txt w/src/f.txt"), "diff.mnemonicPrefix");
        assertEquals("src/f.txt", GitChanges.headerPath("c/src/f.txt w/src/f.txt"));
        assertEquals("a b", GitChanges.headerPath("a b a b"), "no prefix, a space in the name");
        assertEquals("x y.txt", GitChanges.headerPath("w/x y.txt i/x y.txt"));
        assertEquals("tab\there", GitChanges.headerPath("\"a/tab\\there\" \"b/tab\\there\""));
        assertEquals("tab\there", GitChanges.headerPath("\"i/tab\\there\" \"w/tab\\there\""));
        assertEquals("tab\there", GitChanges.headerPath("\"tab\\there\" \"tab\\there\""), "quoted, no prefix");
        assertEquals("café", GitChanges.headerPath("\"a/caf\\303\\251\" \"b/caf\\303\\251\""));
        assertEquals("x", GitChanges.headerPath("a/x b/y"), "different names: up to b/, as before");
    }

    @Test
    void aBlameDateIsTheDayTheAuthorSaw() {
        // 2026-01-01T23:30:00Z: still Jan 1 in UTC, already Jan 2 in UTC+2, still Jan 1 in UTC-5.
        long t = java.time.Instant.parse("2026-01-01T23:30:00Z").getEpochSecond();
        assertEquals("2026-01-01", BlameParser.authorDate(t, "+0000"));
        assertEquals("2026-01-02", BlameParser.authorDate(t, "+0200"));
        assertEquals("2026-01-01", BlameParser.authorDate(t, "-0500"));
        assertEquals(
                java.time.Instant.ofEpochSecond(t)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString(),
                BlameParser.authorDate(t, null),
                "no zone: the system's");
        assertEquals(BlameParser.authorDate(t, null), BlameParser.authorDate(t, "bogus"));

        List<BlameParser.BlameLine> lines = BlameParser.parse("""
                1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b 1 1 1
                author Ada
                author-time %d
                author-tz +0200
                summary s
                filename f.txt
                \tline
                """.formatted(t));
        assertEquals("+0200", lines.get(0).authorTz());
    }
}
