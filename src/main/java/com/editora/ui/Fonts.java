package com.editora.ui;

import java.io.InputStream;
import java.util.List;

import javafx.scene.text.Font;

/**
 * Loads the editor's bundled monospace fonts so they're available by family name regardless of what
 * is installed on the system. Call {@link #load()} once at startup (before any UI/CSS is built).
 *
 * <p>Font files live under {@code resources/com/editora/fonts/<family>/}. Each family ships Regular,
 * Bold, Italic, and BoldItalic where available (Fira Code has no italic — JavaFX synthesizes it), so
 * the syntax theme's bold/italic spans render with real glyphs.
 */
public final class Fonts {

    /** Bundled font families, in picker order; the first is the default for new configs. */
    public static final List<String> BUNDLED = List.of(
            "JetBrains Mono",
            "Cascadia Code",
            "Fira Code",
            "IBM Plex Mono",
            "Source Code Pro");

    public static final String DEFAULT = "JetBrains Mono";

    private static final String[] FILES = {
        "jetbrains-mono/JetBrainsMono-Regular.ttf",
        "jetbrains-mono/JetBrainsMono-Bold.ttf",
        "jetbrains-mono/JetBrainsMono-Italic.ttf",
        "jetbrains-mono/JetBrainsMono-BoldItalic.ttf",
        "cascadia-code/CascadiaCode-Regular.ttf",
        "cascadia-code/CascadiaCode-Bold.ttf",
        "cascadia-code/CascadiaCode-Italic.ttf",
        "cascadia-code/CascadiaCode-BoldItalic.ttf",
        "fira-code/FiraCode-Regular.ttf",
        "fira-code/FiraCode-Bold.ttf",
        "ibm-plex-mono/IBMPlexMono-Regular.ttf",
        "ibm-plex-mono/IBMPlexMono-Bold.ttf",
        "ibm-plex-mono/IBMPlexMono-Italic.ttf",
        "ibm-plex-mono/IBMPlexMono-BoldItalic.ttf",
        "source-code-pro/SourceCodePro-Regular.ttf",
        "source-code-pro/SourceCodePro-Bold.ttf",
        "source-code-pro/SourceCodePro-Italic.ttf",
        "source-code-pro/SourceCodePro-BoldItalic.ttf",
    };

    private Fonts() {
    }

    /** Registers every bundled font with the JavaFX toolkit. Failures are skipped silently. */
    public static void load() {
        for (String file : FILES) {
            try (InputStream in = Fonts.class.getResourceAsStream("/com/editora/fonts/" + file)) {
                if (in != null) {
                    Font.loadFont(in, 12);
                }
            } catch (Exception ignored) {
                // A missing/unreadable font just won't be available; keep loading the rest.
            }
        }
    }
}
