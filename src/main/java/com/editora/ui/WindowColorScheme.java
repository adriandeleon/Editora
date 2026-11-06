package com.editora.ui;

import javafx.application.ColorScheme;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * Tells every window whether the app theme is light or dark, so the parts JavaFX and the OS draw match it.
 *
 * <p>Editora themes itself, but JavaFX picks the look of the window chrome it draws from the scene's colour
 * scheme ({@link Scene.Preferences#colorSchemeProperty()}), which defaults to the <em>desktop</em> setting:
 * the frame of a decorated window ({@code setDarkFrame}) and the minimise/maximise/close buttons of an extended
 * one (their {@code dark} style follows {@code HeaderBar.systemColorScheme}, which falls back to the scene's).
 * With a dark app theme on a light desktop those buttons came out dark grey on a dark header, all but
 * invisible. So each window's scene is given the app theme's scheme instead, when the theme changes and as
 * each window opens.
 *
 * <p>Popups (menus, tooltips, completion lists) have no frame or window buttons and are skipped. Nothing
 * Editora draws in a scene reads the scheme back: its stylesheets come from the theme, not from
 * {@code prefers-color-scheme}.
 */
public final class WindowColorScheme {

    private static volatile boolean dark;

    private WindowColorScheme() {}

    /**
     * Starts giving each window the current scheme as it opens. Call once at start-up, before the first
     * window is shown. A window joins {@link Window#getWindows()} as it is shown, before its first frame.
     */
    public static void install(boolean darkTheme) {
        dark = darkTheme;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    hook(w);
                }
            }
        });
        for (Window w : Window.getWindows()) {
            hook(w);
        }
    }

    /** Applies a light or dark app theme to every open window; windows opened later follow {@link #install}. */
    public static void apply(boolean darkTheme) {
        dark = darkTheme;
        for (Window w : Window.getWindows()) {
            if (!(w instanceof PopupWindow)) {
                applyTo(w.getScene(), darkTheme);
            }
        }
    }

    /** Whether the windows are currently told the app theme is dark. */
    static boolean isDark() {
        return dark;
    }

    private static void hook(Window window) {
        if (window instanceof PopupWindow) {
            return;
        }
        applyTo(window.getScene(), dark);
        // A window whose scene is swapped later keeps the scheme too.
        window.sceneProperty().addListener((obs, was, scene) -> applyTo(scene, dark));
    }

    /** Sets a scene's colour scheme for a light or dark app theme. Package-private for tests. */
    static void applyTo(@Nullable Scene scene, boolean darkTheme) {
        if (scene != null) {
            scene.getPreferences().setColorScheme(darkTheme ? ColorScheme.DARK : ColorScheme.LIGHT);
        }
    }
}
