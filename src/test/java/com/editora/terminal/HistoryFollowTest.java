package com.editora.terminal;

import java.util.concurrent.atomic.AtomicInteger;

import com.jediterm.core.compatibility.Point;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.CursorShape;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalDisplay;
import com.jediterm.terminal.emulator.mouse.MouseFormat;
import com.jediterm.terminal.emulator.mouse.MouseMode;
import com.jediterm.terminal.model.JediTerminal;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A selection stays on its text as output arrives — through a real {@link JediTerminal} writing
 * into a real buffer, with no PTY and no toolkit.
 *
 * <p>The regression this pins: the history listener never fires for an ordinary newline, so the
 * first version shifted nothing, and a selection made over {@code 6 7 8} was drawn over {@code 75
 * 76 77} a moment later and would have copied that. The second half is the capped scrollback, where
 * the history count stops moving while the lines do not.
 */
class HistoryFollowTest {

    private static final int ROWS = 4;
    private static final int MAX_HISTORY = 20;

    private TerminalTextBuffer buffer;
    private JediTerminal terminal;
    private HistoryFollow follow;
    private final AtomicInteger cleared = new AtomicInteger();

    /** What the view does with each change: the same three lines, without the view. */
    private volatile TerminalSelection selection;

    private int scrollOrigin;

    @BeforeEach
    void setUp() {
        StyleState styleState = new StyleState();
        buffer = new TerminalTextBuffer(10, ROWS, styleState, MAX_HISTORY);
        terminal = new JediTerminal(new Display(), buffer, styleState);
        follow = new HistoryFollow(() -> {
            cleared.incrementAndGet();
            selection = null;
            scrollOrigin = 0;
        });
        buffer.addChangesListener(follow);
        buffer.addModelListener(() -> {
            int history = buffer.getHistoryLinesCount();
            HistoryFollow.Change change = follow.sync(history, buffer.getHeight());
            if (change.resize()) {
                scrollOrigin = HistoryFollow.origin(scrollOrigin, 0, history);
            } else if (change.delta() != 0) {
                scrollOrigin = HistoryFollow.origin(scrollOrigin, change.delta(), history);
                TerminalSelection current = selection;
                if (current != null) current.shiftY(-change.delta());
            }
        });
    }

    private void writeLines(int from, int count) {
        for (int i = from; i < from + count; i++) {
            terminal.writeCharacters("line" + i);
            terminal.nextLine();
        }
    }

    /** Selects the whole of the screen row holding {@code text}, and returns it for comparison. */
    private String select(String text) {
        for (int row = 0; row < ROWS; row++) {
            if (buffer.getLine(row).getText().startsWith(text)) {
                selection = new TerminalSelection(new Point(0, row), new Point(text.length(), row));
                return text;
            }
        }
        throw new AssertionError(text + " is not on screen:\n" + buffer.getScreenLines());
    }

    private String selectedText() {
        return SelectionUtil.getSelectionText(selection, buffer);
    }

    @Test
    void aSelectionFollowsItsTextIntoHistory() {
        writeLines(0, 6);
        String text = select("line4");
        int startRow = selection.getStart().y;

        writeLines(6, 3);

        assertEquals(startRow - 3, selection.getStart().y, "start moved by the lines written");
        assertEquals(text, selectedText());
    }

    @Test
    void itKeepsFollowingOnceTheScrollbackIsFull() {
        // Fill it: more lines than history plus screen can hold.
        writeLines(0, MAX_HISTORY + ROWS + 5);
        assertEquals(MAX_HISTORY, buffer.getHistoryLinesCount(), "scrollback is at its cap");
        String text = select("line" + (MAX_HISTORY + ROWS + 3));
        int startRow = selection.getStart().y;

        writeLines(100, 3);

        // The count did not change; the lines still moved, and the selection with them.
        assertEquals(MAX_HISTORY, buffer.getHistoryLinesCount());
        assertEquals(startRow - 3, selection.getStart().y);
        assertEquals(text, selectedText());
    }

    @Test
    void aScrolledBackViewportFollowsAndTheLiveViewStaysLive() {
        writeLines(0, 10);
        scrollOrigin = -3;
        writeLines(10, 2);
        assertEquals(-5, scrollOrigin, "scrolled back: the same rows stay in view");

        scrollOrigin = 0;
        writeLines(12, 2);
        assertEquals(0, scrollOrigin, "live: pinned to the bottom");
    }

    @Test
    void aShorterWindowDoesNotShiftTheSelectionTwice() {
        // JediTerm moves the selection itself when rows are pushed into history by a resize. A
        // tracker that counted those rows as output would move it again, by the same amount.
        writeLines(0, 6);
        String text = select("line4");

        terminal.resize(new TermSize(10, ROWS - 1), RequestOrigin.User);

        assertEquals(text, selectedText());
    }

    @Test
    void aReflowDoesNotShiftTheSelection() {
        writeLines(0, 6);
        String text = select("line4");

        terminal.resize(new TermSize(14, ROWS), RequestOrigin.User);

        assertEquals(text, selectedText());
    }

    @Test
    void outputAfterAResizeIsFollowedAgain() {
        writeLines(0, 6);
        terminal.resize(new TermSize(10, ROWS - 1), RequestOrigin.User);
        String text = select("line4");
        int startRow = selection.getStart().y;

        writeLines(6, 2);

        assertEquals(startRow - 2, selection.getStart().y);
        assertEquals(text, selectedText());
    }

    @Test
    void clearingTheHistoryDropsWhatWasMeasuredAgainstIt() {
        writeLines(0, 10);
        select("line8");
        scrollOrigin = -2;

        buffer.lock();
        try {
            buffer.clearHistory();
        } finally {
            buffer.unlock();
        }

        assertEquals(1, cleared.get());
        assertNull(selection);
        assertEquals(0, scrollOrigin);

        // And the count restarts from zero, so the next line is a move of one, not of minus ten.
        select("line9");
        int startRow = selection.getStart().y;
        writeLines(10, 1);
        assertEquals(startRow - 1, selection.getStart().y);
    }

    @Test
    void theViewportIsClampedToTheHistoryThatExists() {
        assertEquals(0, HistoryFollow.origin(0, 5, 100));
        assertEquals(-8, HistoryFollow.origin(-3, 5, 100));
        assertEquals(-100, HistoryFollow.origin(-98, 5, 100));
        assertEquals(0, HistoryFollow.origin(-3, -5, 100), "lines coming back out never scroll past live");
    }

    /** Hands JediTerm the live selection, as the view does, so a resize can adjust it. */
    private final class Display implements TerminalDisplay {
        @Override
        public void setCursor(int x, int y) {}

        @Override
        public void setCursorShape(CursorShape shape) {}

        @Override
        public void beep() {}

        @Override
        public void onResize(TermSize newSize, RequestOrigin origin) {}

        @Override
        public void scrollArea(int top, int size, int dy) {}

        @Override
        public void setCursorVisible(boolean visible) {}

        @Override
        public void useAlternateScreenBuffer(boolean enabled) {}

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(String title) {}

        @Override
        public TerminalSelection getSelection() {
            return selection;
        }

        @Override
        public void terminalMouseModeSet(MouseMode mode) {}

        @Override
        public void setMouseFormat(MouseFormat format) {}

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
