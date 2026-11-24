package com.editora.logviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogMatcherTest {

    /** Polynomial backtracking of high degree: JDK 25 memoizes {@code (a+)+$}, but not this. */
    static final String EVIL = "(.*a){20}$";

    static final String EVIL_LINE = "a".repeat(40) + "!";

    @Test
    void onlyAPatternThatCanBacktrackBadlyIsTimed() {
        assertTrue(LogMatcher.canBacktrack(Pattern.compile("(a+)+$")));
        assertTrue(LogMatcher.canBacktrack(Pattern.compile(EVIL)));
        assertTrue(LogMatcher.canBacktrack(Pattern.compile("(a*)\\1")), "a back-reference");
        assertTrue(LogMatcher.canBacktrack(Pattern.compile(".*a.*b.*c")), "three quantifiers");
        assertFalse(LogMatcher.canBacktrack(Pattern.compile("time.*out")));
        assertFalse(LogMatcher.canBacktrack(Pattern.compile("timed?out")));
        assertFalse(LogMatcher.canBacktrack(Pattern.compile("ERROR|WARN")));
        assertFalse(LogMatcher.canBacktrack(Pattern.compile("\\(x\\)+")), "an escaped bracket is no group");
        assertFalse(LogMatcher.canBacktrack(Pattern.compile("a*b+c*", Pattern.LITERAL)));
    }

    @Test
    void aLineThatTakesTooLongDoesNotMatchAndThreeOfThemEndTheTrying() {
        LogMatcher m = new LogMatcher(Pattern.compile(EVIL), 5_000_000L);
        long start = System.nanoTime();
        assertFalse(m.find(EVIL_LINE));
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "stopped near its limit");
        assertEquals(1, m.slowLines());
        assertFalse(m.gaveUp());
        m.find(EVIL_LINE);
        m.find(EVIL_LINE);
        assertTrue(m.gaveUp());
        assertFalse(m.find("aaaa"), "once given up, nothing matches");
    }

    @Test
    void aGoodPatternMatchesAsUsual() {
        LogMatcher m = new LogMatcher(Pattern.compile("timed? ?out"));
        assertTrue(m.find("Read timed out"));
        assertFalse(m.find("all fine"));
        assertEquals(0, m.slowLines());
    }

    @Test
    void rangesReportsNonEmptyMatchesUpToTheCap() {
        LogMatcher m = new LogMatcher(Pattern.compile("o+|x*"));
        List<int[]> got = new ArrayList<>();
        assertEquals(2, m.ranges("foo boo zoo", 2, (s, e) -> got.add(new int[] {s, e})));
        assertEquals(1, got.get(0)[0]);
        assertEquals(3, got.get(0)[1]);
        assertEquals(5, got.get(1)[0]);
    }
}
