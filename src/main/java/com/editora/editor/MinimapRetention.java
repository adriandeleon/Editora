package com.editora.editor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The content pixels of recently shown minimaps, kept on the CPU while their tabs are in the background.
 *
 * <p>A background tab drops its minimap image so the texture it pins is returned to Prism (see
 * {@code docs/performance.md}, "Bound retained GPU textures"). Dropping the image used to mean a full
 * content render on the next switch to the tab — every paragraph scanned and a forced full-scene layout
 * for the snapshot — which was the largest app frame of a tab switch. The pixels themselves are a few
 * hundred KB and nothing about them is GPU-bound, so they are copied out here before the image goes and
 * written back into a fresh image when the tab returns: a texture upload instead of a render.
 *
 * <p>Bounded both ways, like every image cache: at most {@link #MAX_ENTRIES} minimaps and
 * {@link ImageCacheBudget#MINIMAP_BUDGET_BYTES} in all, evicting the least recently shown. An entry is
 * only as valid as its minimap says: the minimap discards it when the document, the colours, the tab size
 * or the column size changed while hidden.
 *
 * <p>FX-thread only, like the minimaps it serves.
 */
final class MinimapRetention {

    /** How many hidden tabs keep their minimap pixels; the rest re-render when shown. */
    static final int MAX_ENTRIES = 16;

    /** A content image's pixels, with the logical canvas size they were snapshotted at. */
    record Entry(int[] pixels, int pixelWidth, int pixelHeight, double canvasWidth, double canvasHeight) {
        long bytes() {
            return pixels.length * 4L;
        }
    }

    static final MinimapRetention SHARED = new MinimapRetention(MAX_ENTRIES, ImageCacheBudget.MINIMAP_BUDGET_BYTES);

    private final int maxEntries;
    private final long budgetBytes;
    private final Map<Object, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);

    MinimapRetention(int maxEntries, long budgetBytes) {
        this.maxEntries = Math.max(1, maxEntries);
        this.budgetBytes = Math.max(0, budgetBytes);
    }

    /** Keeps {@code entry} for {@code key}, evicting the least recently used entries past either bound. */
    void put(Object key, Entry entry) {
        entries.put(key, entry);
        Iterator<Map.Entry<Object, Entry>> it = entries.entrySet().iterator();
        while (entries.size() > maxEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
        ImageCacheBudget.trim(entries, Entry::bytes, budgetBytes);
    }

    /** The entry kept for {@code key}, now the most recently used, or {@code null}. */
    @Nullable
    Entry get(Object key) {
        return entries.get(key);
    }

    /** Drops the entry for {@code key}, if any. */
    void remove(Object key) {
        entries.remove(key);
    }

    int size() {
        return entries.size();
    }

    long bytes() {
        long total = 0;
        for (Entry e : entries.values()) {
            total += e.bytes();
        }
        return total;
    }
}
