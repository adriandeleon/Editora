package com.editora.pdf;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

import java.util.Collection;

/**
 * Pure text-layout helpers for the code PDF: flatten a document + its highlight {@link StyleSpans} into
 * per-line colored {@link Run}s (expanding tabs), and wrap a line to a fixed column count (monospace).
 * No PDFBox here, so it is unit-tested.
 */
public final class PdfText {

    /** A run of same-styled text on one line. */
    public record Run(String text, Color color, boolean bold, boolean italic) {
    }

    private PdfText() {
    }

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
                col++;
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

    private static void flush(List<Run> line, StringBuilder buf, Color color, boolean bold, boolean italic,
            boolean have) {
        if (have && buf.length() > 0) {
            line.add(new Run(buf.toString(), color, bold, italic));
        }
        buf.setLength(0);
    }

    /** Wraps one line's runs into visual lines no wider than {@code maxCols} (monospace ⇒ 1 col/char). */
    public static List<List<Run>> wrap(List<Run> line, int maxCols) {
        int total = 0;
        for (Run r : line) {
            total += r.text().length();
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
                if (col >= maxCols) {
                    out.add(cur);
                    cur = new ArrayList<>();
                    col = 0;
                }
                int take = Math.min(maxCols - col, t.length() - pos);
                cur.add(new Run(t.substring(pos, pos + take), r.color(), r.bold(), r.italic()));
                pos += take;
                col += take;
            }
        }
        out.add(cur);
        return out;
    }
}
