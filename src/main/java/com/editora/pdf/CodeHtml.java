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

    /**
     * The token classes of {@link #toClassHtml}: each light-palette token colour ({@link PdfTheme}) and the
     * colour the same token has on a dark page. The page's stylesheet is generated from this table
     * ({@link #classCss}), so the two cannot drift.
     */
    private static final String[][] TOKEN_CLASSES = {
        {"#cf222e", "hl-k", "#ff7b72"}, // keyword, operator, list marker
        {"#0a3069", "hl-s", "#a5d6ff"}, // string, regexp, inline code
        {"#1a7f37", "hl-e", "#7ee787"}, // escape
        {"#6e7781", "hl-c", "#8b949e"}, // comment, quote
        {"#0550ae", "hl-n", "#79c0ff"}, // number, constant, property, heading
        {"#953800", "hl-t", "#ffa657"}, // type, annotation
        {"#8250df", "hl-f", "#d2a8ff"}, // function
        {"#116329", "hl-g", "#7ee787"}, // tag
        {"#0969da", "hl-l", "#58a6ff"}, // link
    };

    /**
     * A {@code <pre class="md-highlight">} fragment whose tokens carry <em>classes</em>, not colours, for a
     * page that has both a light and a dark scheme: {@link #classCss} is the stylesheet that colours them
     * in each. A token colour the table does not know keeps an inline colour.
     */
    public static String toClassHtml(String text, StyleSpans<Collection<String>> spans, int tabSize) {
        List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns(text == null ? "" : text, spans, tabSize);
        StringBuilder sb = new StringBuilder(256).append("<pre class=\"md-highlight\"><code>");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            for (PdfText.Run run : lines.get(i)) {
                String content = escape(run.text());
                String cls = tokenClass(run.color());
                boolean plain = run.color().equals(PdfTheme.DEFAULT_FG);
                if (plain && !run.bold() && !run.italic()) {
                    sb.append(content);
                    continue;
                }
                sb.append("<span");
                StringBuilder classes = new StringBuilder(cls == null ? "" : cls);
                if (run.bold()) {
                    classes.append(classes.isEmpty() ? "" : " ").append("hl-b");
                }
                if (run.italic()) {
                    classes.append(classes.isEmpty() ? "" : " ").append("hl-i");
                }
                if (!classes.isEmpty()) {
                    sb.append(" class=\"").append(classes).append('"');
                }
                if (cls == null && !plain) {
                    sb.append(" style=\"color:").append(hex(run.color())).append('"');
                }
                sb.append('>').append(content).append("</span>");
            }
        }
        return sb.append("</code></pre>").toString();
    }

    private static String tokenClass(Color color) {
        String hex = hex(color);
        for (String[] row : TOKEN_CLASSES) {
            if (row[0].equalsIgnoreCase(hex)) {
                return row[1];
            }
        }
        return null;
    }

    /** The rules for {@link #toClassHtml}'s classes under {@code scope}: light, and dark where the page is. */
    public static String classCss(String scope) {
        StringBuilder light = new StringBuilder();
        StringBuilder dark = new StringBuilder();
        for (String[] row : TOKEN_CLASSES) {
            light.append(scope)
                    .append(" .")
                    .append(row[1])
                    .append(" { color: ")
                    .append(row[0])
                    .append("; }\n");
            dark.append("  ")
                    .append(scope)
                    .append(" .")
                    .append(row[1])
                    .append(" { color: ")
                    .append(row[2])
                    .append("; }\n");
        }
        return light + scope + " .hl-b { font-weight: bold; }\n" + scope + " .hl-i { font-style: italic; }\n"
                + "@media (prefers-color-scheme: dark) {\n" + dark + "}\n"
                + "@media print {\n" + light.toString().replace(scope, "  " + scope) + "}\n";
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
