package com.editora.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure helper for persisting one buffer's notes, bookmarks or breakpoints into a store that other buffers on
 * the same file write too (a second window on the same project bucket).
 *
 * <p>Each buffer holds its own copy of a file's marks, loaded when the file was opened. Writing that copy
 * back as the file's whole list deleted every mark another buffer had added since. A buffer may only remove
 * what it removed itself: an entry in the store that this buffer does not hold is dropped only when the
 * buffer has <em>seen</em> it — it was in the store the last time the buffer read or wrote the file's list.
 * An entry it has never seen is someone else's and stays.
 */
final class MarkMerge {

    /**
     * One rewrite of a file's list in a bucket of a mark store, for the other windows. {@code bucket} is the
     * live per-project map itself — windows on the same project (or all on no project) hold the same
     * instance, so identity says whether a window is affected. {@code fileKey} is the key within it;
     * {@code null} means "several files" (a rename re-keyed a folder).
     */
    record Change(Kind kind, Map<String, ?> bucket, String fileKey) {}

    /** Which store a {@link Change} is about. */
    enum Kind {
        NOTES,
        BOOKMARKS,
        BREAKPOINTS
    }

    private MarkMerge() {}

    /** The keys of {@code list} ({@code null} → none). */
    static <T, K> Set<K> keys(List<T> list, Function<T, K> key) {
        Set<K> out = new HashSet<>();
        if (list != null) {
            for (T item : list) {
                if (item != null) {
                    out.add(key.apply(item));
                }
            }
        }
        return out;
    }

    /**
     * The entries of {@code stored} that belong to another buffer: not held by this one ({@code mine}) and
     * never seen by it ({@code seen}; {@code null} = it has seen nothing). In stored order.
     */
    static <T, K> List<T> foreign(List<T> stored, Set<K> seen, List<T> mine, Function<T, K> key) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        Set<K> held = keys(mine, key);
        List<T> out = null;
        for (T item : stored) {
            if (item == null) {
                continue;
            }
            K k = key.apply(item);
            if (!held.contains(k) && (seen == null || !seen.contains(k))) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(item);
            }
        }
        return out == null ? List.of() : out;
    }

    /**
     * What this buffer writes for the file: its own entries plus the {@link #foreign} ones. Returns
     * {@code mine} itself when there are none, so the common single-buffer case is exactly the old write.
     */
    static <T, K> List<T> withForeign(List<T> stored, Set<K> seen, List<T> mine, Function<T, K> key) {
        List<T> foreign = foreign(stored, seen, mine, key);
        if (foreign.isEmpty()) {
            return mine;
        }
        List<T> out = new ArrayList<>(mine);
        out.addAll(foreign);
        return out;
    }
}
