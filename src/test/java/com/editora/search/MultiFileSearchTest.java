package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void matchProductionStopsAtTheRequestedLimit() {
        List<LineMatch> matches = MultiFileSearch.matchesInText("hit\n".repeat(10_000), q("hit"), 37);
        assertEquals(37, matches.size());
    }

    @Test
    void denseSingleLineStopsAllocationAtTheRequestedLimitAndHonorsCancellation() {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        String text = "x".repeat(500_000);
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());

        assertEquals(1, MultiFileSearch.matchesInText(text, q("x"), 1).size());
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertTrue(allocated < 2_000_000, "the matcher must not materialize the other 499,999 matches");

        Thread.currentThread().interrupt();
        try {
            assertTrue(MultiFileSearch.matchesInText(text, q("x"), 1).isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void replaceAllSplicesEveryMatchAndCounts() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("foo bar foo", q("foo"), "X");
        assertEquals("X bar X", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void replaceAllAcrossLinesPreservesNewlines() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("a foo\nfoo b\n", q("foo"), "Z");
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
    void regexReplaceIsPerLineLikeThePreview() {
        // ";$" matches every line's trailing ; in the preview (per-line); replace must do the same, not just
        // the single end-of-file match a whole-text regex replace would do.
        SearchQuery q = new SearchQuery(";$", false, true, false);
        assertEquals(3, MultiFileSearch.matchesInText("a;\nb;\nc;", q).size(), "preview matches every line");
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("a;\nb;\nc;", q, "X");
        assertEquals("aX\nbX\ncX", r.text());
        assertEquals(3, r.count());
    }

    @Test
    void regexReplaceNeverSpansALineBreak() {
        // A cross-line regex matches nothing in the per-line preview; replace must not rewrite across "\n".
        SearchQuery q = new SearchQuery("foo\nbar", false, true, false);
        String text = "x foo\nbar y";
        assertEquals(0, MultiFileSearch.matchesInText(text, q).size());
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll(text, q, "Z");
        assertEquals(text, r.text(), "no cross-line rewrite");
        assertEquals(0, r.count());
    }

    @Test
    void regexReplacePreservesCrlf() {
        MultiFileSearch.ReplaceResult r =
                MultiFileSearch.replaceAll("a;\r\nb;\r\n", new SearchQuery(";$", false, true, false), "X");
        assertEquals("aX\r\nbX\r\n", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void regexAndWholeWordHonored() {
        List<LineMatch> ms =
                MultiFileSearch.matchesInText("cat cats category cat", new SearchQuery("cat", true, false, true));
        assertEquals(2, ms.size()); // only the standalone "cat"s
    }

    private static SearchQuery rx(String t) {
        return new SearchQuery(t, false, true, false); // regex, case-insensitive, not whole-word
    }

    @Test
    void regexReplaceSupportsCaptureGroups() {
        MultiFileSearch.ReplaceResult r =
                MultiFileSearch.replaceAll("FooService BarService", rx("(\\w+)Service"), "$1Client");
        assertEquals("FooClient BarClient", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void regexReplaceWholeMatchGroupZero() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("a1 b2", rx("\\w\\d"), "[$0]");
        assertEquals("[a1] [b2]", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void literalReplaceLeavesDollarUntouched() {
        // Non-regex: a "$1" in the replacement is inserted verbatim (no group interpretation).
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("foo foo", q("foo"), "$1");
        assertEquals("$1 $1", r.text());
        assertEquals(2, r.count());
    }

    @Test
    void regexBadGroupReferenceIsAGracefulNoOp() {
        // $2 referenced but the pattern has only one group → leave the text unchanged, count 0.
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("foo", rx("(foo)"), "$2");
        assertEquals("foo", r.text());
        assertEquals(0, r.count());
    }

    /**
     * Full case folding reaches multi-file search + replace too (#444). The replace path splices at the
     * matcher's offsets, so a length-changing fold is where a bad offset would corrupt the file rather than
     * merely miss a hit — {@code ß} is one char but folds to two.
     */
    @Test
    void fullCaseFoldMatchesAcrossLengthChangingFolds() {
        List<LineMatch> ms = MultiFileSearch.matchesInText("die Straße\ndie Strasse\n", q("STRASSE"));
        assertEquals(2, ms.size());
        assertEquals(1, ms.get(0).line());
        assertEquals(5, ms.get(0).col());
        assertEquals(6, ms.get(0).length(), "the match spans the ORIGINAL 6-char word, not its 7-char fold");
        assertEquals(7, ms.get(1).length());
    }

    @Test
    void replaceAcrossALengthChangingFoldSplicesTheOriginalSpan() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("die Straße hier", q("STRASSE"), "Weg");
        assertEquals("die Weg hier", r.text());
        assertEquals(1, r.count());
    }

    @Test
    void replaceIsUnaffectedForAsciiText() {
        MultiFileSearch.ReplaceResult r = MultiFileSearch.replaceAll("foo Foo FOO", q("foo"), "bar");
        assertEquals("bar bar bar", r.text());
        assertEquals(3, r.count());
    }

    // --- B4(d): the Find-in-Files regex dialect matches ripgrep's -------------------------------------

    private static final SearchQuery WORD = new SearchQuery("\\w+", true, true, false);

    @Test
    void unicodeClassesMakeWordCharsMatchNonAsciiLettersLikeRipgrep() {
        List<LineMatch> ripgrepLike =
                MultiFileSearch.matchesInText("café", WORD, Integer.MAX_VALUE, MultiFileSearch.UNICODE_CLASSES);
        assertEquals(1, ripgrepLike.size(), "\\w+ covers the whole word, as Rust regex does");
        assertEquals(4, ripgrepLike.get(0).length());

        // Without the flag java.util.regex keeps \w ASCII-only — the find bar's behaviour, left alone.
        List<LineMatch> findBar = MultiFileSearch.matchesInText("café", WORD, Integer.MAX_VALUE);
        assertEquals(3, findBar.get(0).length(), "ASCII \\w stops at the accent");
    }

    @Test
    void unicodeDigitsAndWordBoundariesFollowTheSameDialect() {
        SearchQuery digits = new SearchQuery("\\d+", true, true, false);
        assertEquals(
                1,
                MultiFileSearch.matchesInText("n=٣٤", digits, 10, MultiFileSearch.UNICODE_CLASSES)
                        .size(),
                "Arabic-Indic digits are \\d in Rust regex");
        assertTrue(MultiFileSearch.matchesInText("n=٣٤", digits, 10).isEmpty());

        // Whole word: "é" is a word char, so there is no boundary inside "résumé" for "sum".
        SearchQuery sum = new SearchQuery("sum", true, true, true);
        assertTrue(MultiFileSearch.matchesInText("résumé", sum, 10, MultiFileSearch.UNICODE_CLASSES)
                .isEmpty());
        assertEquals(
                1,
                MultiFileSearch.matchesInText("résumé", sum, 10).size(),
                "the ASCII \\b sees a boundary on both sides of an accented letter");
    }

    @Test
    void replaceUsesTheSameDialectAsThePreviewSoItRewritesWhatWasShown() {
        var replaced = MultiFileSearch.replaceAll("café au lait", WORD, "<$0>", MultiFileSearch.UNICODE_CLASSES);
        assertEquals("<café> <au> <lait>", replaced.text());
        assertEquals(3, replaced.count());
        assertEquals(
                "<caf>é <au> <lait>",
                MultiFileSearch.replaceAll("café au lait", WORD, "<$0>").text());
    }

    @Test
    void theUnicodeDialectKeepsLimitsBadPatternsAndLiteralSearchIntact() {
        assertEquals(
                2,
                MultiFileSearch.matchesInText("a b c d", WORD, 2, MultiFileSearch.UNICODE_CLASSES)
                        .size());
        SearchQuery bad = new SearchQuery("(", true, true, false);
        assertTrue(MultiFileSearch.matchesInText("(x)", bad, 10, MultiFileSearch.UNICODE_CLASSES)
                .isEmpty());
        assertEquals(
                "(x)",
                MultiFileSearch.replaceAll("(x)", bad, "y", MultiFileSearch.UNICODE_CLASSES)
                        .text());
        SearchQuery literal = new SearchQuery("\\w+", true, false, false);
        assertEquals(
                1,
                MultiFileSearch.matchesInText("a \\w+ b", literal, 10, MultiFileSearch.UNICODE_CLASSES)
                        .size(),
                "a literal query is not a regex in either dialect");
        // A zero-width match must advance, not loop.
        SearchQuery empty = new SearchQuery("x*", true, true, false);
        assertEquals(
                3,
                MultiFileSearch.matchesInText("ab", empty, 10, MultiFileSearch.UNICODE_CLASSES)
                        .size());
    }
}
