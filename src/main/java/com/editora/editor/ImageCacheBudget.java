package com.editora.editor;

import java.util.Iterator;
import java.util.Map;
import java.util.function.ToLongFunction;

import javafx.scene.image.Image;

/**
 * A byte budget for the decoded-image caches ({@link PreviewImageLoader}, {@link DiagramImages},
 * {@link MathImages}).
 *
 * <p>Those caches were bounded by <em>entry count</em>, which is not a memory bound when the entries
 * differ in size by three orders of magnitude. A shields.io badge is a few KB; a
 * {@code ![](screenshot.png)} in someone's Markdown is whatever they committed, and every decoded
 * {@link Image} pins a Prism texture. Exhausting the texture pool is the documented black-window failure
 * (see the dist profile's {@code prism.maxvram}), so the cap has to be in bytes. Count caps stay as the
 * cheap first line; this trims after them. Same lesson as the Typst page cache (#461), which bounds pages
 * rather than entries.
 *
 * <p>Budgets are per cache and deliberately loose: they must never bind in ordinary use (a preview full of
 * badges is a few MB), only stop one pathological document from filling VRAM.
 */
final class ImageCacheBudget {

    /** Preview images are user content of arbitrary size — the one cache that really needs this. */
    static final long PREVIEW_BUDGET_BYTES = 192L * 1024 * 1024;

    /** Rendered diagrams: large, but generated from source the user is looking at. */
    static final long DIAGRAM_BUDGET_BYTES = 128L * 1024 * 1024;

    /** Formulas are small; this is a backstop, not a working limit. */
    static final long MATH_BUDGET_BYTES = 32L * 1024 * 1024;

    private ImageCacheBudget() {}

    /**
     * Approximate resident cost of a decoded image: 4 bytes per pixel, which is what the ARGB backing
     * buffer and its GPU texture each cost. Returns 0 for null or a not-yet-decoded image (dimensions
     * read as 0), so an entry can never be weighed as negative or NaN.
     */
    static long footprint(Image image) {
        if (image == null) {
            return 0;
        }
        double w = image.getWidth();
        double h = image.getHeight();
        if (!(w > 0) || !(h > 0)) { // also false for NaN
            return 0;
        }
        return (long) w * (long) h * 4L;
    }

    /**
     * Evicts eldest-first until the cache's total weight fits {@code budget}, returning the total that
     * remains. The caller must hold the cache's monitor (these are {@code Collections.synchronizedMap}
     * wrappers, whose mutex is the wrapper itself).
     *
     * <p>The map must be in <em>access order</em>, so iteration yields least-recently-used first — the same
     * order {@code removeEldestEntry} evicts in, which keeps the two caps consistent with each other.
     * The most recent entry is never evicted even if it alone exceeds the budget: it is what the caller
     * just rendered and is about to display, and dropping it would re-render forever.
     */
    static <K, V> long trim(Map<K, V> cache, ToLongFunction<V> weigh, long budget) {
        long total = 0;
        for (V v : cache.values()) {
            total += weigh.applyAsLong(v);
        }
        if (total <= budget) {
            return total;
        }
        Iterator<Map.Entry<K, V>> it = cache.entrySet().iterator();
        while (total > budget && cache.size() > 1 && it.hasNext()) {
            total -= weigh.applyAsLong(it.next().getValue());
            it.remove();
        }
        return total;
    }
}
