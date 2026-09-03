package com.editora.editor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps one immutable full-text snapshot for a document version.
 *
 * <p>RichTextFX materializes a new {@link String} for a whole-document {@code getText()}. Several
 * independently debounced consumers can run after the same edit burst, so letting each one call it repeats
 * the same O(document) FX-thread copy. A buffer owns one of these caches and invalidates it synchronously on
 * every plain-text change; consumers then share the first snapshot built for the new version.
 */
final class DocumentSnapshots {

    record Snapshot(long version, String text) {}

    private Snapshot current;
    private long materializations;

    Snapshot get(long version, Supplier<String> textSource) {
        if (current == null || current.version() != version) {
            current = new Snapshot(version, Objects.requireNonNull(textSource.get()));
            materializations++;
        }
        return current;
    }

    void invalidate() {
        current = null;
    }

    long materializations() {
        return materializations;
    }
}
