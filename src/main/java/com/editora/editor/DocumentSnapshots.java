package com.editora.editor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Keeps one immutable full-text snapshot for a document version.
 *
 * <p>RichTextFX materializes a new {@link String} for a whole-document {@code getText()}. Several
 * independently debounced consumers can run after the same edit burst, so letting each one call it repeats
 * the same O(document) copy. A buffer owns one of these caches and invalidates it synchronously on every
 * plain-text change; consumers then share the first snapshot taken for the new version.
 *
 * <p>Taking a snapshot is O(1): it holds the document version (see {@link DocumentText#of(
 * org.fxmisc.richtext.GenericStyledArea)}), and the String is built the first time any consumer asks
 * {@link Snapshot#text()} — on its worker when it scans off the FX thread, so a typing pause no longer copies
 * the document on the FX thread (perf review 2026-11-23 E2). The cache itself is FX-thread confined; a
 * {@link Snapshot} may be read on any thread.
 */
final class DocumentSnapshots {

    /** One version's text, built at most once on whichever thread reads it first. */
    static final class Snapshot implements DocumentText {
        private final long version;
        private final DocumentText.Memo memo;

        Snapshot(long version, DocumentText.Memo memo) {
            this.version = version;
            this.memo = memo;
        }

        long version() {
            return version;
        }

        @Override
        public String text() {
            return memo.text();
        }

        /** The text when it was built already, else null; builds nothing. */
        @Nullable
        String builtText() {
            return memo.peek();
        }
    }

    private @Nullable Snapshot current;
    private final AtomicLong materializations = new AtomicLong();

    private @Nullable String expected;

    /**
     * The snapshot of {@code version}; on a miss {@code capture} is called here (FX thread) to take an O(1) handle
     * on the document, whose {@code get()} builds the String later, once, on the thread that first asks for it.
     */
    Snapshot get(long version, Supplier<Supplier<String>> capture) {
        if (current == null || current.version() != version) {
            if (expected != null) {
                current = new Snapshot(version, new DocumentText.Memo(expected, null));
                expected = null;
            } else {
                Supplier<String> source = Objects.requireNonNull(capture.get());
                current = new Snapshot(
                        version, new DocumentText.Memo(null, source).onBuilt(materializations::incrementAndGet));
            }
        }
        return current;
    }

    /**
     * Announces the text the document is about to hold, so a consumer running inside that replacement's
     * change notification is handed this String instead of copying the whole document back out of
     * RichTextFX. Always paired with {@link #seed} once the replacement returns.
     */
    void expect(@Nullable String text) {
        expected = text;
    }

    /**
     * Installs {@code text} as the snapshot of {@code version} without materializing anything: a load already
     * holds the document as one String, and that same instance then serves as the saved baseline, the first
     * Undo History checkpoint and every whole-document consumer until the first edit.
     */
    void seed(long version, String text) {
        expected = null;
        current = new Snapshot(version, new DocumentText.Memo(Objects.requireNonNull(text), null));
    }

    void invalidate() {
        current = null;
    }

    /**
     * Drops the snapshot unless it is the very String {@code retained} — the saved baseline of a clean buffer,
     * which the first snapshot aliases: letting that one go would free nothing and cost a whole-document copy
     * on the next read. A snapshot whose String was never built holds only the document version the area
     * holds too, so it is kept as well. Returns whether anything was released.
     */
    boolean releaseUnless(@Nullable String retained) {
        String built = current == null ? null : current.builtText();
        if (built == null || built == retained) {
            return false;
        }
        current = null;
        return true;
    }

    /** Whole-document Strings built through this cache, on any thread. */
    long materializations() {
        return materializations.get();
    }
}
