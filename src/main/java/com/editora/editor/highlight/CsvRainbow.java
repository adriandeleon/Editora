package com.editora.editor.highlight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * "Rainbow" per-column CSV highlighting: assigns each column a cycling color so field boundaries are easy
 * to read (the VS Code Rainbow CSV idiom). Computed directly in Java — TextMate can't count columns — as
 * {@link StyleSpans} over the whole document, used by {@code BufferHighlighter.applyHighlighting} for CSV buffers
 * when rainbow is on (in place of the {@code source.csv} grammar).
 *
 * <p>The pure {@link #segments} (no toolkit) is unit-tested; {@link #buildSpans} is the thin RichTextFX
 * wrapper that maps each segment's style code to a CSS class ({@code csv-col-<k>} / {@code csv-delimiter}).
 */
public final class CsvRainbow {

    /** Number of distinct column colors before the cycle repeats (matches the {@code .text.csv-col-N} CSS). */
    public static final int COLORS = 8;

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
    public static StyleSpans<Collection<String>> buildSpans(String text, char delim, int colors) {
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

    /**
     * The range of the current text, {@code [from, to)}, whose styles must change for the document to show
     * {@code now}, when it shows {@code before} (the spans last applied) edited only inside {@code [dirtyStart,
     * dirtyEnd)} (current-text offsets; see {@link HighlightDirty}). Outside that range the document's styles are
     * {@code before}'s, shifted with the text, so the answer starts at the first offset before {@code dirtyStart}
     * where {@code now} differs from {@code before} (else at {@code dirtyStart}) and ends at the last offset after
     * {@code dirtyEnd} where {@code now} differs from {@code before} aligned at the end (else at
     * {@code dirtyEnd}). {@code from == to}: nothing to restyle. Lets a pause restyle only the records an edit
     * changed instead of every paragraph (perf review 2026-11-23 E4).
     */
    public static int[] changedRange(
            StyleSpans<Collection<String>> before, StyleSpans<Collection<String>> now, int dirtyStart, int dirtyEnd) {
        int length = now.length();
        int ds = Math.max(0, Math.min(dirtyStart, length));
        int de = Math.max(ds, Math.min(dirtyEnd, length));
        if (before.length() - (length - de) < ds) {
            return new int[] {0, length}; // the dirty range does not explain the change in length: restyle all
        }
        List<StyleSpan<Collection<String>>> a = spans(before);
        List<StyleSpan<Collection<String>>> b = spans(now);
        int from = commonRun(a, b, ds, false);
        int to = length - commonRun(a, b, length - de, true);
        return from >= to ? new int[] {from, from} : new int[] {from, to};
    }

    private static List<StyleSpan<Collection<String>>> spans(StyleSpans<Collection<String>> spans) {
        List<StyleSpan<Collection<String>>> out = new java.util.ArrayList<>(spans.getSpanCount());
        spans.forEach(out::add);
        return out;
    }

    /** How many characters from the start (or the end) have equal styles in both, at most {@code limit}. */
    private static int commonRun(
            List<StyleSpan<Collection<String>>> a, List<StyleSpan<Collection<String>>> b, int limit, boolean fromEnd) {
        int i = 0;
        int j = 0;
        int leftA = 0;
        int leftB = 0;
        int pos = 0;
        while (pos < limit) {
            if (leftA == 0) {
                if (i == a.size()) {
                    return pos;
                }
                leftA = a.get(fromEnd ? a.size() - 1 - i : i).getLength();
            }
            if (leftB == 0) {
                if (j == b.size()) {
                    return pos;
                }
                leftB = b.get(fromEnd ? b.size() - 1 - j : j).getLength();
            }
            if (leftA == 0) {
                i++;
                continue;
            }
            if (leftB == 0) {
                j++;
                continue;
            }
            Collection<String> sa = a.get(fromEnd ? a.size() - 1 - i : i).getStyle();
            Collection<String> sb = b.get(fromEnd ? b.size() - 1 - j : j).getStyle();
            if (!sa.equals(sb)) {
                return pos;
            }
            int step = Math.min(Math.min(leftA, leftB), limit - pos);
            pos += step;
            leftA -= step;
            leftB -= step;
            if (leftA == 0) {
                i++;
            }
            if (leftB == 0) {
                j++;
            }
        }
        return limit;
    }
}
