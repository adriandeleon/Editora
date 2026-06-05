package com.editora.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MultiFileSearchTest {

    private static SearchQuery q(String t) {
        return new SearchQuery(t, false, false, false);
    }

    @Test
    void matchesReportOneBasedLineAndColumnWithLineText() {
        String text = "alpha\nbeta foo\nfoo and foo\n";
        List<LineMatch> ms = MultiFileSearch.matchesInText(text, q("foo"));
        assertEquals(3, ms.size());
        assertEquals(2, ms.get(0).line());
        assertEquals(6, ms.get(0).col()); // "beta foo" → foo at col 6 (1-based)
        assertEquals("beta foo", ms.get(0).lineText());
        assertEquals(3, ms.get(1).line());
        assertEquals(1, ms.get(1).col());
        assertEquals(3, ms.get(2).line());
        assertEquals(9, ms.get(2).col());
    }

    @Test
    void crlfLinesStripTrailingCarriageReturnInPreview() {
        List<LineMatch> ms = MultiFileSearch.matchesInText("foo\r\nbar\r\n", q("foo"));
        assertEquals(1, ms.size());
        assertEquals("foo", ms.get(0).lineText()); // no trailing \r
        assertEquals(1, ms.get(0).line());
    }

    @Test
    void emptyQueryOrTextYieldsNothing() {
        assertEquals(0, MultiFileSearch.matchesInText("abc", q("")).size());
        assertEquals(0, MultiFileSearch.matchesInText("", q("a")).size());
    }

    @Test
    void replaceAllSplicesEveryMatchAndCounts() {
        MultiFileSearch.ReplaceResult r =
                MultiFileSearch.replaceAll("foo bar foo", q("foo"), "X");
        assertEquals("X bar X", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void replaceAllAcrossLinesPreservesNewlines() {
        MultiFileSearch.ReplaceResult r =
                MultiFileSearch.replaceAll("a foo\nfoo b\n", q("foo"), "Z");
        assertEquals("a Z\nZ b\n", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void replaceAllNoMatchReturnsOriginal() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("abc", q("zzz"), "X");
        assertEquals("abc", r.text());
        assertEquals(0, r.count());
    }

    @Test
    void regexAndWholeWordHonored() {
        List<LineMatch> ms = MultiFileSearch.matchesInText("cat cats category cat",
                new SearchQuery("cat", true, false, true));
        assertEquals(2, ms.size()); // only the standalone "cat"s
    }
}
