package com.editora.logviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogNavigationTest {

    private static final String LOG = String.join(
            "\n",
            "INFO  starting up", // 0
            "DEBUG  connecting", // 1
            "WARN  slow response", // 2
            "INFO  ok", // 3
            "ERROR  boom", // 4
            "INFO  done"); // 5

    @Test
    void findsNextWarnOrAbove() {
        assertEquals(2, LogNavigation.nextLevelLine(LOG, 0, true, LogLevel.WARN));
        assertEquals(4, LogNavigation.nextLevelLine(LOG, 2, true, LogLevel.WARN));
    }

    @Test
    void wrapsAroundToTheStart() {
        assertEquals(2, LogNavigation.nextLevelLine(LOG, 4, true, LogLevel.WARN)); // past the last error → wrap
    }

    @Test
    void findsPreviousWarnOrAbove() {
        assertEquals(2, LogNavigation.nextLevelLine(LOG, 4, false, LogLevel.WARN));
        assertEquals(4, LogNavigation.nextLevelLine(LOG, 0, false, LogLevel.WARN)); // wrap backward
    }

    @Test
    void minLevelFiltersOutWarnWhenErrorRequested() {
        assertEquals(4, LogNavigation.nextLevelLine(LOG, 0, true, LogLevel.ERROR)); // skips the WARN at line 2
    }

    @Test
    void noQualifyingLineReturnsMinusOne() {
        assertEquals(-1, LogNavigation.nextLevelLine("INFO a\nINFO b", 0, true, LogLevel.WARN));
        assertEquals(-1, LogNavigation.nextLevelLine("", 0, true, LogLevel.WARN));
    }

    @Test
    void onlyErrorLineIsTheCaretLineDoesNotSelfMatch() {
        assertEquals(-1, LogNavigation.nextLevelLine("INFO a\nERROR here\nINFO b", 1, true, LogLevel.ERROR));
    }
}
