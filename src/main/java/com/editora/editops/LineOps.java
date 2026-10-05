package com.editora.editops;

/**
 * Pure, unit-tested line operations — <b>duplicate line</b>, <b>move line up</b>, and <b>move line
 * down</b> — computing a minimal {@link Edit} (a replacement span + the resulting caret) from the current
 * text + caret, or {@code null} for a no-op. No toolkit dependency; the controller applies the
 * {@code Edit} to the active {@code CodeArea} (mirrors {@link Transposer}). All operate on the caret's
 * own line and preserve the caret column, following the line as it moves/duplicates. With a selection the
 * {@code …Lines} variants act on every line it touches as one block and keep it selected.
 */
public final class LineOps {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) {}

    /** Replace {@code [from, to)} with {@code replacement}, then select {@code [selStart, selEnd)}. */
    public record BlockEdit(int from, int to, String replacement, int selStart, int selEnd) {}

    private LineOps() {}

    /** {@code {start, end}} of the whole lines the selection touches (end excludes the last newline). A
     *  selection ending exactly at a line start doesn't include that trailing line. */
    private static int[] blockBounds(String text, int selStart, int selEnd) {
        int a = Math.clamp(Math.min(selStart, selEnd), 0, text.length());
        int b = Math.clamp(Math.max(selStart, selEnd), 0, text.length());
        int effEnd = b > a && text.charAt(b - 1) == '\n' ? b - 1 : b;
        return new int[] {lineStart(text, a), lineEnd(text, effEnd)};
    }

    /** Duplicate the selection's lines below themselves; the selection follows onto the copy. */
    public static BlockEdit duplicateLines(String text, int selStart, int selEnd) {
        int[] block = blockBounds(text, selStart, selEnd);
        String lines = text.substring(block[0], block[1]);
        int shift = lines.length() + 1;
        return new BlockEdit(block[1], block[1], "\n" + lines, selStart + shift, selEnd + shift);
    }

    /** Move the selection's lines above the line before them, still selected. No-op at the top. */
    public static BlockEdit moveLinesUp(String text, int selStart, int selEnd) {
        int[] block = blockBounds(text, selStart, selEnd);
        if (block[0] == 0) {
            return null;
        }
        int prevStart = lineStart(text, block[0] - 1);
        String prevLine = text.substring(prevStart, block[0] - 1);
        String repl = text.substring(block[0], block[1]) + "\n" + prevLine;
        int shift = prevLine.length() + 1;
        return new BlockEdit(prevStart, block[1], repl, selStart - shift, selEnd - shift);
    }

    /** Move the selection's lines below the line after them, still selected. No-op at the bottom. */
    public static BlockEdit moveLinesDown(String text, int selStart, int selEnd) {
        int[] block = blockBounds(text, selStart, selEnd);
        if (block[1] >= text.length()) {
            return null;
        }
        int nextEnd = lineEnd(text, block[1] + 1);
        String nextLine = text.substring(block[1] + 1, nextEnd);
        String repl = nextLine + "\n" + text.substring(block[0], block[1]);
        int shift = nextLine.length() + 1;
        // A selection that took the block's trailing newline has none to take once the block is last.
        return new BlockEdit(
                block[0], nextEnd, repl, Math.min(selStart + shift, nextEnd), Math.min(selEnd + shift, nextEnd));
    }

    /** Start of the line containing {@code pos} (index just after the previous newline, or 0). */
    private static int lineStart(String text, int pos) {
        int i = pos;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** End of the line containing {@code pos} (index of the next newline at/after {@code pos}, or length). */
    private static int lineEnd(String text, int pos) {
        int i = pos;
        int n = text.length();
        while (i < n && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /**
     * Duplicate the caret's line, inserting the copy on the line below; the caret follows to the same
     * column on the duplicate (VS Code / Sublime "duplicate line down"). Never a no-op.
     */
    public static Edit duplicateLine(String text, int caret) {
        int curStart = lineStart(text, caret);
        int curEnd = lineEnd(text, caret);
        String line = text.substring(curStart, curEnd);
        int column = caret - curStart;
        // Pure insertion at end of the current line: a newline + a copy of the line.
        int newCaret = curEnd + 1 + column; // same column, on the duplicated line below
        return new Edit(curEnd, curEnd, "\n" + line, newCaret);
    }

    /**
     * Swap the caret's line with the previous one; the caret follows the moved line up, keeping its
     * column. No-op on the first line.
     */
    public static Edit moveLineUp(String text, int caret) {
        int curStart = lineStart(text, caret);
        if (curStart == 0) {
            return null; // already the first line
        }
        int prevStart = lineStart(text, curStart - 1);
        int curEnd = lineEnd(text, caret);
        String prevLine = text.substring(prevStart, curStart - 1); // excludes its trailing newline
        String curLine = text.substring(curStart, curEnd);
        String repl = curLine + "\n" + prevLine; // same length as [prevStart, curEnd)
        int newCaret = prevStart + (caret - curStart); // same column, line now starts at prevStart
        return new Edit(prevStart, curEnd, repl, newCaret);
    }

    /**
     * Swap the caret's line with the next one; the caret follows the moved line down, keeping its
     * column. No-op on the last line.
     */
    public static Edit moveLineDown(String text, int caret) {
        int n = text.length();
        int curStart = lineStart(text, caret);
        int curEnd = lineEnd(text, caret);
        if (curEnd >= n) {
            return null; // last line: nothing below to swap with
        }
        int nextStart = curEnd + 1;
        int nextEnd = lineEnd(text, nextStart);
        String curLine = text.substring(curStart, curEnd);
        String nextLine = text.substring(nextStart, nextEnd);
        String repl = nextLine + "\n" + curLine; // same length as [curStart, nextEnd)
        int newCaret = curStart + nextLine.length() + 1 + (caret - curStart); // same column, line moved down
        return new Edit(curStart, nextEnd, repl, newCaret);
    }
}
