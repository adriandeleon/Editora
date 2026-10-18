package com.editora.terminal;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSearchTest {

    private static final Map<Integer, String> ROWS = Map.of(
            -2, "Error: first", //
            -1, "no hit here", //
            0, "error error", //
            1, "", //
            2, "ERROR last");

    @Test
    void everyHitOldestFirstIgnoringCaseByDefault() {
        List<TerminalSearch.Match> found = TerminalSearch.find(ROWS::get, -2, 2, "error", false);
        assertEquals(
                List.of(
                        new TerminalSearch.Match(-2, 0, 5),
                        new TerminalSearch.Match(0, 0, 5),
                        new TerminalSearch.Match(0, 6, 11),
                        new TerminalSearch.Match(2, 0, 5)),
                found);
    }

    @Test
    void matchCaseFindsOnlyTheExactSpelling() {
        assertEquals(List.of(new TerminalSearch.Match(2, 0, 5)), TerminalSearch.find(ROWS::get, -2, 2, "ERROR", true));
    }

    @Test
    void hitsDoNotOverlapAndAnEmptyQueryFindsNothing() {
        assertEquals(2, TerminalSearch.find(r -> "aaaa", 0, 0, "aa", false).size());
        assertTrue(TerminalSearch.find(ROWS::get, -2, 2, "", false).isEmpty());
        assertTrue(TerminalSearch.find(r -> null, 0, 3, "x", false).isEmpty(), "rows that are not there");
    }

    @Test
    void theCountStopsAtTheCap() {
        assertEquals(
                TerminalSearch.MAX_MATCHES,
                TerminalSearch.find(r -> "x".repeat(100), 0, 99, "x", false).size());
    }

    @Test
    void aSearchStartsAtTheLatestHitAtOrAboveTheBottomOfTheView() {
        List<TerminalSearch.Match> found = TerminalSearch.find(ROWS::get, -2, 2, "error", false);
        assertEquals(3, TerminalSearch.startIndex(found, 2), "the bottom row's own hit");
        assertEquals(2, TerminalSearch.startIndex(found, 1), "the last one on row 0");
        assertEquals(0, TerminalSearch.startIndex(found, -1));
        assertEquals(3, TerminalSearch.startIndex(found, -5), "none above: wraps to the latest");
        assertEquals(-1, TerminalSearch.startIndex(List.of(), 0));
    }

    @Test
    void pastTheCapTheNewestHitsAreKept() {
        // 1,500 rows of "hit": the newest 1,000 are what a user searching a long log is looking for.
        List<TerminalSearch.Match> found = TerminalSearch.find(r -> "hit " + r, -1499, 0, "hit", false);
        assertEquals(TerminalSearch.MAX_MATCHES, found.size());
        assertEquals(-999, found.getFirst().row(), "oldest kept");
        assertEquals(0, found.getLast().row(), "the bottom row's hit is found, oldest first order kept");
    }

    @Test
    void wideCharactersAreFoundAndTheHitCoversTheirCells() {
        char dwc = com.jediterm.terminal.util.CharUtils.DWC;
        String row = "x 日" + dwc + "本" + dwc + "語" + dwc + " y";
        assertEquals(List.of(new TerminalSearch.Match(0, 2, 6)), TerminalSearch.find(r -> row, 0, 0, "日本", true));
        assertEquals(List.of(new TerminalSearch.Match(0, 9, 10)), TerminalSearch.find(r -> row, 0, 0, "y", false));
    }

    @Test
    void ignoringCaseMatchesMixedCaseRows() {
        assertEquals(1, TerminalSearch.find(r -> "Ünïcode TEXT", 0, 0, "üNÏCODE text", false).size());
    }
}
