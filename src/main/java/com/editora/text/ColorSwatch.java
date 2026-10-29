package com.editora.text;

import java.util.Locale;

/**
 * One colour literal a language server found ({@code textDocument/documentColor}, HTML review D9): where it starts
 * (0-based line and column) and the colour, each channel 0–1. Kept free of lsp4j so the editor can draw it.
 */
public record ColorSwatch(int line, int column, double red, double green, double blue, double alpha) {

    /** {@code rrggbbaa} in lower-case hex — the colour as a CSS name and a style-class suffix. */
    public String hex() {
        return String.format(
                Locale.ROOT, "%02x%02x%02x%02x", channel(red), channel(green), channel(blue), channel(alpha));
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, Double.isNaN(v) ? 0 : v)) * 255);
    }
}
