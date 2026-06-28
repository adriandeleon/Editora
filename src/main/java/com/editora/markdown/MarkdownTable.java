package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) GFM table reflow: given the text block of a pipe table, pad every column
 * to its widest cell and rebuild the delimiter row honoring alignment ({@code :--} left, {@code :-:}
 * center, {@code --:} right). {@link #blockBounds(String, int)} finds the contiguous table block around a
 * caret so {@code EditorBuffer} can select + replace it. Column widths use {@code String.length()} (CJK
 * double-width and escaped {@code \|} are not special-cased).
 */
public final class MarkdownTable {

    /** A delimiter cell: optional leading/trailing colon around one-or-more dashes. */
    private static final Pattern DELIM_CELL = Pattern.compile("\\s*:?-+:?\\s*");

    private static final int MIN_WIDTH = 3; // room for "---" / ":-:"

    private enum Align {
        NONE,
        LEFT,
        CENTER,
        RIGHT
    }

    private MarkdownTable() {}

    /** True if {@code line} looks like a table row (contains a pipe and is not blank). */
    private static boolean isRow(String line) {
        return line != null && !line.isBlank() && line.indexOf('|') >= 0;
    }

    /**
     * The {@code [start, end)} offsets of the contiguous run of table rows containing {@code caret}, or
     * {@code null} when the caret's line is not a table row.
     */
    public static int[] blockBounds(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        int ls = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        int le = text.indexOf('\n', caret);
        if (le < 0) {
            le = text.length();
        }
        if (!isRow(text.substring(ls, le))) {
            return null;
        }
        int start = ls;
        while (start > 0) {
            int prevEnd = start - 1;
            int prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1;
            if (!isRow(text.substring(prevStart, prevEnd))) {
                break;
            }
            start = prevStart;
        }
        int end = le;
        while (end < text.length()) {
            int nextStart = end + 1;
            int nextEnd = text.indexOf('\n', nextStart);
            if (nextEnd < 0) {
                nextEnd = text.length();
            }
            if (nextStart > text.length() || !isRow(text.substring(nextStart, nextEnd))) {
                break;
            }
            end = nextEnd;
        }
        return new int[] {start, end};
    }

    /**
     * Reflows a table {@code block} (no trailing newline). Returns the input unchanged when it is not a
     * table with a delimiter row.
     */
    public static String reflow(String block) {
        if (block == null) {
            return block;
        }
        String[] lines = block.split("\n", -1);
        if (lines.length < 2) {
            return block;
        }
        String indent = leadingWhitespace(lines[0]);
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(splitCells(line));
        }
        int delim = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (isDelimiterRow(lines[i])) {
                delim = i;
                break;
            }
        }
        if (delim < 0) {
            return block; // not a real GFM table — leave it alone
        }
        int ncol = 0;
        for (List<String> r : rows) {
            ncol = Math.max(ncol, r.size());
        }
        Align[] align = alignments(rows.get(delim), ncol);
        int[] width = new int[ncol];
        for (int c = 0; c < ncol; c++) {
            width[c] = MIN_WIDTH;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i == delim) {
                continue;
            }
            List<String> r = rows.get(i);
            for (int c = 0; c < r.size(); c++) {
                width[c] = Math.max(width[c], r.get(c).length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(indent);
            if (i == delim) {
                out.append(delimiterRow(width, align));
            } else {
                out.append(dataRow(rows.get(i), width, align, ncol));
            }
        }
        return out.toString();
    }

    /** The result of a table navigation: the (reflowed) block text and the caret offset within it. */
    public record Nav(String block, int caret) {}

    /**
     * Tab/Shift-Tab navigation within a table {@code block}. Reflows the block and returns the caret offset
     * (within the reflowed block) at the next/previous cell's content. Forward past the last cell appends a
     * new row. Returns {@code null} when the block is not a GFM table (no delimiter row).
     */
    public static Nav tab(String block, int caretInBlock, boolean forward) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        int lineStart = lineStartOffset(block, li);
        int ci = cellIndexAt(lines[li], caretInBlock - lineStart);

        int targetLine = li;
        int targetCell = ci;
        String working = block;
        if (forward) {
            if (ci + 1 < cellCount(lines[li])) {
                targetCell = ci + 1;
            } else {
                int next = nextNonDelim(lines, li, delim);
                if (next >= 0) {
                    targetLine = next;
                    targetCell = 0;
                } else {
                    working = block + "\n" + emptyRow(columnCount(lines));
                    targetLine = working.split("\n", -1).length - 1;
                    targetCell = 0;
                }
            }
        } else {
            if (ci - 1 >= 0) {
                targetCell = ci - 1;
            } else {
                int prev = prevNonDelim(lines, li, delim);
                if (prev < 0) {
                    return new Nav(reflow(block), caretInBlock); // already at the first cell
                }
                targetLine = prev;
                targetCell = Math.max(0, cellCount(lines[prev]) - 1);
            }
        }
        String reflowed = reflow(working);
        return new Nav(reflowed, cellContentOffset(reflowed, targetLine, targetCell));
    }

    /**
     * Enter within a table: if the caret is on the last row, append a new empty row and put the caret in its
     * first cell. Returns {@code null} otherwise (caller does a normal newline).
     */
    public static Nav enter(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        if (delimiterIndex(lines) < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        if (li != lines.length - 1) {
            return null; // only the last row adds a row; mid-table Enter is a normal split
        }
        String working = block + "\n" + emptyRow(columnCount(lines));
        String reflowed = reflow(working);
        int newLine = reflowed.split("\n", -1).length - 1;
        return new Nav(reflowed, cellContentOffset(reflowed, newLine, 0));
    }

    private static int delimiterIndex(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (isDelimiterRow(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int columnCount(String[] lines) {
        int n = 0;
        for (String line : lines) {
            n = Math.max(n, cellCount(line));
        }
        return n;
    }

    private static String emptyRow(int ncol) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < Math.max(1, ncol); c++) {
            sb.append("  |");
        }
        return sb.toString();
    }

    /** Number of cells in a row (segments between pipes, excluding the empty leading/trailing slots). */
    private static int cellCount(String line) {
        return splitCells(line).size();
    }

    private static int lineIndexAt(String block, int caret) {
        int c = Math.max(0, Math.min(caret, block.length()));
        int idx = 0;
        for (int i = 0; i < c; i++) {
            if (block.charAt(i) == '\n') {
                idx++;
            }
        }
        return idx;
    }

    private static int lineStartOffset(String block, int lineIndex) {
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset = block.indexOf('\n', offset) + 1;
        }
        return offset;
    }

    /** Which cell column {@code col} falls in (0-based), counting pipes before it. */
    private static int cellIndexAt(String line, int col) {
        boolean leadingPipe = line.strip().startsWith("|");
        int pipesBefore = 0;
        for (int i = 0; i < Math.min(col, line.length()); i++) {
            if (line.charAt(i) == '|') {
                pipesBefore++;
            }
        }
        int ci = leadingPipe ? pipesBefore - 1 : pipesBefore;
        return Math.max(0, Math.min(ci, Math.max(0, cellCount(line) - 1)));
    }

    /** Absolute offset (within the reflowed block) of the start of cell {@code cell} on line {@code line}. */
    private static int cellContentOffset(String block, int line, int cell) {
        int lineStart = lineStartOffset(block, line);
        String text = lineAt(block, line);
        int pipes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '|') {
                if (pipes == cell) {
                    int content = i + 1;
                    while (content < text.length() && text.charAt(content) == ' ') {
                        content++;
                    }
                    return lineStart + content;
                }
                pipes++;
            }
        }
        return lineStart + text.length();
    }

    private static String lineAt(String block, int line) {
        int start = lineStartOffset(block, line);
        int end = block.indexOf('\n', start);
        return end < 0 ? block.substring(start) : block.substring(start, end);
    }

    private static int nextNonDelim(String[] lines, int from, int delim) {
        for (int i = from + 1; i < lines.length; i++) {
            if (i != delim) {
                return i;
            }
        }
        return -1;
    }

    private static int prevNonDelim(String[] lines, int from, int delim) {
        for (int i = from - 1; i >= 0; i--) {
            if (i != delim) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitCells(String line) {
        String t = line.strip();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String c : t.split("\\|", -1)) {
            cells.add(c.strip());
        }
        return cells;
    }

    private static boolean isDelimiterRow(String line) {
        List<String> cells = splitCells(line);
        if (cells.isEmpty()) {
            return false;
        }
        for (String c : cells) {
            if (!DELIM_CELL.matcher(c).matches()) {
                return false;
            }
        }
        return true;
    }

    private static Align[] alignments(List<String> delimCells, int ncol) {
        Align[] a = new Align[ncol];
        for (int c = 0; c < ncol; c++) {
            String cell = c < delimCells.size() ? delimCells.get(c).strip() : "";
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            a[c] = left && right ? Align.CENTER : right ? Align.RIGHT : left ? Align.LEFT : Align.NONE;
        }
        return a;
    }

    private static String delimiterRow(int[] width, Align[] align) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < width.length; c++) {
            int w = width[c];
            String dashes =
                    switch (align[c]) {
                        case LEFT -> ":" + "-".repeat(w - 1);
                        case RIGHT -> "-".repeat(w - 1) + ":";
                        case CENTER -> ":" + "-".repeat(w - 2) + ":";
                        case NONE -> "-".repeat(w);
                    };
            sb.append(' ').append(dashes).append(" |");
        }
        return sb.toString();
    }

    private static String dataRow(List<String> cells, int[] width, Align[] align, int ncol) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < ncol; c++) {
            String v = c < cells.size() ? cells.get(c) : "";
            sb.append(' ').append(pad(v, width[c], align[c])).append(" |");
        }
        return sb.toString();
    }

    private static String pad(String v, int w, Align align) {
        int extra = Math.max(0, w - v.length());
        return switch (align) {
            case RIGHT -> " ".repeat(extra) + v;
            case CENTER -> " ".repeat(extra / 2) + v + " ".repeat(extra - extra / 2);
            default -> v + " ".repeat(extra);
        };
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }
}
