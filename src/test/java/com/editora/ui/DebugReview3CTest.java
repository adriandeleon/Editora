package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review 3, reviewer C's pure checks: the Breakpoints list's rows. */
class DebugReview3CTest {

    /** C107: at 1400 px a row's code took the room of its condition ("Condition: lengths.size…"). */
    @Test
    void aLongLineOfCodeIsCutSoTheConditionFits() {
        assertEquals("x();", BreakpointsPicker.shortCode("x();"));
        String line = "int total = sum(lengths.values()); // BP-COND with a long comment after it";
        String cut = BreakpointsPicker.shortCode(line);
        assertEquals(BreakpointsPicker.CODE_CHARS, cut.length(), cut);
        assertTrue(cut.endsWith("…") && line.startsWith(cut.substring(0, cut.length() - 1)), cut);
        String exact = "y".repeat(BreakpointsPicker.CODE_CHARS);
        assertEquals(exact, BreakpointsPicker.shortCode(exact), "a line that fits is left whole");
        assertTrue(BreakpointsPicker.WIDTH > 620, "wider than a file picker");
    }
}
