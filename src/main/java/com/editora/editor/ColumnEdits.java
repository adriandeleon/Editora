package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, unit-tested geometry + text helpers for rectangular ("column"/"block") selection. Given the
 * full document text and a block (a line range × a column range), it computes the per-line absolute
 * offset ranges, the extracted rectangular text, and per-line insertion offsets — all clamped to each
 * line's length (shorter lines contribute an empty slice). No toolkit dependency; {@link ColumnSelection}
 * applies the results to the {@code CodeArea}.
 */
public final class ColumnEdits {

    /** An absolute {@code [start, end)} offset range within the document (one per line of a block). */
    public record Range(int start, int end) { }

    private ColumnEdits() {
    }

    /** 0-based start offset of every line (paragraph): {@code lineStarts(t)[i]} = offset of line {@code i}. */
    public static int[] lineStarts(String text) {
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

    /** Length of line {@code line} (excluding its trailing newline). */
    public static int lineLength(String text, int[] starts, int line) {
        int s = starts[line];
        int e = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
        return e - s;
    }

    /** Per-line replace ranges for the rectangle [{@code topLine},{@code bottomLine}] × [{@code leftCol},{@code rightCol}), clamped. */
    public static List<Range> rectRanges(String text, int topLine, int bottomLine, int leftCol, int rightCol) {
        int[] starts = lineStarts(text);
        List<Range> ranges = new ArrayList<>();
        int last = Math.min(bottomLine, starts.length - 1);
        for (int line = Math.max(0, topLine); line <= last; line++) {
            int len = lineLength(text, starts, line);
            int s = starts[line] + Math.min(leftCol, len);
            int e = starts[line] + Math.min(rightCol, len);
            ranges.add(new Range(s, e));
        }
        return ranges;
    }

    /** The rectangle's text: each line's clamped {@code [leftCol,rightCol)} joined by {@code \n}. */
    public static String rectText(String text, int topLine, int bottomLine, int leftCol, int rightCol) {
        int[] starts = lineStarts(text);
        StringBuilder sb = new StringBuilder();
        int last = Math.min(bottomLine, starts.length - 1);
        for (int line = Math.max(0, topLine); line <= last; line++) {
            if (line > topLine) {
                sb.append('\n');
            }
            int len = lineLength(text, starts, line);
            int s = Math.min(leftCol, len);
            int e = Math.min(rightCol, len);
            sb.append(text, starts[line] + s, starts[line] + e);
        }
        return sb.toString();
    }

    /** Per-line insertion offsets at column {@code col} (clamped) for lines [{@code topLine},{@code bottomLine}]. */
    public static List<Integer> insertOffsets(String text, int topLine, int bottomLine, int col) {
        int[] starts = lineStarts(text);
        List<Integer> offs = new ArrayList<>();
        int last = Math.min(bottomLine, starts.length - 1);
        for (int line = Math.max(0, topLine); line <= last; line++) {
            int len = lineLength(text, starts, line);
            offs.add(starts[line] + Math.min(col, len));
        }
        return offs;
    }

    /**
     * Per-line ranges for deleting the single character before column {@code col} (column backspace):
     * one {@code [col-1, col)} range per line that actually has a character there. Lines shorter than
     * {@code col} are skipped.
     */
    public static List<Range> backspaceRanges(String text, int topLine, int bottomLine, int col) {
        int[] starts = lineStarts(text);
        List<Range> ranges = new ArrayList<>();
        if (col <= 0) {
            return ranges;
        }
        int last = Math.min(bottomLine, starts.length - 1);
        for (int line = Math.max(0, topLine); line <= last; line++) {
            int len = lineLength(text, starts, line);
            if (len >= col) {
                ranges.add(new Range(starts[line] + col - 1, starts[line] + col));
            }
        }
        return ranges;
    }
}
