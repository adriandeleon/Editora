package com.editora.terminal;

import java.util.List;

import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TextBufferChangesListener;

/**
 * Works out how far the text moved on each buffer change, so a selection and a scrolled-back viewport can be kept on
 * the characters they were made over.
 *
 * <p>Nothing in JediTerm says this directly. The history listener sounds like the right hook and is not: it fires from
 * {@code clearHistory} and from the resize path, never from the {@code scrollArea} every ordinary newline takes. The
 * model listener does fire per mutation, and the history line count is what moved — until the scrollback is full,
 * when the count stops changing while lines keep falling off the top. The changes listener's {@link
 * #linesDiscardedFromHistory} fills that gap: lines that left history moved the rest just as surely as lines that
 * entered it.
 *
 * <p>A resize is reported as one. JediTerm moves the selection itself when a shorter window pushes rows into history,
 * and tracks its endpoints through a reflow; shifting it again here moved it twice, so a selection made before a
 * splitter drag copied rows above the ones highlighted. A resize announces itself as a width change ({@link
 * #widthResized}) or as a screen height that differs from the last one seen.
 *
 * <p>A cleared history is reported at once rather than at the next sync, because the buffer fires its model event
 * <em>before</em> it announces the clear: by the time {@link #sync} sees a count of zero it cannot tell a clear from
 * lines leaving.
 *
 * <p>Called on the emulator thread, under the buffer lock, which is what makes the two listeners' bookkeeping
 * consistent without any of its own. Ported from Termina; here a resize also reports its delta, which the view's
 * prompt marks and search hits still follow.
 */
final class HistoryFollow implements TextBufferChangesListener {

    /**
     * What one buffer change did to the coordinate system.
     *
     * @param delta rows the text moved up by — positive as lines age into history, negative when they come back out,
     *     zero when nothing moved
     * @param resize the move was a resize, which JediTerm has already applied to the selection
     */
    record Change(int delta, boolean resize) {
        static final Change NONE = new Change(0, false);
    }

    private final Runnable onHistoryCleared;
    private int knownHistory;
    private int knownHeight = -1;
    private int discarded;
    private boolean resized;

    /**
     * @param onHistoryCleared run, on the buffer's thread, when the scrollback is dropped — whatever was measured
     *     against it has to go with it
     */
    HistoryFollow(Runnable onHistoryCleared) {
        this.onHistoryCleared = onHistoryCleared;
    }

    @Override
    public void linesDiscardedFromHistory(List<TerminalLine> lines) {
        discarded += lines.size();
    }

    @Override
    public void widthResized() {
        resized = true;
    }

    @Override
    public void historyCleared() {
        knownHistory = 0;
        discarded = 0;
        onHistoryCleared.run();
    }

    /**
     * Reconciles against the buffer's current counts. Call from the model listener, after the change, with the
     * history line count and the screen height as they now are.
     */
    Change sync(int historyLines, int screenHeight) {
        int delta = historyLines - knownHistory + discarded;
        boolean resize = resized || (knownHeight >= 0 && knownHeight != screenHeight);
        knownHistory = historyLines;
        knownHeight = screenHeight;
        discarded = 0;
        resized = false;
        if (delta == 0 && !resize) return Change.NONE;
        return new Change(delta, resize);
    }

    /**
     * Where a viewport ends up after the text moved.
     *
     * <p>The live view stays live: at origin 0 following the bottom is what is wanted, and it is the common case. A
     * scrolled-back view follows its text, and in either case the result is clamped to the history that exists,
     * because a resize can take rows away under it.
     */
    static int origin(int origin, int delta, int historyLines) {
        if (origin == 0) return 0;
        return Math.max(-historyLines, Math.min(0, origin - delta));
    }
}
