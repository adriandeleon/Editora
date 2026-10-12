package com.editora.markdown;

import java.util.function.UnaryOperator;

/**
 * The shared mechanics of a line-wise Markdown command (bullet, task, heading): which whole lines a
 * selection covers, and — once the command has rewritten them — where the caret or selection belongs in
 * the result. Pure.
 *
 * <p>Two rules live here because every command got them wrong the same way:
 *
 * <ul>
 *   <li>A selection that ends at the very start of a line (Shift+Down from a line start, a triple click)
 *       does not include that line.</li>
 *   <li>The caret and the selection stay on the text they were on, shifted by whatever prefix was added or
 *       removed in front of them. The commands used to answer with "select the rewritten block", so a
 *       caret-only command left the whole line selected and the next key typed replaced it.</li>
 * </ul>
 */
final class LineEdits {

    private LineEdits() {}

    /** The {@code [start, end)} of the whole lines covered by {@code [selStart, selEnd)}. */
    static int[] block(String text, int selStart, int selEnd) {
        int s = Math.max(0, Math.min(selStart, selEnd));
        int e = Math.min(text.length(), Math.max(selStart, selEnd));
        if (e > s && text.charAt(e - 1) == '\n') {
            e--; // the selection stops at the start of the next line: that line is not part of it
        }
        int nl = text.lastIndexOf('\n', s - 1);
        int start = nl < 0 ? 0 : nl + 1;
        int end = text.indexOf('\n', e);
        return new int[] {start, end < 0 ? text.length() : end};
    }

    /**
     * Rewrites the lines of {@code [blockStart, blockEnd)} with {@code rewrite} (same number of entries back;
     * a {@code null} entry deletes that line) and maps {@code [selStart, selEnd]} into the result. Returns
     * {@code null} when nothing changes.
     */
    static MarkdownEdit rewrite(
            String text, int blockStart, int blockEnd, int selStart, int selEnd, UnaryOperator<String[]> rewrite) {
        String[] before = text.substring(blockStart, blockEnd).split("\n", -1);
        String[] after = rewrite.apply(before.clone());
        StringBuilder out = new StringBuilder();
        int[] oldStart = new int[before.length];
        int[] newStart = new int[before.length];
        int at = blockStart;
        boolean first = true;
        for (int i = 0; i < before.length; i++) {
            oldStart[i] = at;
            at += before[i].length() + 1;
            if (after[i] == null) {
                newStart[i] = -1;
                continue;
            }
            if (!first) {
                out.append('\n');
            }
            first = false;
            newStart[i] = blockStart + out.length();
            out.append(after[i]);
        }
        String replacement = out.toString();
        if (replacement.equals(text.substring(blockStart, blockEnd))) {
            return null;
        }
        int delta = replacement.length() - (blockEnd - blockStart);
        // A selection that started in front of a line's text still does (it takes the new prefix in); a lone
        // caret there moves behind the prefix, where typing continues the text.
        int low = Math.min(selStart, selEnd);
        boolean range = selStart != selEnd;
        int a = map(selStart, range && selStart == low, blockStart, blockEnd, delta, before, after, oldStart, newStart);
        int b = map(selEnd, range && selEnd == low, blockStart, blockEnd, delta, before, after, oldStart, newStart);
        return new MarkdownEdit(blockStart, blockEnd, replacement, a, b);
    }

    private static int map(
            int offset,
            boolean stayLeft,
            int blockStart,
            int blockEnd,
            int delta,
            String[] before,
            String[] after,
            int[] oldStart,
            int[] newStart) {
        if (offset < blockStart) {
            return offset;
        }
        if (offset > blockEnd) {
            return offset + delta;
        }
        int i = before.length - 1;
        while (i > 0 && oldStart[i] > offset) {
            i--;
        }
        if (after[i] == null) {
            // The line is gone: the end of the nearest line that is still there.
            for (int k = i - 1; k >= 0; k--) {
                if (after[k] != null) {
                    return newStart[k] + after[k].length();
                }
            }
            return blockStart;
        }
        return newStart[i] + mapColumn(offset - oldStart[i], before[i], after[i], stayLeft);
    }

    /** A column of {@code before} in {@code after}: the two share their tail, and differ in a prefix. */
    static int mapColumn(int col, String before, String after) {
        return mapColumn(col, before, after, false);
    }

    private static int mapColumn(int col, String before, String after, boolean stayLeft) {
        int common = 0;
        int max = Math.min(before.length(), after.length());
        while (common < max
                && before.charAt(before.length() - 1 - common) == after.charAt(after.length() - 1 - common)) {
            common++;
        }
        int oldPrefix = before.length() - common;
        int newPrefix = after.length() - common;
        if (col > oldPrefix || (col == oldPrefix && !stayLeft)) {
            return newPrefix + (col - oldPrefix);
        }
        return Math.min(col, newPrefix);
    }
}
