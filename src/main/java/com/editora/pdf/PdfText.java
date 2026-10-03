package com.editora.pdf;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Pure text-layout helpers for the code PDF: flatten a document + its highlight {@link StyleSpans} into
 * per-line colored {@link Run}s (expanding tabs), and wrap a line to a fixed column count (monospace).
 * No PDFBox here, so it is unit-tested.
 */
public final class PdfText {

    /** A run of same-styled text on one line. */
    public record Run(String text, Color color, boolean bold, boolean italic) {}

    private PdfText() {}

    /**
     * Splits {@code text} into lines of {@link Run}s. When {@code spans} is non-null each run carries its
     * token color/bold/italic (from {@link PdfTheme}); when null every run is default-colored plain text.
     * Tabs expand to spaces against {@code tabSize} (column-aware); {@code \r} is dropped.
     */
    public static List<List<Run>> splitIntoLineRuns(String text, StyleSpans<Collection<String>> spans, int tabSize) {
        Iterator<StyleSpan<Collection<String>>> it = spans == null ? null : spans.iterator();
        Collection<String> style = null;
        int left = 0;

        List<List<Run>> lines = new ArrayList<>();
        List<Run> line = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Color color = PdfTheme.DEFAULT_FG;
        boolean bold = false;
        boolean italic = false;
        boolean have = false;
        int col = 0;

        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (it != null) {
                while (left == 0 && it.hasNext()) {
                    StyleSpan<Collection<String>> s = it.next();
                    style = s.getStyle();
                    left = s.getLength();
                }
                if (left > 0) {
                    left--;
                }
            }
            char ch = text.charAt(i);
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                flush(line, buf, color, bold, italic, have);
                have = false;
                lines.add(line);
                line = new ArrayList<>();
                col = 0;
                continue;
            }
            Color c = it == null ? PdfTheme.DEFAULT_FG : PdfTheme.colorFor(style);
            boolean b = it != null && PdfTheme.bold(style);
            boolean ita = it != null && PdfTheme.italic(style);
            String piece;
            if (ch == '\t') {
                int spaces = tabSize - (col % tabSize);
                piece = " ".repeat(spaces);
                col += spaces;
            } else {
                piece = String.valueOf(ch);
                if (!Character.isLowSurrogate(ch)) {
                    col += columns(text.codePointAt(i)); // a wide (CJK) character occupies two cells
                }
            }
            if (have && (!c.equals(color) || b != bold || ita != italic)) {
                flush(line, buf, color, bold, italic, true);
            }
            if (!have || buf.length() == 0) {
                color = c;
                bold = b;
                italic = ita;
                have = true;
            }
            buf.append(piece);
        }
        flush(line, buf, color, bold, italic, have);
        lines.add(line);
        return lines;
    }

    private static void flush(
            List<Run> line, StringBuilder buf, Color color, boolean bold, boolean italic, boolean have) {
        if (have && buf.length() > 0) {
            line.add(new Run(buf.toString(), color, bold, italic));
        }
        buf.setLength(0);
    }

    /**
     * The number of monospace cells {@code cp} occupies: two for an East Asian wide character (CJK
     * ideographs, kana, hangul, full-width forms), one for everything else.
     */
    public static int columns(int cp) {
        boolean wide = (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
        return wide ? 2 : 1;
    }

    /** The number of monospace cells {@code text} occupies (see {@link #columns(int)}). */
    public static int columns(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            n += columns(cp);
        }
        return n;
    }

    /**
     * Wraps one line's runs into visual lines no wider than {@code maxCols} cells (monospace: one cell per
     * character, two for a wide one). Never splits a surrogate pair.
     */
    public static List<List<Run>> wrap(List<Run> line, int maxCols) {
        int total = 0;
        for (Run r : line) {
            total += columns(r.text());
        }
        if (maxCols <= 0 || total <= maxCols) {
            return List.of(line);
        }
        List<List<Run>> out = new ArrayList<>();
        List<Run> cur = new ArrayList<>();
        int col = 0;
        for (Run r : line) {
            String t = r.text();
            int pos = 0;
            while (pos < t.length()) {
                int end = pos;
                int used = 0;
                while (end < t.length()) {
                    int cp = t.codePointAt(end);
                    int w = columns(cp);
                    if (col + used + w > maxCols && (col + used > 0)) {
                        break; // (a wide character alone on a 1-cell line is still emitted)
                    }
                    used += w;
                    end += Character.charCount(cp);
                }
                if (end == pos) {
                    out.add(cur);
                    cur = new ArrayList<>();
                    col = 0;
                    continue;
                }
                cur.add(new Run(t.substring(pos, end), r.color(), r.bold(), r.italic()));
                pos = end;
                col += used;
            }
        }
        out.add(cur);
        return out;
    }
}
