package com.editora.editor;

import java.util.function.Consumer;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

import org.jspecify.annotations.Nullable;

/**
 * Colours for the Canvas-drawn editor overlays (whitespace markers, diagnostic squiggles, search washes,
 * inline debug values). A Canvas cannot be styled from CSS, so the overlays read the theme through
 * {@link ThemeProbes}: its error, warning and success tokens ({@code -state-red}, {@code -state-amber},
 * {@code -state-green}) and its accent ({@code -color-accent-emphasis}, {@code -color-accent-fg}) are the
 * {@link Seeds} every role starts from.
 *
 * <p>Each role is then moved toward the editor ground's ink (black on light, white on dark) only as far as its
 * contrast target requires, so it holds on every bundled and user theme and under an editor theme whose ground
 * differs from the app theme's. Where no stylesheet reaches the probes, every seed is the ground's own mid ink:
 * a neutral, no fixed hue (ICON-08). Amber and red mark only warnings and errors, green only a success; a search
 * match and an assignment are the accent (ICON-18, Pauta 6.2.0). Resolved when the editor background or the theme
 * changes, never per paint.
 */
public final class OverlayPalette {

    /** Contrast a faint, decorative marker must reach to be visible at all without competing with text. */
    static final double SUBTLE = 3.0;
    /** Contrast of a structural hint drawn across many lines (indent guides): there, but well behind text. */
    static final double GUIDE = 1.45;
    /** Contrast of a non-text indicator (squiggle, match border). */
    public static final double INDICATOR = 3.0;
    /** Contrast of text the user reads (inline debug values). */
    static final double TEXT = 4.5;

    /** The theme colours the palette starts from. */
    public record Seeds(Color error, Color warning, Color success, Color accent, Color info) {

        /** No theme to read: every seed the ground's mid ink, which no theme owns. */
        public static Seeds neutral(Color ground) {
            Color mid = ground.interpolate(ink(ground), 0.6);
            return new Seeds(mid, mid, mid, mid, mid);
        }

        /** The seeds {@code probes} resolved to, each one {@link #neutral} where none did. */
        static Seeds of(ThemeProbes probes, Color ground) {
            Seeds none = neutral(ground);
            return new Seeds(
                    probes.get(ThemeProbes.RED, none.error()),
                    probes.get(ThemeProbes.AMBER, none.warning()),
                    probes.get(ThemeProbes.GREEN, none.success()),
                    probes.get(ThemeProbes.ACCENT, none.accent()),
                    probes.get(ThemeProbes.ACCENT_FG, none.info()));
        }
    }

    /** One resolved set of overlay colours. */
    public record Colors(
            Color whitespace,
            Color inlineValue,
            Color error,
            Color warning,
            Color info,
            Color searchMatch,
            Color searchActive,
            Color searchActiveBorder,
            Color indentGuide,
            Color ground,
            Color success,
            Color occurrenceRead,
            Color occurrenceWrite,
            Color occurrenceWriteBorder,
            Color jumpPill,
            Color jumpInk) {}

    private record Key(Color ground, Seeds seeds) {}

    private static @Nullable Key cachedFor;
    private static @Nullable Colors cached;

    private OverlayPalette() {}

    /** The overlay colours for an editor painted on {@code background}, with no theme to read. Pure. */
    public static Colors of(@Nullable Color background) {
        Color ground = opaque(background);
        return of(ground, Seeds.neutral(ground));
    }

    /** The overlay colours for an editor painted on {@code background}, starting from {@code seeds}. Pure. */
    public static Colors of(@Nullable Color background, Seeds seeds) {
        Color ground = opaque(background);
        boolean dark = isDark(ground);
        Color neutral = ground.interpolate(ink(ground), 0.2);
        Color accent = seeds.accent();
        Color pill = reach(accent, ground, INDICATOR);
        return new Colors(
                reach(neutral, ground, SUBTLE),
                reach(neutral, ground, TEXT),
                reach(seeds.error(), ground, INDICATOR),
                reach(seeds.warning(), ground, INDICATOR),
                reach(seeds.info(), ground, INDICATOR),
                alpha(accent, dark ? 0.26 : 0.32), // text sits under it: a dark ground shows a tint more
                alpha(accent, dark ? 0.45 : 0.55),
                reach(accent, ground, INDICATOR),
                reach(ground.interpolate(ink(ground), 0.08), ground, GUIDE),
                ground,
                reach(seeds.success(), ground, INDICATOR),
                alpha(ground.interpolate(ink(ground), 0.5), 0.22),
                alpha(accent, 0.32),
                alpha(reach(accent, ground, INDICATOR), 0.9),
                pill,
                ink(pill));
    }

