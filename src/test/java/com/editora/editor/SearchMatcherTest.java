package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchMatcherTest {

    private static int[] m(int s, int e) {
        return new int[] {s, e};
    }

    private static void assertRanges(List<int[]> actual, int[]... expected) {
        assertEquals(expected.length, actual.size(), "match count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], actual.get(i)[0], "start " + i);
            assertEquals(expected[i][1], actual.get(i)[1], "end " + i);
        }
    }

    @Test
    void literalFindsAllNonOverlapping() {
        assertRanges(SearchMatcher.matches("a.b.a.b", "a.b", true, false, false), m(0, 3), m(4, 7));
    }

    @Test
    void literalCaseInsensitiveByDefault() {
        assertRanges(SearchMatcher.matches("Foo FOO foo", "foo", false, false, false), m(0, 3), m(4, 7), m(8, 11));
        // case-sensitive only matches the exact case
        assertRanges(SearchMatcher.matches("Foo FOO foo", "foo", true, false, false), m(8, 11));
    }

    @Test
    void wholeWordLiteralRejectsSubstrings() {
        // "cat" appears in "category" and "cat" — whole-word keeps only the standalone one.
        assertRanges(SearchMatcher.matches("cat category cat", "cat", true, false, true), m(0, 3), m(13, 16));
    }

    @Test
    void regexMatchesAndCaseFlag() {
        assertRanges(SearchMatcher.matches("a1 b2 c3", "[a-z][0-9]", true, true, false), m(0, 2), m(3, 5), m(6, 8));
        assertRanges(SearchMatcher.matches("AB ab", "[a-z]+", true, true, false), m(3, 5));
    }

    @Test
    void caseInsensitiveRegexFoldsNonAscii() {
        // Without UNICODE_CASE, CASE_INSENSITIVE only folds ASCII, so "café" wouldn't match "CAFÉ" in regex
        // mode — while the literal path does. Both must agree.
        assertRanges(SearchMatcher.matches("CAFÉ", "café", false, true, false), m(0, 4));
        assertRanges(SearchMatcher.matches("CAFÉ", "café", false, false, false), m(0, 4)); // literal, for parity
    }

    @Test
    void wholeWordLiteralUsesRealWordBoundaries() {
        // A query with a non-word edge char: there IS a \b between "a" and "+", so "+foo" matches "a+foo" —
        // matching the regex path's \b(?:…)\b and ripgrep -w (the old test required a non-word char outside).
        assertRanges(SearchMatcher.matches("a+foo", "+foo", true, false, true), m(1, 5));
        // Both edges non-word ⇒ no boundary after ")", so it correctly does NOT match (like regex \b(?:…)\b).
        assertRanges(SearchMatcher.matches("call(foo)", "(foo)", true, false, true));
        // A word-edge query is unchanged: "cat" is still not a whole word inside "xcat".
        assertRanges(SearchMatcher.matches("xcat cat", "cat", true, false, true), m(5, 8));
    }

    @Test
    void regexWholeWordWraps() {
        // \b(?:in)\b must not match "inside"
        assertRanges(SearchMatcher.matches("in inside in", "in", true, true, true), m(0, 2), m(10, 12));
    }

    @Test
    void zeroWidthRegexDoesNotLoopForever() {
        // a pattern that can match empty must still terminate and advance
        List<int[]> r = SearchMatcher.matches("abc", "x*", true, true, false);
        assertTrue(r.size() <= 4, "terminates: " + r.size());
    }

    @Test
    void emptyOrNullQueryYieldsNothing() {
        assertTrue(SearchMatcher.matches("abc", "", false, false, false).isEmpty());
        assertTrue(SearchMatcher.matches("abc", null, false, false, false).isEmpty());
        assertTrue(SearchMatcher.matches(null, "a", false, false, false).isEmpty());
    }

    @Test
    void badRegexReturnsEmptyAndIsReported() {
        assertTrue(SearchMatcher.matches("abc", "(", false, true, false).isEmpty());
        assertNotNull(SearchMatcher.regexError("("));
        assertNull(SearchMatcher.regexError("[a-z]+"));
    }

    @Test
    void nextIndexWrapsBothWays() {
        List<int[]> ms = List.of(m(2, 4), m(10, 12), m(20, 22));
        assertEquals(1, SearchMatcher.nextIndex(ms, 5, true)); // first start >= 5
        assertEquals(0, SearchMatcher.nextIndex(ms, 30, true)); // wrap forward
        assertEquals(0, SearchMatcher.nextIndex(ms, 10, false)); // last start < 10
        assertEquals(2, SearchMatcher.nextIndex(ms, 0, false)); // wrap backward
        assertEquals(-1, SearchMatcher.nextIndex(List.of(), 0, true));
    }

    @Test
    void indexAtFindsContainingMatch() {
        List<int[]> ms = List.of(m(2, 4), m(10, 12));
        assertEquals(0, SearchMatcher.indexAt(ms, 3));
        assertEquals(1, SearchMatcher.indexAt(ms, 10));
        assertEquals(-1, SearchMatcher.indexAt(ms, 6));
    }

    // A pattern + input that catastrophically backtracks on this JDK: `(.*a){30}` over 30 'a's runs ~9 s
    // unbounded (`{32}` ~38 s), which is a total UI freeze since the find bar matches on the FX thread.
    private static final String EVIL_RE = "(.*a){30}";
    private static final String EVIL_IN = "a".repeat(30);

    @Test
    void aPathologicalRegexIsAbandonedRatherThanHangingForever() {
        // java.util.regex has no timeout and the pattern is VALID, so regexError() doesn't catch it — it used
        // to spin ~9 s (much longer as the input grows) on the FX thread. The default budget must bound it.
        long start = System.nanoTime();
        List<int[]> out = SearchMatcher.matches(EVIL_IN, EVIL_RE, true, true, false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 4_000, "must abandon the runaway match near the ~1s budget, took " + elapsedMs + "ms");
        assertNotNull(out); // returned at all — that's the point
    }

    @Test
    void aTinyBudgetBoundsItAlmostImmediately() {
        // A 1 ms budget aborts the same catastrophic match right away — proves the deadline actually fires,
        // not that the input happened to be tractable.
        long start = System.nanoTime();
        List<int[]> out = SearchMatcher.regexMatches(EVIL_IN, EVIL_RE, true, false, 1_000_000L); // 1 ms
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "bounded by the tiny budget");
        assertNotNull(out);
    }

    @Test
    void ordinaryRegexSearchStillWorks() {
        // The bounding must not change normal results.
        List<int[]> out = SearchMatcher.matches("foo bar foo", "foo", true, true, false);
        assertEquals(2, out.size());
        assertEquals(0, out.get(0)[0]);
        assertEquals(8, out.get(1)[0]);
    }
}
