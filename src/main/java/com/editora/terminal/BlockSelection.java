package com.editora.terminal;

import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;

/**
 * The text of a rectangular selection — what JediTerm has no notion of.
 *
 * <p>{@code SelectionUtil.getSelectionText} reads a stream: the first row from its start, the middle
 * rows whole, the last to its end, with wrapped rows joined. A block is none of that. Each row is cut
 * at the same two <em>columns</em>, translated to that row's own slots (see {@link ColumnMap}), and
 * every row ends in a newline, wrapped or not: a column of an {@code ls -l} listing is a list of
 * names, and joining two of them because the line happened to wrap would make one name out of two.
 * Trailing blanks go, as they do when copying a stream; the cells past the end of a short line are
 * padding, not text.
 *
 * <p>Static and given the buffer, like {@link TerminalView#wordSelection}, so it is tested against a
 * real buffer without a view.
 */
final class BlockSelection {

    private BlockSelection() {}

    /** The block's text, one line per row. The caller holds the buffer lock. */
    static String text(TerminalSelection block, TerminalTextBuffer buffer) {
        int top = Math.min(block.getStart().y, block.getEnd().y);
        int bottom = Math.max(block.getStart().y, block.getEnd().y);
        // Clamped to what exists: a row that scrolled out of the history is gone, and asking for it
        // is an ERROR line in the log rather than an exception.
        top = Math.max(top, -buffer.getHistoryLinesCount());
        bottom = Math.min(bottom, buffer.getHeight() - 1);
        StringBuilder out = new StringBuilder();
        for (int row = top; row <= bottom; row++) {
            int[] span = SelectionGeometry.blockOnRow(block, row);
            if (span == null) continue;
            if (row > top) out.append('\n');
            out.append(cut(buffer.getLine(row).getText(), span[0], span[1]));
        }
        return out.toString();
    }

    /** One row's share of the block: columns {@code from} to {@code to}, as text. */
    static String cut(String line, int from, int to) {
        int start = Math.min(line.length(), ColumnMap.slotAt(line, from));
        int end = Math.min(line.length(), ColumnMap.slotAt(line, to));
        if (end <= start) return "";
        StringBuilder row = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            // The second cell of a wide glyph is a placeholder, not a character.
            if (c != CharUtils.DWC) row.append(c);
        }
        int length = row.length();
        while (length > 0 && (row.charAt(length - 1) == ' ' || row.charAt(length - 1) == '\0')) length--;
        row.setLength(length);
        return row.toString();
    }
}
