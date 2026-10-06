package com.editora.editor;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which preview <em>surface</em> a rendered Mermaid diagram or Typst document belongs to, so the static
 * render caches can keep one render per surface instead of one per edit.
 *
 * <p>Those caches are keyed by a hash of the source. A live preview re-renders after every settled edit, and
 * each render is a new source, so a tab being edited used to add an entry per pause — whole multi-page
 * documents, each page a decoded image — until a count or page cap pushed the oldest out, and nothing was
 * released when the tab closed. A surface is the one place such a render is shown; only its newest render can
 * be shown again, so the previous one is dropped when the next lands and all of them when the buffer goes.
 */
final class PreviewSurfaces {

    private PreviewSurfaces() {}

    /** The Mermaid preview of a buffer: per file, so a retained render survives the tab being reopened. */
    static String mermaid(Path path, Object buffer) {
        return path != null ? path.toString() : ("mmd@" + System.identityHashCode(buffer));
    }

    /** The key a Typst preview's last good pages are retained under while a newer render is pending. */
    static String typstRetain(Path path, Object buffer) {
        return path != null ? path.toString() : ("untitled@" + System.identityHashCode(buffer));
    }

    /** The Typst preview of a buffer. */
    static String typst(Object buffer) {
        return "typst@" + System.identityHashCode(buffer);
    }

    /** Drops everything the render caches hold for a buffer that is being disposed. */
    static void release(Path path, Object buffer) {
        MermaidImages.release(mermaid(path, buffer));
        TypstImages.release(typstRetain(path, buffer), typst(buffer));
    }

    /** The cache key of the newest render of each surface. Thread-safe: renders land on worker threads. */
    static final class Newest {
        private final Map<String, String> bySurface = new ConcurrentHashMap<>();

        /**
         * Records {@code cacheKey} as the newest render of {@code surface} and returns the key it replaces —
         * the cache entry to drop — or null when there was none, it is the same render, or the render belongs
         * to no surface (a diagram block inside a Markdown preview, bounded by the cache's own LRU).
         */
        String record(String surface, String cacheKey) {
            if (surface == null) {
                return null;
            }
            String previous = bySurface.put(surface, cacheKey);
            return previous == null || previous.equals(cacheKey) ? null : previous;
        }

        /** Forgets {@code surface}, returning the cache key of its newest render (null when it had none). */
        String release(String surface) {
            return surface == null ? null : bySurface.remove(surface);
        }

        int size() {
            return bySurface.size();
        }
    }
}
