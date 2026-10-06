package com.editora.process;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The span of one {@link OutputPump} drain during which output lines are being handed to their listener, so
 * that whatever shows them can apply the whole drain as <em>one</em> edit instead of one per line.
 *
 * <p>The pump's listeners are per-line by contract (a test runner parses each line; a before-launch step
 * watches for one), and a console sits several interfaces behind them. Rather than widen every one of those
 * to carry a batch, a console asks here: {@link #defer} inside a drain parks its flush until the drain's
 * lines have all been delivered; outside a drain it answers {@code false} and the console applies the text
 * at once, exactly as before. A rich-text append costs an edit, a restyle and a scroll, so 256 lines a drain
 * were 256 of each — the UI thread did nothing else while a program printed.
 *
 * <p>FX-thread only, like the drains themselves.
 */
public final class OutputBatch {

    private static final Logger LOG = Logger.getLogger(OutputBatch.class.getName());

    private static int depth;
    private static final Set<Runnable> deferred = new LinkedHashSet<>();

    private OutputBatch() {}

    /**
     * Runs {@code flush} once when the drain now delivering output has handed over its last line, however
     * often it is asked for in that drain. False when no drain is delivering: the caller flushes itself.
     */
    public static boolean defer(Runnable flush) {
        if (depth == 0) {
            return false;
        }
        deferred.add(flush);
        return true;
    }

    /** Opens the span; every {@code begin} is paired with an {@link #end}. */
    public static void begin() {
        depth++;
    }

    /**
     * Closes the span and runs what was deferred in it. A drain entered from a nested event loop closes
     * first and flushes the outer drain's consoles with its own — early, which is harmless: they defer
     * again on their next line.
     */
    public static void end() {
        depth = Math.max(0, depth - 1);
        if (deferred.isEmpty()) {
            return;
        }
        List<Runnable> flushes = new ArrayList<>(deferred);
        deferred.clear();
        for (Runnable flush : flushes) {
            try {
                flush.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A console flush failed; continuing", e);
            }
        }
    }
}
