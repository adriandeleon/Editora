package com.editora.terminal;

import java.util.concurrent.TimeUnit;

import com.jediterm.core.typeahead.TypeAheadTerminalModel;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Type-ahead: keystrokes painted before the shell echoes them, so typing over a slow link feels local.
 *
 * <p>JediTerm's {@code TerminalTypeAheadManager} decides what to predict and when a prediction proved wrong; this
 * is the model it predicts into. JediTerm's own model writes predictions into a package-private field of the
 * buffer's line, which only its Swing renderer reads, so here they live beside the buffer instead: the predicted
 * text of the cursor's row, which {@link TerminalView} paints over the row in a dim colour.
 *
 * <p>The manager measures how long echoes take and predicts only once two have taken longer than {@link
 * #LATENCY_THRESHOLD_NANOS} (JediTerm's default), and stops again when they get fast. A local shell echoes within
 * a millisecond, so turning this on costs it nothing. Programs on the alternate screen (vim, less) never get
 * predictions. Every method runs under the buffer's lock.
 */
public final class TypeAheadOverlay implements TypeAheadTerminalModel {

    /** Echoes slower than this turn predictions on. */
    public static final long LATENCY_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /** The predicted text of one row (0 = the top of the live screen). */
    public record Prediction(int row, String text) {}

    private final JediTerminal terminal;
    private final TerminalTextBuffer buffer;
    private final Runnable repaint;
    private volatile boolean enabled;
    private @Nullable StringBuilder predicted;
    private int predictedRow;

    public TypeAheadOverlay(JediTerminal terminal, TerminalTextBuffer buffer, Runnable repaint) {
        this.terminal = terminal;
        this.buffer = buffer;
        this.repaint = repaint;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** What to paint, or null: predictions only count on the row the cursor is still on. Caller holds the lock. */
    public @Nullable Prediction prediction() {
        StringBuilder text = predicted;
        return text == null || predictedRow != cursorRow() ? null : new Prediction(predictedRow, text.toString());
    }

    @Override
    public void insertCharacter(char ch, int index) {
        StringBuilder line = workingLine();
        while (line.length() < index) {
            line.append(' ');
        }
        line.insert(Math.max(0, index), ch);
        predicted = line;
        predictedRow = cursorRow();
    }

    @Override
    public void removeCharacters(int from, int count) {
        StringBuilder line = workingLine();
        int start = Math.max(0, Math.min(from, line.length()));
        line.delete(start, Math.min(line.length(), start + Math.max(0, count)));
        predicted = line;
        predictedRow = cursorRow();
    }

    @Override
    public void moveCursor(int index) {
        // The manager reports the predicted cursor itself (getCursorX); nothing is kept here.
    }

    @Override
    public void forceRedraw() {
        repaint.run();
    }

    @Override
    public void clearPredictions() {
        predicted = null;
    }

    @Override
    public void lock() {
        buffer.lock();
    }

    @Override
    public void unlock() {
        buffer.unlock();
    }

    @Override
    public boolean isUsingAlternateBuffer() {
        return buffer.isUsingAlternateBuffer();
    }

    @Override
    public LineWithCursorX getCurrentLineWithCursor() {
        return new LineWithCursorX(new StringBuffer(buffer.getLine(cursorRow()).getText()), terminal.getCursorX() - 1);
    }

    @Override
    public int getTerminalWidth() {
        return terminal.getTerminalWidth();
    }

    @Override
    public boolean isTypeAheadEnabled() {
        return enabled;
    }

    @Override
    public long getLatencyThreshold() {
        return LATENCY_THRESHOLD_NANOS;
    }

    @Override
    public ShellType getShellType() {
        return ShellType.Unknown;
    }

    /** The predicted line so far, or the real one to start predicting from. */
    private StringBuilder workingLine() {
        if (predicted != null && predictedRow == cursorRow()) {
            return new StringBuilder(predicted);
        }
        return new StringBuilder(buffer.getLine(cursorRow()).getText());
    }

    private int cursorRow() {
        return terminal.getCursorY() - 1;
    }
}
