package com.editora.terminal;

import com.jediterm.core.typeahead.TypeAheadTerminalModel;

/**
 * Type-ahead turned off.
 *
 * <p>{@code TerminalStarter} requires a {@code TerminalTypeAheadManager}, and that in turn requires
 * a model — there is no null-object in the library. Type-ahead speculatively paints keystrokes
 * before the shell echoes them, which hides latency on a slow link but mispredicts on a local PTY
 * (a local shell echoes within a frame anyway, so the only visible effect is the occasional wrong
 * guess). {@link #isTypeAheadEnabled()} returning false makes the manager short-circuit, so the
 * remaining methods are never reached.
 */
public final class DisabledTypeAhead implements TypeAheadTerminalModel {

    @Override
    public void insertCharacter(char ch, int index) {}

    @Override
    public void removeCharacters(int from, int count) {}

    @Override
    public void moveCursor(int index) {}

    @Override
    public void forceRedraw() {}

    @Override
    public void clearPredictions() {}

    @Override
    public void lock() {}

    @Override
    public void unlock() {}

    @Override
    public boolean isUsingAlternateBuffer() {
        return false;
    }

    @Override
    public LineWithCursorX getCurrentLineWithCursor() {
        return new LineWithCursorX(new StringBuffer(), 0);
    }

    @Override
    public int getTerminalWidth() {
        return 0;
    }

    @Override
    public boolean isTypeAheadEnabled() {
        return false;
    }

    @Override
    public long getLatencyThreshold() {
        return Long.MAX_VALUE;
    }

    @Override
    public ShellType getShellType() {
        return ShellType.Unknown;
    }
}
