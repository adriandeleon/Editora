package com.editora.pdf;

import java.awt.Color;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;

/**
 * Renders syntax-highlighted code to a self-contained HTML fragment for the clipboard's {@code text/html}
 * flavor — so pasting code into Slack, an email or a document keeps its colors (VS Code's
 * {@code editor.copyWithSyntaxHighlighting}). Pure (no toolkit), so it is unit-tested.
 *
 * <p>It reuses {@link PdfText#splitIntoLineRuns} + {@link PdfTheme}, the same token→color mapping the PDF
 * and print exports use, so the palette is the deliberately light GitHub-style one — pasted code almost
 * always lands on a light background, the same reasoning that makes those exports light-only.
 */
public final class CodeHtml {

    private CodeHtml() {}

    /**
     * A {@code <pre>} fragment for {@code text} styled by {@code spans}. Tabs are expanded to
     * {@code tabSize} columns (as in the code exports). Never returns null; an empty input yields an empty
     * {@code <pre>}.
     */
    public static String toHtml(String text, StyleSpans<Collection<String>> spans, int tabSize) {
        List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns(text == null ? "" : text, spans, tabSize);
        StringBuilder sb = new StringBuilder(256);
        // A <pre> preserves the runs' expanded whitespace across the widest range of paste targets; the
        // font-family/background make it read as a code block rather than inheriting the target's prose style.
        sb.append("<pre style=\"font-family:")
                .append("'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace;")
                .append("font-size:12px;color:")
                .append(hex(PdfTheme.DEFAULT_FG))
                .append(";background-color:")
                .append(hex(PdfTheme.BACKGROUND))
                .append(";padding:8px;border-radius:6px;\">");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            for (PdfText.Run run : lines.get(i)) {
                appendRun(sb, run);
            }
        }
        sb.append("</pre>");
        return sb.toString();
    }

    private static void appendRun(StringBuilder sb, PdfText.Run run) {
        String content = escape(run.text());
        boolean styled = !run.color().equals(PdfTheme.DEFAULT_FG) || run.bold() || run.italic();
        if (!styled) {
            sb.append(content); // default-colored, unstyled text needs no span (smaller, inherits the <pre>)
            return;
        }
        sb.append("<span style=\"color:").append(hex(run.color()));
        if (run.bold()) {
            sb.append(";font-weight:bold");
        }
        if (run.italic()) {
            sb.append(";font-style:italic");
        }
        sb.append("\">").append(content).append("</span>");
    }

    /** {@code &}, {@code <}, {@code >} escaped; whitespace is left to the {@code <pre>}. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
