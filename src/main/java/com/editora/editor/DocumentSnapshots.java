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
 *
 * <p>FX-thread confined, like the document it mirrors.
 */
final class DocumentSnapshots {

    record Snapshot(long version, String text) {}

    private Snapshot current;
    private long materializations;

    private String expected;

    Snapshot get(long version, Supplier<String> textSource) {
        if (current == null || current.version() != version) {
            if (expected != null) {
                current = new Snapshot(version, expected);
                expected = null;
            } else {
                current = new Snapshot(version, Objects.requireNonNull(textSource.get()));
                materializations++;
            }
        }
        return current;
    }

    /**
     * Announces the text the document is about to hold, so a consumer running inside that replacement's
     * change notification is handed this String instead of copying the whole document back out of
     * RichTextFX. Always paired with {@link #seed} once the replacement returns.
     */
    void expect(String text) {
        expected = text;
    }

    /**
     * Installs {@code text} as the snapshot of {@code version} without materializing anything: a load already
     * holds the document as one String, and that same instance then serves as the saved baseline, the first
     * Undo History checkpoint and every whole-document consumer until the first edit.
     */
    void seed(long version, String text) {
        expected = null;
        current = new Snapshot(version, Objects.requireNonNull(text));
    }

    void invalidate() {
        current = null;
    }

    long materializations() {
        return materializations;
    }
}
