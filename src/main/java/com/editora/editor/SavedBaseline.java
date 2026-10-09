package com.editora.editor;

import java.util.Objects;

/**
 * What a buffer compares its text with to know whether it is modified: the saved (or loaded) text's length and
 * {@link TextFingerprint fingerprint} — not the text.
 *
 * <p>A freshly loaded or saved buffer shares one String between its document snapshot and this baseline, so
 * holding it costs nothing and comparing against it is an identity check. Once the buffer is dirty the
 * snapshot has moved on, and the baseline's String would be a second document-sized copy for as long as the
 * tab stays modified. So the baseline is {@link #seal sealed} then: it keeps the fingerprint (computed from
 * the text at that moment if a save or load worker did not supply it) and lets the text go. A sealed baseline
 * answers {@link #matches} by hashing the current text, which happens only when the lengths already agree —
 * an edit back to the saved text, an undo to it, a line-ending conversion — never per keystroke.
 */
final class SavedBaseline {

    private final int length;
    /** The text the baseline was taken from, while it is still the buffer's own snapshot; null once sealed. */
    private String text;
    /** {@link TextFingerprint} of the text; null until sealed when no worker supplied it. */
    private String fingerprint;

    private SavedBaseline(int length, String text, String fingerprint) {
        this.length = length;
        this.text = text;
        this.fingerprint = fingerprint;
    }

    /** A baseline that holds {@code text} until sealed; the fingerprint is computed at sealing. */
    static SavedBaseline of(String text) {
        return new SavedBaseline(text.length(), Objects.requireNonNull(text), null);
    }

    /** A baseline that holds {@code text} until sealed, with its fingerprint already known (a load worker's). */
    static SavedBaseline of(String text, String fingerprint) {
        return new SavedBaseline(text.length(), Objects.requireNonNull(text), fingerprint);
    }

    /** A baseline that never held the text: a save worker's length and fingerprint of what it wrote. */
    static SavedBaseline sealed(int length, String fingerprint) {
        return new SavedBaseline(length, null, Objects.requireNonNull(fingerprint));
    }

    /** Length of the baseline text — the per-keystroke gate: a different length is a different text. */
    int length() {
        return length;
    }

    /** Whether {@code current} is the baseline text: by identity or equality while the text is held, else by hash. */
    synchronized boolean matches(String current) {
        if (current.length() != length) {
            return false;
        }
        if (text != null) {
            return current == text || current.equals(text);
        }
        return fingerprint.equals(TextFingerprint.of(current));
    }

    /**
     * Lets the text go, keeping its fingerprint. Called once the buffer is dirty and its edits have settled:
     * the text is no longer the snapshot, so holding it would be the second copy this class exists to avoid.
     */
    synchronized void seal() {
        if (text == null) {
            return;
        }
        if (fingerprint == null) {
            fingerprint = TextFingerprint.of(text);
        }
        text = null;
    }

    /** Whether the text is still held (tests). */
    /** The saved text while it is still held (before sealing), else null. */
    synchronized String heldText() {
        return text;
    }

    synchronized boolean holdsText() {
        return text != null;
    }

    /** The fingerprint, computing it from the held text if needed (tests/diagnostics). */
    synchronized String fingerprint() {
        if (fingerprint == null) {
            fingerprint = TextFingerprint.of(text);
        }
        return fingerprint;
    }
}
