package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javafx.scene.Parent;

/**
 * Makes a tab character as wide as the buffer's tab size says. JavaFX lays text out with tab stops every
 * <b>8</b> columns unless told otherwise, and the size is a property of each paragraph's {@code TextFlow}
 * ({@code -fx-tab-size}), which is not inherited — so it cannot ride on the area's inline font style. A
 * one-rule stylesheet on the buffer's view host reaches every paragraph of both split panes instead.
 */
final class TabStops {

    /** Marks our generated sheet among a node's stylesheets, so a new size replaces the old one. */
    static final String PREFIX = "data:text/css;charset=utf-8;base64,";

    /** JavaFX's own default and the widest stop we honour (EditorConfig allows any positive integer). */
    static final int MAX = 64;

    private TabStops() {}

    /** The rule for {@code tabSize} columns (clamped to 1..{@link #MAX}). Pure. */
    static String css(int tabSize) {
        return ".paragraph-text { -fx-tab-size: " + Math.max(1, Math.min(tabSize, MAX)) + "; }";
    }

    /** {@link #css} as a {@code data:} stylesheet URL. Pure. */
    static String sheet(int tabSize) {
        return PREFIX + Base64.getEncoder().encodeToString(css(tabSize).getBytes(StandardCharsets.UTF_8));
    }

    /** Applies {@code tabSize} to every text paragraph under {@code host}; no restyle when it is unchanged. */
    static void apply(Parent host, int tabSize) {
        String sheet = sheet(tabSize);
        var sheets = host.getStylesheets();
        if (sheets.contains(sheet)) {
            return;
        }
        sheets.removeIf(s -> s.startsWith(PREFIX));
        sheets.add(sheet);
    }
}
