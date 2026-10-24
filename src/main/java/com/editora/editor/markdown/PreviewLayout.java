package com.editora.editor.markdown;

import java.util.Locale;

/**
 * How one file's preview is laid out, the way the user left it: the preview's zoom factor and where the
 * divider of the Split view sits (0–1, the editor's share). Remembered per file next to its view mode
 * ({@code WorkspaceState.markdownPreviewLayouts}), as the text {@code "<zoom>;<divider>"}.
 *
 * <p>Both used to be reset: the divider to the middle on every switch of mode, the zoom when the tab closed.
 */
public record PreviewLayout(double zoom, double divider) {

    public static final double MIN_ZOOM = 0.5;
    public static final double MAX_ZOOM = 3.0;
    public static final PreviewLayout DEFAULT = new PreviewLayout(1.0, 0.5);

    public PreviewLayout {
        zoom = Double.isFinite(zoom) ? Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom)) : 1.0;
        divider = Double.isFinite(divider) ? Math.max(0.1, Math.min(0.9, divider)) : 0.5;
    }

    /** Whether nothing differs from a fresh preview — then there is nothing worth storing. */
    public boolean isDefault() {
        return Math.abs(zoom - 1.0) < 0.005 && Math.abs(divider - 0.5) < 0.005;
    }

    /** The stored form: two decimals each, locale-independent. */
    public String text() {
        return String.format(Locale.ROOT, "%.2f;%.2f", zoom, divider);
    }

    /** Reads {@link #text()}; anything else (null, an older or damaged value) is {@link #DEFAULT}. */
    public static PreviewLayout parse(String text) {
        if (text == null) {
            return DEFAULT;
        }
        String[] parts = text.split(";");
        if (parts.length != 2) {
            return DEFAULT;
        }
        try {
            return new PreviewLayout(Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip()));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }
}
