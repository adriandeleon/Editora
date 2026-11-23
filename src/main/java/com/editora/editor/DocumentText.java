package com.editora.editor;

import java.util.function.Supplier;

import org.fxmisc.richtext.GenericStyledArea;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.jspecify.annotations.Nullable;

/**
 * One version of a document whose whole text may be read on any thread. Taking it on the FX thread is O(1): it
 * holds RichTextFX's immutable {@link ReadOnlyStyledDocument} for that version, and the O(document) String is
 * built the first time {@link #text()} is asked — on the worker that scans it, not on the FX thread (perf review
 * 2026-11-23 E2). Every reader of the same handle shares that one String.
 *
 * <p>Thread safety, checked in the fork's bytecode ({@code richtextfx-0.11.7-inlay.3}): the document, its
 * finger tree and its paragraphs are immutable (final fields); their only writes after construction are lazy
 * caches — {@code ReadOnlyStyledDocument.text}/{@code paragraphs}, {@code Paragraph.text}/{@code length} — of
 * immutable or final-field-only values computed identically by any thread, so racing an FX-thread
 * {@code area.getText()} is benign. The memo here makes one handle build its String once.
 */
@FunctionalInterface
public interface DocumentText {

    /** The whole text of this version. Safe on any thread; the first call may be O(document). */
    String text();

    /** A handle over a String already built. */
    static DocumentText of(String text) {
        return new Memo(text, null);
    }

    /**
     * The O(1) handle on {@code area}'s current document. FX thread only (like any read of the area); the
     * returned handle may then be read anywhere.
     */
    static DocumentText of(GenericStyledArea<?, ?, ?> area) {
        ReadOnlyStyledDocument<?, ?, ?> doc = area.getContent().snapshot();
        doc.getParagraphs(); // build the lazy paragraph view here, so a worker only ever reads it
        return new Memo(null, doc::getText);
    }

    /** {@code source} read lazily, at most once, on whichever thread asks first. */
    static DocumentText lazy(Supplier<String> source) {
        return new Memo(null, source);
    }

    /** A lazily-built, thread-safe text memo. */
    final class Memo implements DocumentText {
        private @Nullable String text;
        private @Nullable Supplier<String> source;
        private @Nullable Runnable onBuilt;

        Memo(@Nullable String text, @Nullable Supplier<String> source) {
            this.text = text;
            this.source = source;
        }

        /** Runs once, on the building thread, when {@link #text()} materializes the String. */
        Memo onBuilt(Runnable r) {
            onBuilt = r;
            return this;
        }

        @Override
        public synchronized String text() {
            String t = text;
            if (t == null) {
                Supplier<String> s = java.util.Objects.requireNonNull(source);
                t = java.util.Objects.requireNonNull(s.get());
                text = t;
                source = null; // the document version is no longer needed once its text is
                Runnable built = onBuilt;
                onBuilt = null;
                if (built != null) {
                    built.run();
                }
            }
            return t;
        }

        /** The text if it was built already, else null; never builds it. */
        synchronized @Nullable String peek() {
            return text;
        }
    }
}
