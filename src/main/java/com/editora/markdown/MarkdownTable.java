package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) GFM table reflow: given the text block of a pipe table, pad every column
 * to its widest cell and rebuild the delimiter row honoring alignment ({@code :--} left, {@code :-:}
 * center, {@code --:} right). {@link #blockBounds(String, int)} finds the contiguous table block around a
 * caret so {@code EditorBuffer} can select + replace it. An escaped {@code \|} is content, not a separator:
 * cells are held unescaped inside this class and re-escaped on emit, and widths are measured on the emitted
 * (escaped) form, in display columns ({@link #displayWidth}: a CJK or emoji character takes two, a combining
 * mark none).
 *
 * <p>A table is found from its delimiter row: the header is the one line above it, so a prose line with a
 * pipe that happens to sit over the table is not a row. A table inside a blockquote or a list item keeps
 * its {@code > } / indentation prefix: every operation works on the rows without it and puts it back.
 */
public final class MarkdownTable {

    /** A delimiter cell: optional leading/trailing colon around one-or-more dashes. */
    private static final Pattern DELIM_CELL = Pattern.compile("\\s*:?-+:?\\s*");

    private static final int MIN_WIDTH = 3; // room for "---" / ":-:"

    /** Column alignment, as encoded by the GFM delimiter row ({@code ---}/{@code :--}/{@code :-:}/{@code --:}). */
    public enum Align {
        NONE,
        LEFT,
        CENTER,
        RIGHT
    }

    private MarkdownTable() {}

    /** True if {@code line} looks like a table row (contains a pipe and is not blank). */
    public static boolean isRow(String line) {
        return line != null && !line.isBlank() && line.indexOf('|') >= 0;
    }

    /**
     * The {@code [start, end)} offsets of the GFM table containing {@code caret} — header row, delimiter row
     * and the rows after it — or {@code null} when the caret's line is not part of one.
     */
    public static int[] blockBounds(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        // No Math.max(0, …) clamp here: lastIndexOf searches backward from and *including* fromIndex, so
        // clamping caret==0 to 0 finds a leading '\n' and yields ls=1 > le=0 → substring(1,0) throws. A
        // negative fromIndex is exactly right — it returns -1, so ls lands on 0.
        int ls = text.lastIndexOf('\n', caret - 1) + 1;
        int le = text.indexOf('\n', caret);
        if (le < 0) {
            le = text.length();
        }
        if (!isRow(text.substring(ls, le)) || MarkdownLines.insideFence(text, ls)) {
            return null; // no pipe — or a pipe in a code block, front matter or raw HTML: not a table row
        }
        // The run of lines with a pipe around the caret, then the table inside it: it starts one line above
        // the first delimiter row. Lines of the run above that are prose that mentions a pipe.
        List<Integer> starts = new ArrayList<>();
        starts.add(ls);
        int start = ls;
        while (start > 0) {
            int prevEnd = start - 1;
            int prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1;
            if (!isRow(text.substring(prevStart, prevEnd))) {
                break;
            }
            start = prevStart;
            starts.add(0, start);
        }
        int caretLine = starts.size() - 1;
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
            starts.add(nextStart);
            end = nextEnd;
        }
        for (int i = 1; i < starts.size(); i++) {
            int lineEnd = i + 1 < starts.size() ? starts.get(i + 1) - 1 : end;
            if (isDelimiterRow(unframed(text.substring(starts.get(i), lineEnd), false))) {
                return caretLine >= i - 1 ? new int[] {starts.get(i - 1), end} : null;
            }
        }
        return null;
    }

    // ---- container prefix (blockquote, list item) ----

    private static final Pattern FRAME = Pattern.compile("^[ \\t]*(?:>[ \\t]*)*");
    private static final Pattern FRAME_ITEM = Pattern.compile("^(?:[-*+]|\\d{1,9}[.)])[ \\t]+(?=\\|)");

    /** Length of what stands in front of a row: indentation, {@code >} marks and — on the first row only — a list marker. */
    private static int frameLength(String line, boolean first) {
        Matcher m = FRAME.matcher(line);
        int n = m.find() ? m.end() : 0;
        if (first) {
            Matcher item = FRAME_ITEM.matcher(line.substring(n));
            if (item.find()) {
                n += item.end();
            }
        }
        return n;
    }

    private static String unframed(String line, boolean first) {
        return line.substring(frameLength(line, first));
    }

    /**
     * Runs {@code op} on {@code block} without its container prefix and puts the prefix back on the result:
     * the first line keeps its own (it may carry the list marker), every other line gets the second line's.
     */
    private static Nav framed(String block, int caret, java.util.function.BiFunction<String, Integer, Nav> op) {
        String[] lines = block.split("\n", -1);
        String head = lines[0].substring(0, frameLength(lines[0], true));
        String rest = lines.length > 1 ? lines[1].substring(0, frameLength(lines[1], false)) : head;
        if (head.isEmpty() && rest.isEmpty()) {
            return op.apply(block, caret);
        }
        StringBuilder body = new StringBuilder();
        int bodyCaret = 0;
        int at = 0;
        for (int i = 0; i < lines.length; i++) {
            int frame = frameLength(lines[i], i == 0);
            if (i > 0) {
                body.append('\n');
            }
            if (caret >= at && caret <= at + lines[i].length()) {
                bodyCaret = body.length() + Math.max(0, caret - at - frame);
            }
            body.append(lines[i], frame, lines[i].length());
            at += lines[i].length() + 1;
        }
        Nav n = op.apply(body.toString(), bodyCaret);
        if (n == null) {
            return null;
        }
        String[] out = n.block().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int outCaret = 0;
        int pos = 0;
        for (int i = 0; i < out.length; i++) {
            String frame = i == 0 ? head : rest;
            if (i > 0 && out[i].isEmpty()) {
                // The line a table is left on (Enter on its empty last row) continues a quote, not an indent.
                frame = rest.contains(">") ? rest.stripTrailing() + " " : "";
            }
            if (i > 0) {
                sb.append('\n');
            }
            if (n.caret() >= pos && n.caret() <= pos + out[i].length()) {
                outCaret = sb.length() + frame.length() + (n.caret() - pos);
            }
            sb.append(frame).append(out[i]);
            pos += out[i].length() + 1;
        }
        return new Nav(sb.toString(), outCaret);
    }

    /**
     * Reflows a table {@code block} (no trailing newline). Returns the input unchanged when it is not a
     * table with a delimiter row.
     */
    public static String reflow(String block) {
        if (block == null) {
            return block;
        }
        Nav n = framed(block, 0, (b, c) -> new Nav(reflowRows(b), 0));
        return n.block();
    }

    /**
     * {@link #reflow} that also says where the caret went: it stays in its cell, on the same character of
     * the cell's text. Returns {@code null} when {@code block} is not a GFM table.
     */
    public static Nav reflowAt(String block, int caretInBlock) {
        return framed(block, caretInBlock, (b, caret) -> {
            String[] lines = b.split("\n", -1);
            if (delimiterIndex(lines) < 0) {
                return null;
            }
            int li = lineIndexAt(b, caret);
            int ci = cellIndexAt(lines[li], caret - lineStartOffset(b, li));
            int into = Math.max(0, caret - cellContentOffset(b, li, ci));
            String out = reflowRows(b);
            String cell = li == delimiterIndex(lines)
                    ? ""
                    : escapePipes(splitCells(lines[li]).get(ci));
            return new Nav(out, cellContentOffset(out, li, ci) + Math.min(into, cell.length()));
        });
    }

    private static String reflowRows(String block) {
        String[] lines = block.split("\n", -1);
        if (lines.length < 2) {
            return block;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            rows.add(splitCells(line));
        }
        int delim = delimiterIndex(lines);
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
                width[c] = Math.max(width[c], cellWidth(r.get(c)));
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
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
        return framed(block, caretInBlock, (b, c) -> tabRows(b, c, forward));
    }

    private static Nav tabRows(String block, int caretInBlock, boolean forward) {
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
        if (li == delim) {
            // The delimiter row holds no text: Tab leaves it for the row below, Shift-Tab for the header.
            int next = forward ? nextNonDelim(lines, li, delim) : delim - 1;
            if (next < 0) {
                working = block + "\n" + emptyRow(columnCount(lines));
                next = lines.length;
            }
            targetLine = next;
            targetCell = forward ? 0 : Math.max(0, cellCount(lines[next]) - 1);
        } else if (forward && beforeFirstCell(lines[li], caretInBlock - lineStart)) {
            targetCell = 0; // the caret stood in front of the row's opening pipe
        } else if (forward) {
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
                    return new Nav(
                            reflowRows(block), cellContentOffset(reflowRows(block), 0, 0)); // already at the first cell
                }
                targetLine = prev;
                targetCell = Math.max(0, cellCount(lines[prev]) - 1);
            }
        }
        String reflowed = reflowRows(working);
        return new Nav(reflowed, cellContentOffset(reflowed, targetLine, targetCell));
    }

    /**
     * Enter within a table. On the last row it appends an empty row and puts the caret in its first cell —
     * unless that last row is already empty, which is how a table is left: the row goes and the caret lands
     * on the blank line after the table. On any other row the caret moves to the same cell of the row
     * below; a cell is never split in two. Returns {@code null} when the block is not a GFM table.
     */
    public static Nav enter(String block, int caretInBlock) {
        return framed(block, caretInBlock, MarkdownTable::enterRows);
    }

    private static Nav enterRows(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        if (li != lines.length - 1) {
            int next = nextNonDelim(lines, li, delim);
            int ci = li == delim ? 0 : cellIndexAt(lines[li], caretInBlock - lineStartOffset(block, li));
            String reflowed = reflowRows(block);
            return new Nav(reflowed, cellContentOffset(reflowed, next, Math.min(ci, cellCount(lines[next]) - 1)));
        }
        if (li > delim && splitCells(lines[li]).stream().allMatch(String::isEmpty)) {
            String kept = reflowRows(block.substring(0, block.lastIndexOf('\n')));
            return new Nav(kept + "\n", kept.length() + 1);
        }
        String working = block + "\n" + emptyRow(columnCount(lines));
        String reflowed = reflowRows(working);
        int newLine = reflowed.split("\n", -1).length - 1;
        return new Nav(reflowed, cellContentOffset(reflowed, newLine, 0));
    }

    /** The largest table dimension {@link #parseSize} accepts, to keep a typo like {@code 999x999} sane. */
    public static final int MAX_SIZE = 50;

    private static final Pattern SIZE = Pattern.compile("\\s*(\\d+)\\s*[x×X]\\s*(\\d+)\\s*");

    /**
     * Parses an {@code "RxC"} table-size string (e.g. {@code "4x4"}, {@code "3 X 2"}, {@code "2×5"}) into
     * {@code int[]{rows, cols}} (rows includes the header), each clamped to {@code [1, }{@link #MAX_SIZE}{@code ]}.
     * Returns {@code null} when the input is not two {@code x}-separated positive integers.
     */
    public static int[] parseSize(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = SIZE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        try {
            int r = Integer.parseInt(m.group(1));
            int c = Integer.parseInt(m.group(2));
            if (r < 1 || c < 1) {
                return null;
            }
            return new int[] {Math.min(r, MAX_SIZE), Math.min(c, MAX_SIZE)};
        } catch (NumberFormatException e) {
            return null; // overflow on an absurdly long digit run
        }
    }

    /**
     * Generates a fresh, aligned GFM table skeleton with {@code rowsTotal} rows (the first is the header) and
     * {@code cols} columns: a {@code Column 1 | Column 2 | …} header, a delimiter row, then empty body rows.
     * The {@link Nav} caret lands in the first header cell. {@code rowsTotal}/{@code cols} are clamped to ≥1.
     */
    public static Nav generate(int rowsTotal, int cols) {
        return generate(rowsTotal, cols, i -> "Column " + i);
    }

    /** {@link #generate(int, int)} with the header text of column {@code n} (1-based) supplied by {@code header}. */
    public static Nav generate(int rowsTotal, int cols, java.util.function.IntFunction<String> header) {
        int c = Math.max(1, cols);
        int body = Math.max(0, rowsTotal - 1);
        List<List<String>> grid = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        List<String> delim = new ArrayList<>();
        for (int i = 0; i < c; i++) {
            cells.add(header.apply(i + 1));
            delim.add("---");
        }
        grid.add(cells);
        grid.add(delim);
        for (int r = 0; r < body; r++) {
            List<String> row = new ArrayList<>();
            for (int i = 0; i < c; i++) {
                row.add("");
            }
            grid.add(row);
        }
        String reflowed = reflowRows(joinRows(grid));
        return new Nav(reflowed, cellContentOffset(reflowed, 0, 0));
    }

    /**
     * Inserts an empty row below the caret's row (or as the first body row when the caret is on the header /
     * delimiter), reflows, and puts the caret in the new row's first cell. Returns {@code null} when the
     * block is not a GFM table.
     */
    public static Nav addRow(String block, int caretInBlock) {
        return framed(block, caretInBlock, MarkdownTable::addRowRows);
    }

    private static Nav addRowRows(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        int ncol = columnCount(lines);
        List<List<String>> rows = rowsOf(block);
        int insertAt = (li <= delim) ? delim + 1 : li + 1;
        rows.add(insertAt, emptyCells(ncol, ""));
        String reflowed = reflowRows(joinRows(rows));
        return new Nav(reflowed, cellContentOffset(reflowed, insertAt, 0));
    }

    /**
     * Deletes the caret's data row (reflows, caret to the same cell in the surviving neighbour). Returns
     * {@code null} when not a GFM table or when the caret is on the header or delimiter row (those can't be
     * deleted).
     */
    public static Nav deleteRow(String block, int caretInBlock) {
        return framed(block, caretInBlock, MarkdownTable::deleteRowRows);
    }

    private static Nav deleteRowRows(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        if (li == 0 || li == delim) {
            return null; // never delete the header or the delimiter row
        }
        int ci = cellIndexAt(lines[li], caretInBlock - lineStartOffset(block, li));
        List<List<String>> rows = rowsOf(block);
        rows.remove(li);
        String reflowed = reflowRows(joinRows(rows));
        // Clamp to the last surviving row — but never onto the delimiter row, which is where deleting the
        // only data row landed the caret: typing there mangles the `---` line and the table stops parsing.
        int newLine = Math.min(li, rows.size() - 1);
        if (newLine == delim) {
            newLine = delim - 1; // fall back to the header
        }
        return new Nav(reflowed, cellContentOffset(reflowed, newLine, ci));
    }

    /**
     * Inserts an empty column to the right of the caret's column in every row, reflows, and puts the caret in
     * the new cell. Returns {@code null} when not a GFM table.
     */
    public static Nav addColumn(String block, int caretInBlock) {
        return framed(block, caretInBlock, MarkdownTable::addColumnRows);
    }

    private static Nav addColumnRows(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        int ci = cellIndexAt(lines[li], caretInBlock - lineStartOffset(block, li));
        int ncol = columnCount(lines);
        int insertAt = Math.min(ci + 1, ncol);
        List<List<String>> rows = rowsOf(block);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String fill = (i == delim) ? "---" : "";
            while (r.size() < ncol) {
                r.add(fill);
            }
            r.add(Math.min(insertAt, r.size()), fill);
        }
        String reflowed = reflowRows(joinRows(rows));
        return new Nav(reflowed, cellContentOffset(reflowed, li, insertAt));
    }

    /**
     * Deletes the caret's column from every row, reflows, and keeps the caret in the same row. Returns
     * {@code null} when not a GFM table or when there is only one column left.
     */
    public static Nav deleteColumn(String block, int caretInBlock) {
        return framed(block, caretInBlock, MarkdownTable::deleteColumnRows);
    }

    private static Nav deleteColumnRows(String block, int caretInBlock) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int ncol = columnCount(lines);
        if (ncol <= 1) {
            return null; // a table needs at least one column
        }
        int li = lineIndexAt(block, caretInBlock);
        int ci = cellIndexAt(lines[li], caretInBlock - lineStartOffset(block, li));
        List<List<String>> rows = rowsOf(block);
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String fill = (i == delim) ? "---" : "";
            while (r.size() < ncol) {
                r.add(fill);
            }
            if (ci < r.size()) {
                r.remove(ci);
            }
        }
        String reflowed = reflowRows(joinRows(rows));
        int newCell = Math.max(0, Math.min(ci, ncol - 2));
        return new Nav(reflowed, cellContentOffset(reflowed, li, newCell));
    }

    /**
     * Sets the alignment of the caret's column (rewrites that delimiter cell to {@code ---}/{@code :--}/
     * {@code :-:}/{@code --:}), reflows, and keeps the caret in place. Returns {@code null} when not a GFM
     * table.
     */
    public static Nav setAlignment(String block, int caretInBlock, Align align) {
        return framed(block, caretInBlock, (b, c) -> alignRows(b, c, align));
    }

    private static Nav alignRows(String block, int caretInBlock, Align align) {
        String[] lines = block.split("\n", -1);
        int delim = delimiterIndex(lines);
        if (delim < 0) {
            return null;
        }
        int li = lineIndexAt(block, caretInBlock);
        int ci = cellIndexAt(lines[li], caretInBlock - lineStartOffset(block, li));
        int ncol = columnCount(lines);
        List<List<String>> rows = rowsOf(block);
        List<String> d = rows.get(delim);
        while (d.size() < ncol) {
            d.add("---");
        }
        d.set(Math.min(ci, d.size() - 1), delimCellFor(align));
        String reflowed = reflowRows(joinRows(rows));
        return new Nav(reflowed, cellContentOffset(reflowed, li, ci));
    }

    private static List<String> emptyCells(int ncol, String fill) {
        List<String> r = new ArrayList<>();
        for (int c = 0; c < Math.max(1, ncol); c++) {
            r.add(fill);
        }
        return r;
    }

    private static List<List<String>> rowsOf(String block) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : block.split("\n", -1)) {
            rows.add(splitCells(line));
        }
        return rows;
    }

    /** Re-joins parsed rows into a raw pipe block (re-aligned by a following {@link #reflow}). */
    private static String joinRows(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append('|');
            for (String c : rows.get(i)) {
                // Escaped again because this block is re-parsed by the following reflow.
                sb.append(' ').append(escapePipes(c)).append(" |");
            }
        }
        return sb.toString();
    }

    private static String delimCellFor(Align a) {
        return switch (a) {
            case LEFT -> ":--";
            case CENTER -> ":-:";
            case RIGHT -> "--:";
            case NONE -> "---";
        };
    }

    /**
     * Converts CSV/TSV {@code csv} into an aligned GFM table (the first row becomes the header), or
     * {@code null} when there are no rows. The delimiter is auto-detected (comma / semicolon / tab);
     * RFC-4180 quoting is honored ({@code ""} → {@code "}, and delimiters/newlines inside quotes). A pipe
     * in a cell is escaped (`\|`) and an embedded newline becomes a space, so it stays a single GFM cell.
     */
    public static String fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        if (csv.charAt(0) == '\uFEFF') {
            csv = csv.substring(1); // a byte-order mark is not part of the first header
        }
        char delim = detectDelimiter(csv);
        List<List<String>> rows = parseCsv(csv, delim);
        while (!rows.isEmpty() && rows.get(rows.size() - 1).stream().allMatch(String::isBlank)) {
            rows.remove(rows.size() - 1); // drop trailing blank records
        }
        if (rows.isEmpty()) {
            return null;
        }
        int ncol = rows.stream().mapToInt(List::size).max().orElse(1);
        // Build display cells (pipes NOT yet escaped — kept literal for width measurement).
        List<List<String>> data = new ArrayList<>();
        for (List<String> row : rows) {
            data.add(csvCells(row, ncol));
        }
        Align[] align = new Align[ncol];
        int[] width = new int[ncol];
        for (int c = 0; c < ncol; c++) {
            align[c] = Align.NONE;
            width[c] = MIN_WIDTH;
            for (List<String> r : data) {
                width[c] = Math.max(width[c], cellWidth(r.get(c)));
            }
        }
        // Emit header, delimiter, then body. dataRow escapes each cell's literal pipes before padding, so
        // `\|` cells stay intact and still fill their column.
        StringBuilder out = new StringBuilder();
        out.append(dataRow(data.get(0), width, align, ncol));
        out.append('\n').append(delimiterRow(width, align));
        for (int i = 1; i < data.size(); i++) {
            out.append('\n').append(dataRow(data.get(i), width, align, ncol));
        }
        return out.toString();
    }

    /** Pads {@code row} to {@code ncol} and flattens newlines; pipes stay literal (escaped later, on emit). */
    private static List<String> csvCells(List<String> row, int ncol) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ncol; i++) {
            String v = i < row.size() ? row.get(i) : "";
            out.add(v.replace("\r", " ").replace("\n", " ").strip());
        }
        return out;
    }

    /**
     * Converts the GFM table {@code block} (the caret's table) to comma-delimited CSV with RFC-4180 quoting
     * (a cell containing a comma, quote or newline is wrapped in {@code "…"} with internal quotes doubled).
     * The delimiter row is skipped and {@code \|} unescaped to {@code |}. Returns {@code null} when not a table.
     */
    public static String toCsv(String block) {
        if (block == null) {
            return null;
        }
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = unframed(lines[i], i == 0);
        }
        if (delimiterIndex(lines) < 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (!isRow(line) || isDelimiterRow(line)) {
                continue;
            }
            if (!first) {
                out.append('\n');
            }
            first = false;
            List<String> cells = splitCells(line);
            for (int c = 0; c < cells.size(); c++) {
                if (c > 0) {
                    out.append(',');
                }
                out.append(csvQuote(cells.get(c)));
            }
        }
        return out.toString();
    }

    private static String csvQuote(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** Picks comma / semicolon / tab by counting unquoted occurrences in the first line (comma default). */
    private static char detectDelimiter(String csv) {
        int nl = csv.indexOf('\n');
        String head = nl < 0 ? csv : csv.substring(0, nl);
        int comma = 0;
        int semi = 0;
        int tab = 0;
        boolean q = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '"') {
                q = !q;
            } else if (!q) {
                if (c == ',') {
                    comma++;
                } else if (c == ';') {
                    semi++;
                } else if (c == '\t') {
                    tab++;
                }
            }
        }
        if (tab > 0 && tab >= comma && tab >= semi) {
            return '\t';
        }
        if (semi > comma) {
            return ';';
        }
        return ',';
    }

    /** RFC-4180 record/field parser: quoted fields ({@code ""} escape), delimiters/newlines inside quotes. */
    private static List<List<String>> parseCsv(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean inQuotes = false;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"' && f.toString().isBlank()) {
                f.setLength(0); // `a, "b,c"`: blanks between the delimiter and the quote are not content
                inQuotes = true;
                pending = true;
            } else if (c == delim) {
                row.add(f.toString());
                f.setLength(0);
                pending = true;
            } else if (c == '\n') {
                row.add(f.toString());
                rows.add(row);
                row = new ArrayList<>();
                f.setLength(0);
                pending = false;
            } else if (c != '\r') {
                f.append(c);
                pending = true;
            }
        }
        if (pending || f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** The delimiter row: never the first line — that is the header, even when its cells are dashes. */
    private static int delimiterIndex(String[] lines) {
        for (int i = 1; i < lines.length; i++) {
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
            if (line.charAt(i) == '|' && !escapedPipeAt(line, i)) {
                pipesBefore++;
            }
        }
        int ci = leadingPipe ? pipesBefore - 1 : pipesBefore;
        return Math.max(0, Math.min(ci, Math.max(0, cellCount(line) - 1)));
    }

    /** True when {@code col} is in front of the row's opening pipe. */
    private static boolean beforeFirstCell(String line, int col) {
        int pipe = line.indexOf('|');
        return line.strip().startsWith("|") && col <= pipe;
    }

    /** Absolute offset (within the reflowed block) of the start of cell {@code cell} on line {@code line}. */
    private static int cellContentOffset(String block, int line, int cell) {
        int lineStart = lineStartOffset(block, line);
        String text = lineAt(block, line);
        int pipes = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '|' && !escapedPipeAt(text, i)) {
                if (pipes == cell) {
                    int content = i + 1;
                    while (content < text.length() && text.charAt(content) == ' ') {
                        content++;
                    }
                    if (content >= text.length() || text.charAt(content) == '|') {
                        content = Math.min(i + 2, content); // an empty cell: one blank in, not against its closing pipe
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

    /**
     * The cells of one row, with {@code \|} treated as an escaped pipe (a cell's content, not a separator)
     * and unescaped to a literal {@code |}. Cell values are held unescaped everywhere inside this class;
     * {@link #escapePipes} puts the backslashes back on emit.
     *
     * <p>Splitting on every {@code |} instead — as this did — cut such a cell in two and invented a column,
     * so a table produced by {@link #fromCsv} (which emits {@code \|} by design) was corrupted by the very
     * next reflow, i.e. by pressing Tab in it.
     */
    private static List<String> splitCells(String line) {
        String t = line.strip();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.endsWith("\\|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().strip());
        return cells;
    }

    /** Re-escapes a cell's literal pipes for emission into a row. Inverse of {@link #splitCells}' unescape. */
    private static String escapePipes(String cell) {
        return cell.indexOf('|') < 0 ? cell : cell.replace("|", "\\|");
    }

    /** True when the {@code |} at {@code i} is escaped ({@code \|}) — content, not a cell separator. */
    private static boolean escapedPipeAt(String line, int i) {
        return i > 0 && line.charAt(i - 1) == '\\';
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

    /** Emits one row. Cells are escaped *before* padding so a {@code \|} cell still fills its column. */
    private static String dataRow(List<String> cells, int[] width, Align[] align, int ncol) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < ncol; c++) {
            String v = c < cells.size() ? cells.get(c) : "";
            sb.append(' ').append(pad(escapePipes(v), width[c], align[c])).append(" |");
        }
        return sb.toString();
    }

    /** Display width of a cell as it will be emitted — i.e. with its pipes escaped. */
    private static int cellWidth(String cell) {
        return displayWidth(escapePipes(cell));
    }

    /**
     * The number of monospace columns {@code s} takes: two for East Asian wide and fullwidth characters and
     * for emoji, none for combining marks, joiners and variation selectors, one for everything else.
     * {@code String.length()} counts UTF-16 units, which left every row with CJK text out of line.
     */
    public static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT
                    || (cp >= 0xFE00 && cp <= 0xFE0F)) {
                continue;
            }
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    private static String pad(String v, int w, Align align) {
        int extra = Math.max(0, w - displayWidth(v));
        return switch (align) {
            case RIGHT -> " ".repeat(extra) + v;
            case CENTER -> " ".repeat(extra / 2) + v + " ".repeat(extra - extra / 2);
            default -> v + " ".repeat(extra);
        };
    }
}
