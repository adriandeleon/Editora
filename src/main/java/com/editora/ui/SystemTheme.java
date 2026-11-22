package com.editora.ui;

import java.util.function.BiConsumer;

import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.MapChangeListener;

import org.jspecify.annotations.Nullable;

/**
 * What the operating system asks of the theme: light or dark (ICON-16), and Windows' high-contrast mode (ACC-09).
 *
 * <p>The app theme and the editor theme can be {@link #SYSTEM}, which is the light or the dark member of the Caret
 * &amp; Ink pair as the OS's colour scheme says, and changes with it. While Windows' high-contrast mode is on, every
 * theme choice gives way to the high-contrast pair ({@link #HIGH_CONTRAST_DARK} on a dark high-contrast scheme,
 * {@link #HIGH_CONTRAST_LIGHT} on a light one), and the chosen theme comes back when the mode ends. Both pairs can
 * also be picked by hand like any theme.
 *
 * <p>The OS's state is read from a {@link Source}: {@link #install()} uses JavaFX's {@code Platform.getPreferences()}
 * (FX thread), a test hands in its own. {@link #resolve(String, Appearance)} turns a saved theme name into the theme
 * in effect; it is pure, and {@link Themes} and {@link EditorThemes} resolve every name through it.
 */
public final class SystemTheme {

    /** The saved name of the "follow the system" choice, for the app theme and the editor theme alike. */
    public static final String SYSTEM = "System";

    /** The light member of the high-contrast pair. */
    public static final String HIGH_CONTRAST_LIGHT = "High Contrast Light";

    /** The dark member of the high-contrast pair. */
    public static final String HIGH_CONTRAST_DARK = "High Contrast Dark";

    /** The light member of the pair {@link #SYSTEM} picks from. */
    static final String LIGHT = "Editora Light";

    /** The dark member of the pair {@link #SYSTEM} picks from. */
    static final String DARK = "Editora Dark";

    /** Windows' high-contrast preference, a Boolean in {@code Platform.getPreferences()} (Windows only). */
    static final String WINDOWS_HIGH_CONTRAST = "Windows.SPI.HighContrast";

    /** The OS's appearance: a dark colour scheme or a light one, and whether it asks for high contrast. */
    public record Appearance(boolean dark, boolean highContrast) {

        /** Light, no high contrast: what is assumed before {@link #install} and where the OS says nothing. */
        public static final Appearance DEFAULT = new Appearance(false, false);
    }

    /** Where the appearance is read from, and told to have changed. */
    public interface Source {

        /** The appearance now. */
        Appearance current();

        /** Calls {@code changed} on the FX thread whenever the appearance may have changed. */
        void onChange(Runnable changed);
    }

    private static volatile Appearance now = Appearance.DEFAULT;
    private static @Nullable Source source;
    private static @Nullable BiConsumer<Appearance, Appearance> hook;

    private SystemTheme() {}

    /** Starts following the OS through {@code Platform.getPreferences()}. FX thread; later calls do nothing. */
    public static void install() {
        if (source == null) {
            install(new FxSource());
        }
    }

    /** Starts following {@code from}, in place of any source before it. FX thread. */
    static void install(Source from) {
        source = from;
        now = from.current();
        from.onChange(() -> {
            if (source != from) {
                return; // a source replaced since (tests)
            }
            Appearance before = now;
            Appearance after = from.current();
            if (!after.equals(before)) {
                now = after;
                BiConsumer<Appearance, Appearance> h = hook;
                if (h != null) {
                    h.accept(before, after);
                }
            }
        });
    }

    /**
     * Sets what runs when the appearance changes, with the appearance before and after; it replaces the one
     * before. FX thread.
     */
    public static void onChange(@Nullable BiConsumer<Appearance, Appearance> changed) {
        hook = changed;
    }

    /** Forgets the source, the hook and the appearance read (tests). */
    static void reset() {
        source = null;
        hook = null;
        now = Appearance.DEFAULT;
    }

    /** The OS's appearance as last read. */
    public static Appearance appearance() {
        return now;
    }

    /** The theme in effect for saved name {@code name} under the OS's appearance now. */
    public static String resolve(String name) {
        return resolve(name, now);
    }

    /**
     * The theme in effect for saved name {@code name} under {@code appearance}: the high-contrast pair's member
     * for the OS's scheme while it asks for high contrast, else the Caret &amp; Ink member for {@link #SYSTEM},
     * else the name itself. Pure.
     */
    public static String resolve(@Nullable String name, Appearance appearance) {
        if (appearance.highContrast()) {
            return appearance.dark() ? HIGH_CONTRAST_DARK : HIGH_CONTRAST_LIGHT;
        }
        if (SYSTEM.equals(name)) {
            return appearance.dark() ? DARK : LIGHT;
        }
        return name == null ? "" : name;
    }

    /** Whether {@code name} names a theme the app's appearance decides between {@code before} and {@code after}. Pure. */
    public static boolean changes(String name, Appearance before, Appearance after) {
        return !resolve(name, before).equals(resolve(name, after));
    }

    /** The appearance {@code Platform.getPreferences()} reports. Pure, given its values. */
    static Appearance appearance(@Nullable ColorScheme scheme, @Nullable Object windowsHighContrast) {
        return new Appearance(scheme == ColorScheme.DARK, Boolean.TRUE.equals(windowsHighContrast));
    }

    /** {@code Platform.getPreferences()}: its colour scheme, and Windows' high-contrast key. */
    private static final class FxSource implements Source {

        @Override
        public Appearance current() {
            Platform.Preferences prefs = Platform.getPreferences();
            return appearance(prefs.getColorScheme(), prefs.get(WINDOWS_HIGH_CONTRAST));
        }

        @Override
        public void onChange(Runnable changed) {
            Platform.Preferences prefs = Platform.getPreferences();
            prefs.colorSchemeProperty().addListener((obs, was, scheme) -> changed.run());
            prefs.addListener((MapChangeListener<String, Object>) change -> {
                if (WINDOWS_HIGH_CONTRAST.equals(change.getKey())) {
                    changed.run();
                }
            });
        }
    }
}
