package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure relevance-ranking for the command palette (no toolkit needed). */
class CommandPaletteRankTest {

    @Test
    void scoresExactWholeWordWordStartSubstringSubsequence() {
        assertEquals(5, CommandPalette.relevance("undo", "Undo"));
        assertEquals(4, CommandPalette.relevance("undo", "Edit: Undo")); // whole word
        assertEquals(4, CommandPalette.relevance("undo", "Undo History")); // whole word at start
        assertEquals(3, CommandPalette.relevance("undo", "Undoubtedly")); // word-start, not whole word
        assertEquals(2, CommandPalette.relevance("do", "Window")); // substring mid-word
        assertEquals(1, CommandPalette.relevance("undo", "Unsplit Editor")); // scattered subsequence only
    }

    @Test
    void undoRanksEditUndoFirst() {
        // The reported case: typing "undo" must surface "Edit: Undo" above a scattered match.
        List<String> ranked =
                List.of("View: Unsplit Editor", "Undo History", "Toggle Run Window", "Edit: Undo").stream()
                        .sorted(CommandPalette.byRelevance("undo"))
                        .toList();
        assertEquals("Edit: Undo", ranked.get(0));
        assertEquals("Undo History", ranked.get(1));
        assertTrue(ranked.indexOf("View: Unsplit Editor") > 1, "scattered matches sink to the bottom");
    }
}
