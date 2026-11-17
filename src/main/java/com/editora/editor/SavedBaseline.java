package com.editora.editor;

import java.util.Objects;

import com.editora.editops.TextFingerprint;
import org.jspecify.annotations.Nullable;

/**
 * What a buffer compares its text with to know whether it is modified: the saved (or loaded) text's length and
 * {@link TextFingerprint fingerprint} — not the text.
 *
 * <p>A freshly loaded or saved buffer shares one String between its document snapshot and this baseline, so
 * holding it costs nothing and comparing against it is an identity check. Once the buffer is dirty the
 * snapshot has moved on, and the baseline's String would be a second document-sized copy for as long as the
 * tab stays modified. So the baseline is sealed then: it keeps the fingerprint and lets the text go. The
 * fingerprint comes from the save or load worker, or is worked out off the JavaFX thread from the held text
 * ({@link #knowFingerprint}) before the text is let go ({@link #sealIfFingerprinted}). A sealed baseline answers
 * {@link #matches} by hashing the current text, which the buffer does off the JavaFX thread (PERF-01, Pauta
 * 6.0.1): on the FX thread it asks {@link #matchesWithoutHashing}, which says when a hash is needed.
 *
 * <p>The fields are volatile and nothing here locks: a hash on a worker never makes the FX thread wait. The
 * fingerprint is written before the text is let go, so a baseline without its text always has its fingerprint.
 */
final class SavedBaseline {

    private final int length;
    /** The text the baseline was taken from, while it is still the buffer's own snapshot; null once sealed. */
    private volatile @Nullable String text;
    /** {@link TextFingerprint} of the text; null until worked out when no worker supplied it. */
    private volatile @Nullable String fingerprint;

    private SavedBaseline(int length, @Nullable String text, @Nullable String fingerprint) {
        this.length = length;
        this.text = text;
        this.fingerprint = fingerprint;
    }

    /** A baseline that holds {@code text} until sealed; the fingerprint is worked out before sealing. */
    static SavedBaseline of(String text) {
        return new SavedBaseline(text.length(), Objects.requireNonNull(text), null);
    }

    /** A baseline that holds {@code text} until sealed, with its fingerprint already known (a load worker's). */
    static SavedBaseline of(String text, @Nullable String fingerprint) {
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

    /**
     * Whether {@code current} is the baseline text, answered without hashing: by length, then by identity or
     * equality while the text is held. Null when only a hash can tell (the baseline is sealed and the lengths
     * agree): the FX thread then asks {@link #matches} off the thread.
     */
    @Nullable
    Boolean matchesWithoutHashing(String current) {
        if (current.length() != length) {
            return Boolean.FALSE;
        }
        String held = text;
        if (held != null) {
            return current == held || current.equals(held);
        }
        return null;
    }

    /** Whether {@code current} is the baseline text, hashing it when sealed: never on the FX thread. */
    boolean matches(String current) {
        Boolean cheap = matchesWithoutHashing(current);
        if (cheap != null) {
            return cheap;
        }
        return TextFingerprint.of(current).equals(fingerprint); // sealed, so the fingerprint is known
    }

    /**
     * Lets the text go, keeping its fingerprint, which is worked out here when no worker supplied it: the hash
     * runs on the calling thread, so the buffer calls {@link #sealIfFingerprinted} on the FX thread instead.
     */
    void seal() {
        String held = text;
        if (held == null) {
            return;
        }
        if (fingerprint == null) {
            fingerprint = TextFingerprint.of(held);
        }
        text = null;
    }

    /**
     * Lets the text go when its fingerprint is known, without hashing; false when the fingerprint must first be
     * worked out off the FX thread (from {@link #heldText}, then {@link #knowFingerprint}).
     */
    boolean sealIfFingerprinted() {
        if (text == null) {
            return true;
        }
        if (fingerprint == null) {
            return false;
        }
        text = null;
        return true;
    }

    /** The fingerprint a worker worked out from {@code held}, kept if {@code held} is still this baseline's text. */
    void knowFingerprint(String held, String heldFingerprint) {
        if (fingerprint == null && text == held) {
            fingerprint = heldFingerprint;
        }
    }

    /** Whether the fingerprint is known (from a worker, or worked out already). */
    boolean hasFingerprint() {
        return fingerprint != null;
    }

    /** The saved text while it is still held (before sealing), else null. */
    @Nullable
    String heldText() {
        return text;
    }

    /** Whether the text is still held (tests). */
    boolean holdsText() {
        return text != null;
    }

    /** The fingerprint, computing it from the held text if needed (tests/diagnostics). */
    String fingerprint() {
        String known = fingerprint;
        if (known == null) {
            known = TextFingerprint.of(Objects.requireNonNull(text, "a baseline without a fingerprint holds its text"));
            fingerprint = known;
        }
        return known;
    }
}
