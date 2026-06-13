package com.editora.pdf;

import java.awt.Color;
import java.util.Collection;
import java.util.Map;

/**
 * The fixed <b>light</b> (GitHub "Primer Light") palette used for PDF export, mirroring the editor's
 * {@code styles/syntax.css} default theme. PDFs always export light regardless of the app's editor
 * theme, so these colors are intentionally hardcoded (the single source of truth for the PDF palette).
 * The map keys are the CSS token classes emitted by {@code TextMateHighlighter}.
 */
public final class PdfTheme {

    /** Default text / page-background / line-number-gutter colors (Primer Light). */
    public static final Color DEFAULT_FG = hex("#24292f");

    public static final Color BACKGROUND = hex("#ffffff");
    public static final Color LINE_NUMBER = hex("#8c959f");
    public static final Color CODE_BG = hex("#f6f8fa");
    public static final Color RULE = hex("#d0d7de");

    private static final Map<String, Color> TOKEN = Map.ofEntries(
            Map.entry("keyword", hex("#cf222e")),
            Map.entry("operator", hex("#cf222e")),
            Map.entry("string", hex("#0a3069")),
            Map.entry("regexp", hex("#0a3069")),
            Map.entry("escape", hex("#1a7f37")),
            Map.entry("comment", hex("#6e7781")),
            Map.entry("number", hex("#0550ae")),
            Map.entry("constant", hex("#0550ae")),
            Map.entry("property", hex("#0550ae")),
            Map.entry("attribute", hex("#0550ae")),
            Map.entry("annotation", hex("#953800")),
            Map.entry("type", hex("#953800")),
            Map.entry("function", hex("#8250df")),
            Map.entry("variable", hex("#24292f")),
            Map.entry("punct", hex("#24292f")),
            Map.entry("tag", hex("#116329")),
            Map.entry("code", hex("#0a3069")),
            Map.entry("heading", hex("#0550ae")),
            Map.entry("link", hex("#0969da")));

    private PdfTheme() {}

    /** The color for a token's style classes (first known class wins), or {@link #DEFAULT_FG}. */
    public static Color colorFor(Collection<String> styleClasses) {
        if (styleClasses != null) {
            for (String c : styleClasses) {
                Color color = TOKEN.get(c);
                if (color != null) {
                    return color;
                }
            }
        }
        return DEFAULT_FG;
    }

    /** Whether the token should render bold (keywords/headings/bold markup). */
    public static boolean bold(Collection<String> styleClasses) {
        return styleClasses != null
                && (styleClasses.contains("keyword")
                        || styleClasses.contains("heading")
                        || styleClasses.contains("bold"));
    }

    /** Whether the token should render italic (comments/regex/italic markup). */
    public static boolean italic(Collection<String> styleClasses) {
        return styleClasses != null
                && (styleClasses.contains("comment")
                        || styleClasses.contains("regexp")
                        || styleClasses.contains("italic"));
    }

    static Color hex(String s) {
        return new Color(
                Integer.parseInt(s.substring(1, 3), 16),
                Integer.parseInt(s.substring(3, 5), 16),
                Integer.parseInt(s.substring(5, 7), 16));
    }
}
