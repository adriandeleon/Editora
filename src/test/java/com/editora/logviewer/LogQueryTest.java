package com.editora.logviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogQueryTest {

    @Test
    void emptyPatternsAreNoPatternsAndAnEmptyQueryIsNone() {
        LogQuery q = new LogQuery("", "", true);
        assertNull(q.include());
        assertNull(q.exclude());
        assertTrue(q.isEmpty());
        assertSame(LogQuery.NONE, q.normalized(), "Match Case alone filters nothing");
        assertEquals(new LogQuery(" x", null, false), LogQuery.including(" x"), "spaces are kept");
        assertFalse(new LogQuery(null, "noise", false).isEmpty());
    }

    @Test
    void compileHonoursMatchCaseAndFallsBackToPlainText() {
        LogFilter.Patterns ignoring = LogFilter.compile(new LogQuery("error", "GET /a(", false));
        assertTrue(ignoring.include().matcher("ERROR").find());
        assertTrue(ignoring.exclude().matcher("get /A(b").find(), "an invalid regex is plain text");
        LogFilter.Patterns matching = LogFilter.compile(new LogQuery("error", null, true));
        assertFalse(matching.include().matcher("ERROR").find());
        assertNull(matching.exclude());
        assertSame(LogFilter.Patterns.NONE, LogFilter.compile(LogQuery.NONE));
    }

    @Test
    void aTimeRangeAloneIsAFilterAndSurvivesAPatternChange() {
        LogQuery ranged = LogQuery.NONE.withTimeRange(1000L, null);
        org.junit.jupiter.api.Assertions.assertFalse(ranged.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(ranged, ranged.normalized());
        LogQuery typed = ranged.withInclude("timeout");
        org.junit.jupiter.api.Assertions.assertEquals(1000L, typed.from());
        org.junit.jupiter.api.Assertions.assertTrue(
                ranged.withTimeRange(null, null).isEmpty());
    }
}
