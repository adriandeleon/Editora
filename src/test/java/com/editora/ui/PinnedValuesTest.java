package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.config.PinnedLine;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a pinned value paints at the end of its line. */
class PinnedValuesTest {

    @Test
    void eachLineShowsItsExpressionsInTheOrderTheyWerePinned() {
        Map<Integer, PinnedLine> lines = Map.of(
                4, new PinnedLine(4, List.of("total", "i * 2"), ""),
                9, new PinnedLine(9, List.of("missing"), ""));
        Map<Integer, String> painted = PinnedValues.paint(lines, Map.of(4, Map.of("i * 2", "6", "total", "3")));
        assertEquals(Map.of(4, "total = 3  ·  i * 2 = 6"), painted, "a line with no answer yet is not painted");
    }

    @Test
    void aValueIsCutToOneShortLineAndAFailureSaysWhy() {
        assertEquals("[1, 2, 3] …", PinnedValues.shown(new DapModels.EvalResult("[1, 2, 3]\n[4]", 0, null)));
        assertEquals(
                "x".repeat(PinnedValues.MAX_VALUE_CHARS) + "…",
                PinnedValues.shown(new DapModels.EvalResult("x".repeat(100), 0, null)));
        assertEquals(
                tr("debug.pin.failed", "y cannot be resolved"),
                PinnedValues.shown(DapModels.EvalResult.failure("y cannot be resolved")));
        assertEquals("?", PinnedValues.shown(null));
    }
}