    private static Color opaque(@Nullable Color background) {
        if (background == null) {
            return Color.WHITE; // the stylesheet default is a white editor
        }
        return Color.color(background.getRed(), background.getGreen(), background.getBlue());
    }

    /** Whether light ink reads better than dark ink on {@code ground}. Pure. */
    public static boolean isDark(Color ground) {
        return contrast(Color.WHITE, ground) >= contrast(Color.BLACK, ground);
    }

    private static Color ink(Color ground) {
        return isDark(ground) ? Color.WHITE : Color.BLACK;
    }

    /**
     * {@code base}, moved toward the ground's ink by the smallest step that reaches {@code ratio}
     * against {@code ground}; unchanged when it already does. Pure.
     */
    public static Color reach(Color base, Color ground, double ratio) {
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
     * Feeds {@code sink} the colours for {@code area}'s current background and the theme, and again whenever the
     * editor theme repaints the area or the app theme changes. The theme is read through probes added to
     * {@code host}, the painter's own children, which it lives and dies with; so is the area listener.
     */
    public static void track(Region area, ObservableList<Node> host, Consumer<Colors> sink) {
        ThemeProbes[] probes = new ThemeProbes[1];
        Runnable refresh = () -> {
            if (probes[0] != null) {
                sink.accept(forRegion(area, probes[0]));
            }
        };
        probes[0] = new ThemeProbes(
                host,
                refresh,
                ThemeProbes.RED,
                ThemeProbes.AMBER,
                ThemeProbes.GREEN,
                ThemeProbes.ACCENT,
                ThemeProbes.ACCENT_FG);
        refresh.run();
        area.backgroundProperty().addListener((obs, was, now) -> refresh.run());
    }

    private static Colors forRegion(Region area, ThemeProbes probes) {
        Color ground = backgroundOf(area);
        Key key = new Key(ground, Seeds.of(probes, ground));
        if (cached == null || !key.equals(cachedFor)) { // every overlay of every buffer asks for the same one
            cachedFor = key;
            cached = of(ground, key.seeds());
        }
        return cached;
    }

    /**
     * The log viewer's level colours: a gutter bar per level and a row wash for the levels worth a row.
     * Six levels need to stay apart, so they differ in more than hue — FATAL is the error colour at full
     * strength with the heaviest wash, DEBUG and TRACE are two steps of the neutral.
     */
    public record LogTints(
            Color fatal,
            Color error,
            Color warn,
            Color info,
            Color debug,
            Color trace,
            Color fatalWash,
            Color errorWash,
            Color warnWash,
            Color match) {}

    /** The log tints for an editor painted on {@code background}, with no theme to read. Pure. */
    public static LogTints logTints(@Nullable Color background) {
        Color ground = opaque(background);
        return logTints(ground, Seeds.neutral(ground));
    }

    /**
     * The log tints for an editor painted on {@code background}, from {@code seeds}: FATAL and ERROR are the error
     * colour, WARN the warning colour, INFO the accent's text tint (an INFO line is no success, ICON-18). Pure.
     */
    public static LogTints logTints(@Nullable Color background, Seeds seeds) {
        Color ground = opaque(background);
        boolean dark = isDark(ground);
        Colors base = of(ground, seeds);
        Color neutral = ground.interpolate(ink(ground), 0.5);
        // A wash is a tint under text, so it is weighed by what is left of the ground, not by contrast: a dark
        // ground swallows a translucent colour that is plain on a light one.
        double wash = dark ? 0.20 : 0.09;
        return new LogTints(
                base.error(),
                base.error(),
                base.warning(),
                base.info(),
                reach(neutral, ground, INDICATOR),
                reach(ground.interpolate(ink(ground), 0.25), ground, 1.6),
                alpha(base.error(), wash * 1.8),
                alpha(base.error(), wash),
                alpha(base.warning(), wash * 0.85),
                base.searchMatch());
    }

    /** Feeds {@code sink} the log tints for {@code area}, as {@link #track} feeds the overlay colours. */
    public static void trackLogTints(Region area, ObservableList<Node> host, Consumer<LogTints> sink) {
        ThemeProbes[] probes = new ThemeProbes[1];
        Runnable refresh = () -> {
            if (probes[0] != null) {
                Color ground = backgroundOf(area);
                sink.accept(logTints(ground, Seeds.of(probes[0], ground)));
            }
        };
        probes[0] = new ThemeProbes(
                host,
                refresh,
                ThemeProbes.RED,
                ThemeProbes.AMBER,
                ThemeProbes.GREEN,
                ThemeProbes.ACCENT,
                ThemeProbes.ACCENT_FG);
        refresh.run();
        area.backgroundProperty().addListener((obs, was, now) -> refresh.run());
    }

    private static Color alpha(Color c, double opacity) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(1, opacity));
    }

    public static Color backgroundOf(Region area) {
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
