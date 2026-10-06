package com.editora.editor;

import java.util.HashMap;
import java.util.Map;

import javafx.scene.paint.Color;

/**
 * The colours of TODO-pattern marks, parsed once per distinct configured value.
 *
 * <p>A mark carries its colours as the web strings from settings. The overlay and the minimap paint every
 * visible mark on each repaint, and {@link Color#web} is a string parse — so the handful of distinct values
 * are resolved here once and shared. FX-thread only.
 */
final class TodoColors {

    /** Used for a value that is not a valid colour (the default amber). */
    private static final String FALLBACK = "#E5C07B";

    /** Far more than the patterns anyone configures; reached only by a stream of malformed values. */
    private static final int MAX = 256;

    private static final Map<String, Color> SOLID = new HashMap<>();
    private static final Map<String, Color> WASH = new HashMap<>();

    private TodoColors() {}

    /** {@code web} as an opaque colour. */
    static Color solid(String web) {
        return resolve(SOLID, web, 1.0);
    }

    /** {@code web} at {@code alpha} — one alpha per caller, since the cache is keyed by the string alone. */
    static Color wash(String web, double alpha) {
        return resolve(WASH, web, alpha);
    }

    private static Color resolve(Map<String, Color> cache, String web, double alpha) {
        Color color = cache.get(web);
        if (color == null) {
            try {
                color = Color.web(web, alpha);
            } catch (RuntimeException invalid) {
                color = Color.web(FALLBACK, alpha);
            }
            if (cache.size() >= MAX) {
                cache.clear();
            }
            cache.put(web, color);
        }
        return color;
    }
}
