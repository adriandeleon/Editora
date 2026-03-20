package org.adriandeleon.editora.editor;

import org.adriandeleon.editora.settings.ToolWindowSide;

public final class ToolWindowLayoutSupport {
    private static final double MIN_DIVIDER_POSITION = 0.05d;
    private static final double MAX_DIVIDER_POSITION = 0.95d;

    private ToolWindowLayoutSupport() {
    }

    public static double computeDividerPosition(double splitWidth,
                                                double toolWindowWidth,
                                                ToolWindowSide side,
                                                double fallbackDividerPosition) {
        if (splitWidth <= 0 || toolWindowWidth <= 0 || toolWindowWidth >= splitWidth) {
            return clampDividerPosition(fallbackDividerPosition);
        }

        double ratio = toolWindowWidth / splitWidth;
        return clampDividerPosition(side == ToolWindowSide.RIGHT ? 1d - ratio : ratio);
    }

    public static double clampDividerPosition(double dividerPosition) {
        return Math.max(MIN_DIVIDER_POSITION, Math.min(MAX_DIVIDER_POSITION, dividerPosition));
    }
}


