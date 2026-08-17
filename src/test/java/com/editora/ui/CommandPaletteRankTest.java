package com.editora.ui;

import java.util.Comparator;
import java.util.List;

import com.editora.search.FuzzyMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command palette's result ordering (no toolkit needed).
 *
 * <p>The palette no longer owns a ranking algorithm — it scores with {@link FuzzyMatch} and breaks ties by
 * the shorter then alphabetical title. This reproduces exactly that comparator over a list of titles, so
 * the ordering the user actually sees stays pinned even though the scoring moved out of the class.
 */
class CommandPaletteRankTest {

    /** The palette's ordering: score descending, then shorter title, then alphabetical. */
    private static List<String> ranked(String query, String... titles) {
        record Scored(String title, int score) {}
        return java.util.Arrays.stream(titles)
                .map(t -> {
                    FuzzyMatch.Match m = FuzzyMatch.of(t, query);
                    return m == null ? null : new Scored(t, m.score());
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Scored::score)
                        .reversed()
                        .thenComparingInt((Scored s) -> s.title().length())
                        .thenComparing(Scored::title, String.CASE_INSENSITIVE_ORDER))
                .map(Scored::title)
                .toList();
    }

    @Test
    void undoRanksEditUndoFirst() {
        // The originally reported case, kept as a regression: typing "undo" must surface the Undo command,
        // not a tool window that merely begins with the word, and certainly not a scattered match. The two
        // score equally (position in the title is not a signal — see FuzzyMatchTest); the shorter-title
        // tiebreak is what settles it.
        List<String> ranked = ranked("undo", "View: Unsplit Editor", "Undo History", "Toggle Run Window", "Edit: Undo");
        assertEquals("Edit: Undo", ranked.get(0));
        assertEquals("Undo History", ranked.get(1));
        assertTrue(ranked.indexOf("View: Unsplit Editor") > 1, "scattered matches sink to the bottom");
    }

    @Test
    void aContiguousWordBeatsAScatteredSubsequence() {
        assertEquals(
                "Git: Commit",
                ranked("git", "Toggle Line Numbers", "Git: Commit").get(0));
    }

    @Test
    void nonMatchesAreDroppedEntirely() {
        assertEquals(List.of(), ranked("zzz", "Edit: Undo", "Undo History"));
    }

    @Test
    void multiTermQueriesFindOutOfOrderTitles() {
        // Previously impossible: one ordered subsequence cannot match "toggle" ahead of "git" here.
        assertEquals(
                List.of("Git: Toggle Blame"), ranked("toggle git", "Git: Toggle Blame", "Git: Commit", "Toggle Fold"));
    }
}
