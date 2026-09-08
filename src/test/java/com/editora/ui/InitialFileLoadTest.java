package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitialFileLoadTest {

    @Test
    void textStatsFindsLineCountAndWidestLineInOnePass() {
        assertEquals(new FileWorkflowCoordinator.TextStats(1, 0), FileWorkflowCoordinator.textStats(""));
        assertEquals(new FileWorkflowCoordinator.TextStats(3, 5), FileWorkflowCoordinator.textStats("abc\n12345\nx"));
        assertEquals(new FileWorkflowCoordinator.TextStats(2, 4), FileWorkflowCoordinator.textStats("four\n"));
    }
}
