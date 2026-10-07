package com.editora.logviewer;

import java.util.function.IntFunction;

/**
 * Pure navigation over a log's lines: the next / previous line at or above a severity level, for the
 * "jump to next error" commands in the log viewer. Wraps around the ends and never returns the line the
 * caret is already on (so repeated presses cycle through the error/warning lines). No JavaFX, so it is
 * unit-tested; classification reuses {@link LogPatterns#levelOf}.
 */
public final class LogNavigation {

    private LogNavigation() {}

    /**
     * The 0-based index of the next (or previous) line whose level is at least {@code minLevel}, searching
     * from {@code fromLine} in the given direction and wrapping around, or {@code -1} when no <em>other</em>
     * line qualifies. {@code minLevel} defaults to {@link LogLevel#WARN} when null.
     */
    public static int nextLevelLine(String text, int fromLine, boolean forward, LogLevel minLevel) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        return nextLevelLine(i -> lines[i], lines.length, fromLine, forward, minLevel);
    }

    /**
     * As above over {@code count} lines read through {@code lineAt} — the editor's own paragraphs, so a jump
     * does not first copy a large log into an array of its lines.
     */
    public static int nextLevelLine(
            IntFunction<String> lineAt, int count, int fromLine, boolean forward, LogLevel minLevel) {
        LogLevel min = minLevel == null ? LogLevel.WARN : minLevel;
        for (int step = 1; step < count; step++) { // 1 .. n-1 visits every other line exactly once
            int i = Math.floorMod(fromLine + (forward ? step : -step), count);
            LogLevel level = LogPatterns.levelOf(lineAt.apply(i));
            if (level != null && level.atLeast(min)) {
                return i;
            }
        }
        return -1;
    }
}
