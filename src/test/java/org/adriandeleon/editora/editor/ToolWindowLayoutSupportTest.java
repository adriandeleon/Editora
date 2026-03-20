package org.adriandeleon.editora.editor;

import org.adriandeleon.editora.settings.ToolWindowSide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolWindowLayoutSupportTest {

    @Test
    void computesLeftDockDividerFromSavedWidth() {
        double dividerPosition = ToolWindowLayoutSupport.computeDividerPosition(1200d, 300d, ToolWindowSide.LEFT, 0.22d);

        assertEquals(0.25d, dividerPosition);
    }

    @Test
    void computesRightDockDividerFromSavedWidth() {
        double dividerPosition = ToolWindowLayoutSupport.computeDividerPosition(1200d, 300d, ToolWindowSide.RIGHT, 0.22d);

        assertEquals(0.75d, dividerPosition);
    }

    @Test
    void clampsFallbackDividerWhenWidthIsUnavailable() {
        double dividerPosition = ToolWindowLayoutSupport.computeDividerPosition(0d, 320d, ToolWindowSide.RIGHT, 1.4d);

        assertEquals(0.95d, dividerPosition);
    }
}

