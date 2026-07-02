package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * "Rainbow" per-column CSV highlighting: assigns each column a cycling color so field boundaries are easy
 * to read (the VS Code Rainbow CSV idiom). Computed directly in Java — TextMate can't count columns — as
 * {@link StyleSpans} over the whole document, used by {@link EditorBuffer#applyHighlighting} for CSV buffers
 * when rainbow is on (in place of the {@code source.csv} grammar).
 *
 * <p>The pure {@link #segments} (no toolkit) is unit-tested; {@link #buildSpans} is the thin RichTextFX
 * wrapper that maps each segment's style code to a CSS class ({@code csv-col-<k>} / {@code csv-delimiter}).
 */
final class CsvRainbow {

    /** Number of distinct column colors before the cycle repeats (matches the {@code .text.csv-col-N} CSS). */
    static final int COLORS = 8;

    /** Style code for a delimiter character (rendered muted, like the grammar's separator). */
    static final int DELIMITER = -1;
    /** Style code for an unstyled run (a newline). */
    static final int PLAIN = -2;

    private CsvRainbow() {}

    /**
     * A flat list of {@code {length, code}} runs covering the whole {@code text}: {@code code} is the
     * 0-based column index modulo {@code colors} for a field's characters, {@link #DELIMITER} for an
     * unquoted delimiter, and {@link #PLAIN} for a line break. Column numbering resets at each line.
     * Honors RFC-4180 quotes (a delimiter inside quotes is part of the field; {@code ""} is an escape).
     * Pure — unit-tested.
     */
    static List<int[]> segments(String text, char delim, int colors) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int col = 0;
        boolean inQuotes = false;
        int runCode = Integer.MIN_VALUE;
        int runLen = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int code;
            boolean newline = c == '\n' || c == '\r';
            if (newline) {
                code = PLAIN;
            } else if (inQuotes) {
                code = colorFor(col, colors);
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        // an escaped "" — color both quotes as field, stay in quotes; consume the pair.
                        if (code == runCode) {
                            runLen += 2;
                        } else {
                            if (runLen > 0) {
                                out.add(new int[] {runLen, runCode});
                            }
                            runCode = code;
                            runLen = 2;
                        }
                        i++;
                        continue;
                    }
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
                code = colorFor(col, colors);
            } else if (c == delim) {
                code = DELIMITER;
            } else {
                code = colorFor(col, colors);
            }
            if (code == runCode) {
                runLen++;
            } else {
                if (runLen > 0) {
                    out.add(new int[] {runLen, runCode});
                }
                runCode = code;
                runLen = 1;
            }
            if (newline) {
                col = 0;
                inQuotes = false;
            } else if (code == DELIMITER) {
                col++;
            }
        }
        if (runLen > 0) {
            out.add(new int[] {runLen, runCode});
        }
        return out;
    }

    private static int colorFor(int col, int colors) {
        return col % Math.max(1, colors);
    }

    /** Builds RichTextFX style spans over {@code text} with per-column {@code csv-col-<k>} classes. */
    static StyleSpans<Collection<String>> buildSpans(String text, char delim, int colors) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        boolean any = false;
        for (int[] seg : segments(text, delim, colors)) {
            Collection<String> style = seg[1] == DELIMITER
                    ? List.of("csv-delimiter")
                    : seg[1] == PLAIN ? List.<String>of() : List.of("csv-col-" + seg[1]);
            b.add(style, seg[0]);
            any = true;
        }
        if (!any) {
            b.add(List.of(), text == null ? 0 : text.length());
        }
        return b.create();
    }
}
