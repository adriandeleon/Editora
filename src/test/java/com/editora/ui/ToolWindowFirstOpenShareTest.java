package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JS review C106: the windows that show a program's output open tall enough to read. */
class ToolWindowFirstOpenShareTest {

    @Test
    void outputWindowsOpenTallerAndOthersKeepTheSidesSize() {
        for (String id : new String[] {"run", "testResults", "buildOutput"}) {
            assertTrue(ToolWindow.defaultFirstOpenShare(id) >= 0.3, id);
        }
        assertEquals(0, ToolWindow.defaultFirstOpenShare("todo"));
        assertEquals(0, ToolWindow.defaultFirstOpenShare("project"));
    }
}
