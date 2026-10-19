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
    void manyLongLinesMakeAFileLongLinedThoughNoLineReachesTheSingleLineLimit() {
        String wrapped = ("x".repeat(19_000) + "\n").repeat(40); // a minified bundle wrapped at 19,000 characters
        FileWorkflowCoordinator.TextStats stats = FileWorkflowCoordinator.textStats(wrapped);
        assertTrue(stats.maxLineLength() < FileWorkflowCoordinator.LONG_LINE_FILE_CHARS);
        assertTrue(stats.longLined(), "760,000 characters in long lines");
        String few = ("x".repeat(12_000) + "\n").repeat(5) + "code();\n".repeat(1_000);
        assertFalse(FileWorkflowCoordinator.textStats(few).longLined(), "a few long lines are not enough");
        assertTrue(FileWorkflowCoordinator.textStats("y".repeat(FileWorkflowCoordinator.LONG_LINE_FILE_CHARS))
                .longLined());
    }
}
