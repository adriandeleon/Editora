package com.editora.editor;

import java.util.function.Consumer;

import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

/**
 * Colours for the Canvas-drawn editor overlays (whitespace markers, diagnostic squiggles, search washes,
 * inline debug values). A Canvas cannot be styled from CSS, and the overlays used one fixed colour each —
 * so a whitespace dot that was nearly invisible on a white editor (1.9:1) shouted on a dark one (9.2:1),
 * and an amber squiggle sat at 2.3:1 on white.
 *
 * <p>The palette is derived from the editor's <em>actual</em> background instead: each role starts from a
 * hue suited to a light or a dark ground and is then moved toward the ground's ink (black on light, white
 * on dark) only as far as its contrast target requires. That holds for every bundled and user theme,
 * including the mid-tone ones, without a per-theme table. Resolved when the editor background changes
 * (a theme switch), never per paint.
 */
final class OverlayPalette {

    /** Contrast a faint, decorative marker must reach to be visible at all without competing with text. */
    static final double SUBTLE = 3.0;
    /** Contrast of a non-text indicator (squiggle, match border). */
    static final double INDICATOR = 3.0;
    /** Contrast of text the user reads (inline debug values). */
    static final double TEXT = 4.5;

    /** One resolved set of overlay colours. */
    record Colors(
            Color whitespace,
            Color inlineValue,
            Color error,
            Color warning,
            Color info,
            Color searchMatch,
            Color searchActive,
            Color searchActiveBorder) {}

    private static Color cachedFor;
    private static Colors cached;

    private OverlayPalette() {}

    /** The overlay colours for an editor painted on {@code background}. Pure. */
    static Colors of(Color background) {
        Color ground = background == null
                ? Color.WHITE
                : Color.color(background.getRed(), background.getGreen(), background.getBlue());
        boolean dark = isDark(ground);
        Color neutral = ground.interpolate(ink(ground), 0.2);
        return new Colors(
                reach(neutral, ground, SUBTLE),
                reach(neutral, ground, TEXT),
                reach(Color.web(dark ? "#f0666b" : "#e5484d"), ground, INDICATOR),
                reach(Color.web(dark ? "#e2a03f" : "#b7791f"), ground, INDICATOR),
                reach(Color.web(dark ? "#5c9ce6" : "#2f73c9"), ground, INDICATOR),
                Color.web("#ffd54f", dark ? 0.30 : 0.40),
                Color.web("#ff9800", dark ? 0.45 : 0.55),
                reach(Color.web(dark ? "#ff9800" : "#c2410c"), ground, INDICATOR));
    }

    /** Whether light ink reads better than dark ink on {@code ground}. Pure. */
    static boolean isDark(Color ground) {
        return contrast(Color.WHITE, ground) >= contrast(Color.BLACK, ground);
    }

    private static Color ink(Color ground) {
        return isDark(ground) ? Color.WHITE : Color.BLACK;
    }

    /**
     * {@code base}, moved toward the ground's ink by the smallest step that reaches {@code ratio}
     * against {@code ground}; unchanged when it already does. Pure.
     */
    static Color reach(Color base, Color ground, double ratio) {
        if (contrast(base, ground) >= ratio) {
            return base;
        }
        Color ink = ink(ground);
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 12; i++) {
            double mid = (lo + hi) / 2;
            if (contrast(base.interpolate(ink, mid), ground) >= ratio) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return base.interpolate(ink, hi);
    }

    /** WCAG contrast ratio of two opaque colours. Pure. */
    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * Feeds {@code sink} the colours for {@code area}'s current background, and again whenever the editor
     * theme repaints it. The listener is on the area itself, which the overlay already lives and dies with.
     */
    static void track(Region area, Consumer<Colors> sink) {
        sink.accept(forRegion(area));
        area.backgroundProperty().addListener((obs, was, now) -> sink.accept(forRegion(area)));
    }

    private static Colors forRegion(Region area) {
        Color ground = backgroundOf(area);
        if (cached == null || !ground.equals(cachedFor)) { // every overlay of every buffer asks for the same one
            cachedFor = ground;
            cached = of(ground);
        }
        return cached;
    }

    /**
     * The log viewer's level colours: a gutter bar per level and a row wash for the levels worth a row.
     * Six levels need to stay apart, so they differ in more than hue — FATAL is the error colour at full
     * strength with the heaviest wash, DEBUG and TRACE are two steps of the neutral.
     */
    record LogTints(
            Color fatal,
            Color error,
            Color warn,
            Color info,
            Color debug,
            Color trace,
            Color fatalWash,
            Color errorWash,
            Color warnWash) {}

    /** The log tints for an editor painted on {@code background}. Pure. */
    static LogTints logTints(Color background) {
        Color ground = background == null
                ? Color.WHITE
                : Color.color(background.getRed(), background.getGreen(), background.getBlue());
        boolean dark = isDark(ground);
        Colors base = of(ground);
        Color neutral = ground.interpolate(ink(ground), 0.5);
        // A wash is a tint under text, so it is weighed by what is left of the ground, not by contrast: a dark
        // ground swallows a translucent colour that is plain on a light one.
        double wash = dark ? 0.20 : 0.09;
        return new LogTints(
                base.error(),
                base.error(),
                base.warning(),
                reach(Color.web(dark ? "#4cc38a" : "#2da44e"), ground, INDICATOR),
                reach(neutral, ground, INDICATOR),
                reach(ground.interpolate(ink(ground), 0.25), ground, 1.6),
                alpha(base.error(), wash * 1.8),
                alpha(base.error(), wash),
                alpha(base.warning(), wash * 0.85));
    }

    /** {@link #logTints(Color)} for {@code area}'s current background. */
    static LogTints logTints(Region area) {
        return logTints(backgroundOf(area));
    }

    private static Color alpha(Color c, double opacity) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(1, opacity));
    }

    private static Color backgroundOf(Region area) {
        Background background = area.getBackground();
        if (background != null && !background.getFills().isEmpty()) {
            Paint fill = background.getFills().get(0).getFill();
            if (fill instanceof Color color && color.getOpacity() > 0) {
                return color;
            }
        }
        return Color.WHITE; // not styled yet: the stylesheet default is a white editor
    }
}
