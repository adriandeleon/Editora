package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

/**
 * The Emacs rectangle commands: operations on the <em>columns</em> between point and mark rather than
 * on the linear span between them.
 *
 * <p>Pure and toolkit-free (mirroring {@link EmacsEdits} / {@link Transposer}). A rectangle edit touches
 * a segment of each of several lines, which is not one contiguous span — so every operation here returns
 * an {@link Edit} that rewrites the <em>whole block</em> of affected lines. That keeps each command a
 * single {@code replaceText}, and therefore a single undo step.
 *
 * <p>Distinct from the Alt+drag box selection of the multi-caret add-on, which leaves behind independent
 * carets rather than a rectangle; these commands read the ordinary mark-based selection.
 *
 * <p><b>Columns are character columns, not display columns.</b> A tab counts as one column, so a
 * rectangle over tab-indented text does not line up with what is on screen. Emacs expands tabs here;
 * matching that also means deciding whether to convert tabs to spaces on edit, so it is deferred.
 */
public final class Rectangle {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) {}

    /**
     * A rectangle: the lines {@code [topLine, bottomLine]} and the half-open column range
     * {@code [leftCol, rightCol)}. A zero-width rectangle ({@code leftCol == rightCol}) is meaningful —
     * it is the insertion point {@code open-rectangle} and {@code string-rectangle} use.
     */
    public record Bounds(int topLine, int bottomLine, int leftCol, int rightCol) {
        public int width() {
            return rightCol - leftCol;
        }

        public int lineCount() {
            return bottomLine - topLine + 1;
        }
    }

    private Rectangle() {}

    // --- geometry --------------------------------------------------------------------------------

    /** Start offset of every line in {@code text} (always at least one entry). */
    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
    }

    /** End offset (exclusive, newline not included) of the line beginning at {@code start}. */
    private static int lineEnd(String text, int start) {
        int i = start;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int lineOf(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * The rectangle delimited by two offsets (point and mark, in either order): the lines they fall on,
     * and the smaller/larger of their two columns.
     */
    public static Bounds bounds(String text, int start, int end) {
        int a = Math.max(0, Math.min(start, text.length()));
        int b = Math.max(0, Math.min(end, text.length()));
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int[] starts = lineStarts(text);
        int topLine = lineOf(starts, lo);
        int bottomLine = lineOf(starts, hi);
        int colA = lo - starts[topLine];
        int colB = hi - starts[bottomLine];
        return new Bounds(topLine, bottomLine, Math.min(colA, colB), Math.max(colA, colB));
    }

    // --- read ------------------------------------------------------------------------------------

    /**
     * The rectangle's contents, one string per line, each exactly {@link Bounds#width()} characters —
     * lines too short to reach the rectangle are padded with spaces, as Emacs does, so the shape is
     * preserved for a later yank.
     */
    public static List<String> extract(String text, Bounds b) {
        int[] starts = lineStarts(text);
        List<String> out = new ArrayList<>(b.lineCount());
        for (int line = b.topLine; line <= b.bottomLine && line < starts.length; line++) {
            out.add(pad(segment(text, starts, line, b.leftCol(), b.rightCol()), b.width()));
        }
        return out;
    }

    /** The characters of {@code line} within {@code [from, to)}, clipped to the line's actual length. */
    private static String segment(String text, int[] starts, int line, int from, int to) {
        int s = starts[line];
        int e = lineEnd(text, s);
        int len = e - s;
        int f = Math.min(from, len);
        int t = Math.min(to, len);
        return f >= t ? "" : text.substring(s + f, s + t);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    // --- edits -----------------------------------------------------------------------------------

    /**
     * Rewrites each line of the rectangle through {@code perLine}, which receives the line's full text
     * and returns its replacement, and packages the result as one block replacement.
     */
    private static Edit rewrite(String text, Bounds b, java.util.function.UnaryOperator<String> perLine) {
        int[] starts = lineStarts(text);
        if (b.topLine >= starts.length) {
            return null;
        }
        int last = Math.min(b.bottomLine, starts.length - 1);
        int from = starts[b.topLine];
        int to = lineEnd(text, starts[last]);
        StringBuilder sb = new StringBuilder();
        for (int line = b.topLine; line <= last; line++) {
            if (line > b.topLine) {
                sb.append('\n');
            }
            int s = starts[line];
            sb.append(perLine.apply(text.substring(s, lineEnd(text, s))));
        }
        String replacement = sb.toString();
        if (replacement.contentEquals(text.subSequence(from, to))) {
            return null; // no-op
        }
        return new Edit(from, to, replacement, from + Math.min(b.leftCol(), replacement.length()));
    }

    /** Emacs {@code delete-rectangle} (`C-x r d`): remove the columns, closing the gap. */
    public static Edit delete(String text, Bounds b) {
        if (b.width() == 0) {
            return null;
        }
        return rewrite(text, b, line -> {
            if (line.length() <= b.leftCol()) {
                return line; // line does not reach the rectangle
            }
            return line.substring(0, b.leftCol()) + line.substring(Math.min(b.rightCol(), line.length()));
        });
    }

    /**
     * Emacs {@code clear-rectangle} (`C-x r c`): overwrite the columns with spaces, keeping the width.
     * Lines too short are padded out first, matching Emacs' forced {@code move-to-column}.
     */
    public static Edit clear(String text, Bounds b) {
        if (b.width() == 0) {
            return null;
        }
        return rewrite(text, b, line -> {
            String head = pad(line.length() > b.leftCol() ? line.substring(0, b.leftCol()) : line, b.leftCol());
            String tail = line.length() > b.rightCol() ? line.substring(b.rightCol()) : "";
            return head + " ".repeat(b.width()) + tail;
        });
    }

    /** Emacs {@code open-rectangle} (`C-x r o`): insert blanks, shifting the existing text right. */
    public static Edit open(String text, Bounds b) {
        if (b.width() == 0) {
            return null;
        }
        return rewrite(text, b, line -> {
            String head = pad(line.length() > b.leftCol() ? line.substring(0, b.leftCol()) : line, b.leftCol());
            String tail = line.length() > b.leftCol() ? line.substring(b.leftCol()) : "";
            return head + " ".repeat(b.width()) + tail;
        });
    }

    /**
     * Emacs {@code string-rectangle} (`C-x r t`): replace each line's rectangle segment with
     * {@code s} — with a zero-width rectangle this inserts {@code s} at that column on every line.
     */
    public static Edit replace(String text, Bounds b, String s) {
        String insert = s == null ? "" : s;
        return rewrite(text, b, line -> {
            String head = pad(line.length() > b.leftCol() ? line.substring(0, b.leftCol()) : line, b.leftCol());
            String tail = line.length() > b.rightCol() ? line.substring(b.rightCol()) : "";
            return head + insert + tail;
        });
    }

    /**
     * Emacs {@code rectangle-number-lines} (`C-x r N`): insert consecutive numbers down the left edge,
     * right-aligned to the widest of them and followed by a space.
     */
    public static Edit numberLines(String text, Bounds b, int firstNumber) {
        int width = Math.max(
                String.valueOf(firstNumber).length(),
                String.valueOf(firstNumber + b.lineCount() - 1).length());
        int[] n = {firstNumber};
        return rewrite(text, b, line -> {
            String head = pad(line.length() > b.leftCol() ? line.substring(0, b.leftCol()) : line, b.leftCol());
            String tail = line.length() > b.leftCol() ? line.substring(b.leftCol()) : "";
            return head + String.format("%" + width + "d ", n[0]++) + tail;
        });
    }

    /**
     * Emacs {@code yank-rectangle} (`C-x r y`): insert {@code lines} as a rectangle with its top-left
     * corner at the caret, pushing existing text right on each line. Lines are appended when the
     * rectangle extends past the end of the document, and short lines are padded out to the caret column.
     */
    public static Edit yank(String text, int caret, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int[] starts = lineStarts(text);
        int caretPos = Math.max(0, Math.min(caret, text.length()));
        int topLine = lineOf(starts, caretPos);
        int col = caretPos - starts[topLine];

        int from = starts[topLine];
        int lastExisting = Math.min(topLine + lines.size() - 1, starts.length - 1);
        int to = lineEnd(text, starts[lastExisting]);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            int line = topLine + i;
            String existing = line < starts.length ? text.substring(starts[line], lineEnd(text, starts[line])) : "";
            String head = pad(existing.length() > col ? existing.substring(0, col) : existing, col);
            String tail = existing.length() > col ? existing.substring(col) : "";
            sb.append(head).append(lines.get(i)).append(tail);
        }
        return new Edit(from, to, sb.toString(), caretPos);
    }
}
