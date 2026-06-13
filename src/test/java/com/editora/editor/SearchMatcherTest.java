package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
