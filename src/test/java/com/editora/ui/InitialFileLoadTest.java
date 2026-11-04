package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialFileLoadTest {

    @Test
    void textStatsFindsLineCountAndWidestLineInOnePass() {
        assertEquals(new FileWorkflowCoordinator.TextStats(1, 0), FileWorkflowCoordinator.textStats(""));
        assertEquals(new FileWorkflowCoordinator.TextStats(3, 5), FileWorkflowCoordinator.textStats("abc\n12345\nx"));
        assertEquals(new FileWorkflowCoordinator.TextStats(2, 4), FileWorkflowCoordinator.textStats("four\n"));
    }

    @Test
    void onlyALineTooWideToLayOutMakesAFileLongLined() {
        // A bundle wrapped at 19,000 characters keeps undo now: its long lines are shown plain by the highlighter
        // and it opens in View mode (LongLineFiles), so long-line mode is left to the 64K paragraph.
        String wrapped = ("x".repeat(19_000) + "\n").repeat(40);
        assertFalse(FileWorkflowCoordinator.textStats(wrapped).longLined(), "760,000 characters in 19K lines");
        assertTrue(FileWorkflowCoordinator.textStats("y".repeat(FileWorkflowCoordinator.LONG_LINE_FILE_CHARS))
                .longLined());
    }
}
