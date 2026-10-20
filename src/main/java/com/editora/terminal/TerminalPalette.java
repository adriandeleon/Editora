package com.editora.terminal;

import javafx.scene.paint.Color;

import com.jediterm.terminal.TerminalColor;
import org.jspecify.annotations.Nullable;

/**
 * The colours a terminal draws with: a background, a default foreground, and the sixteen ANSI
 * colours.
 *
 * <p>Indices 16-255 are deliberately <b>not</b> part of a palette. They are a fixed 6x6x6 cube and a
 * 24-step grey ramp defined by xterm, and programs compute those indices arithmetically — a theme
 * that restyled them would break the arithmetic rather than recolour anything.
 *
 * @param background the terminal surface
 * @param foreground text with no explicit colour
 * @param ansi the sixteen ANSI colours, 0-7 normal and 8-15 bright
 */
public record TerminalPalette(Color background, Color foreground, Color[] ansi) {

    /** Indices 16-255, identical in every theme. Built once. */
    private static final Color[] EXTENDED = buildExtended();

    public TerminalPalette {
        if (ansi.length != 16) throw new IllegalArgumentException("expected 16 ANSI colours");
        ansi = ansi.clone();
    }

    private static Color[] buildExtended() {
        Color[] colors = new Color[240];
        // 16-231: a 6x6x6 cube. The levels are xterm's and are not evenly spaced — the jump from 0
        // to the next is deliberately large so "dark" stays dark.
        int[] levels = {0, 95, 135, 175, 215, 255};
        int index = 0;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    colors[index++] = Color.rgb(levels[r], levels[g], levels[b]);
                }
            }
        }
        // 232-255: 24 greys from near-black to near-white.
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            colors[index++] = Color.rgb(v, v, v);
        }
        return colors;
    }

    /** Resolves a cell's colour, or {@code fallback} when the cell specifies none. */
    public Color resolve(@Nullable TerminalColor color, Color fallback) {
        if (color == null) return fallback;
        if (color.isIndexed()) {
            int i = color.getColorIndex();
            if (i >= 0 && i < 16) return ansi[i];
            if (i >= 16 && i < 256) return EXTENDED[i - 16];
            return fallback;
        }
        com.jediterm.core.Color rgb = color.toColor();
        return rgb == null ? fallback : Color.rgb(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
    }

    /**
     * Dim (SGR 2), blended toward the background rather than made translucent, so a dim cell over a
     * coloured background does not pick up that background's hue.
     */
    public static Color dim(Color color, Color over) {
        return color.interpolate(over, 0.45);
    }

    /** The contrast a cell's text is given at least (WCAG AA, VS Code's {@code minimumContrastRatio} default). */
    public static final double MIN_CONTRAST = 4.5;

    /**
     * {@code fg}, moved toward black or white just far enough to reach {@code ratio} against {@code bg}; as it is
     * when it already does. A palette made from a theme's syntax colours made white on blue 1.2:1 and green equal
     * to yellow; this keeps every pair readable whatever the theme, and keeps the hue where it can.
     */
    public static Color withContrast(Color fg, Color bg, double ratio) {
        if (contrast(fg, bg) >= ratio) return fg;
        double bgLum = luminance(bg);
        Color toward = bgLum > 0.5 ? Color.BLACK : Color.WHITE;
        Color away = bgLum > 0.5 ? Color.WHITE : Color.BLACK;
        // The direction away from the background's own lightness; if even its end is not enough, the other.
        Color target = contrast(toward, bg) >= contrast(away, bg) ? toward : away;
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 12; i++) {
            double mid = (lo + hi) / 2;
            if (contrast(fg.interpolate(target, mid), bg) >= ratio) hi = mid;
            else lo = mid;
        }
        return fg.interpolate(target, hi);
    }

    /** WCAG contrast ratio of two colours, 1 to 21. */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    @Override
    public Color[] ansi() {
        return ansi.clone();
    }
}
