package com.editora.terminal;

import javafx.scene.paint.Color;

/**
 * Terminal palettes derived from editor themes.
 *
 * <p>The rule is Termina's, where it was applied by hand to the Editora pair: keyword becomes red, escape green,
 * type yellow, number blue, function magenta, and the caret accent cyan — so {@code ls} and {@code git diff}
 * colours sit in the same family as the code in the editor. Termina chose the bright variants and the ends of
 * the ramp by eye; here they are computed, so any editor theme yields a palette:
 *
 * <ul>
 *   <li>On a dark ground the bright colours move toward white; on a light ground toward black, since a light
 *       colour on white is the failure every light terminal theme has to avoid.
 *   <li>ANSI white (7) is the text colour pulled toward the background, so it stays distinct from bright
 *       white (15, the text colour itself). On a light ground that makes "white" dark — it has to be, or a
 *       program that sets colour 7 writes invisible text.
 *   <li>Bright black (8) is halfway between text and ground: what dim and secondary text uses.
 * </ul>
 *
 * <p>No palette rescues a prompt built for a dark terminal: agnoster's {@code user@host} segment is the
 * <em>default</em> foreground on ANSI black, so on any light theme it is dark on dark.
 */
public final class TerminalPalettes {

    private TerminalPalettes() {}

    /** The flagship dark editor theme ("Caret &amp; Ink"), from {@code editor-themes/editora-dark.css}. */
    public static final TerminalPalette EDITORA_DARK = derive(new SyntaxColors(
            Color.web("#171a24"),
            Color.web("#e8eaf3"),
            Color.web("#43dec5"),
            Color.web("#ff7b72"),
            Color.web("#7ee787"),
            Color.web("#ffa657"),
            Color.web("#79c0ff"),
            Color.web("#d2a8ff")));

    /** The flagship light editor theme, from {@code editor-themes/editora-light.css}. */
    public static final TerminalPalette EDITORA_LIGHT = derive(new SyntaxColors(
            Color.web("#ffffff"),
            Color.web("#1b1e2a"),
            Color.web("#0e8577"),
            Color.web("#cf222e"),
            Color.web("#1a7f37"),
            Color.web("#953800"),
            Color.web("#0550ae"),
            Color.web("#8250df")));

    /** The palette for an editor theme's colours. */
    public static TerminalPalette derive(SyntaxColors c) {
        boolean dark = isDark(c.background());
        Color fg = c.foreground();
        Color bg = c.background();
        Color[] normal = {
            dark ? bg.interpolate(Color.BLACK, 0.25) : fg,
            c.keyword(),
            c.escape(),
            c.type(),
            c.number(),
            c.function(),
            c.caret(),
            fg.interpolate(bg, dark ? 0.15 : 0.3),
        };
        Color toward = dark ? Color.WHITE : Color.BLACK;
        double amount = dark ? 0.3 : 0.25;
        Color[] ansi = new Color[16];
        for (int i = 0; i < 8; i++) {
            ansi[i] = normal[i];
        }
        ansi[8] = fg.interpolate(bg, 0.5);
        for (int i = 1; i <= 6; i++) {
            ansi[8 + i] = normal[i].interpolate(toward, amount);
        }
        ansi[15] = fg;
        return new TerminalPalette(bg, fg, ansi);
    }

    /** Whether a ground is dark: relative luminance below the midpoint. */
    static boolean isDark(Color background) {
        return luminance(background) < 0.5;
    }

    /** WCAG relative luminance. */
    static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    /** WCAG contrast ratio between two colours. */
    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
