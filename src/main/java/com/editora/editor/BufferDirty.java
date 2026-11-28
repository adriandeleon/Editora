package com.editora.editor;

import javafx.application.Platform;

import com.editora.editops.LineEndings;
import com.editora.editops.TextFingerprint;
import com.editora.editor.fold.NarrowCarry;
import com.editora.perf.OffFx;
import org.jspecify.annotations.Nullable;

/**
 * The dirty marker of one buffer: whether its text differs from the last saved or loaded baseline, decided cheaply
 * per edit (lengths), then by fingerprint off the FX thread when typing settles, and the bookkeeping after a save
 * or a reload.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The baseline itself stays on the buffer, where tests read it.
 */
final class BufferDirty {

    private final EditorBuffer eb;

    BufferDirty(EditorBuffer eb) {
        this.eb = eb;
    }

    /** True when the current content has no durable identity even if it equals the in-memory baseline. */
    boolean forcedDirty;
    /** The line ending the file had when loaded or last saved; a different one is an unsaved change. */
    String cleanLineEnding = LineEndings.LF;
    /** Length of {@link EditorBuffer#getContent()} without building it — the per-keystroke dirty-check gate. */
    private int contentLength() {
        if (eb.logView.filtered()) {
            return eb.logView.fullLength();
        }
        return eb.area.getLength() + NarrowCarry.heldLength(eb.narrowing.narrowHeld);
    }
    /** Exact: an edit that left a dirty buffer at its saved length is compared here if it has not been yet. */
    boolean isDirty() {
        resolveDirty();
        return dirtyUnresolved ? differsFromSaved() : eb.dirty.get(); // off the FX thread: answer, publish later
    }
    /** True while {@link EditorBuffer#dirty} is carried over an edit that may have restored the saved text. */
    private boolean dirtyUnresolved;
    /**
     * The per-edit dirty check. A length (or line-ending) difference decides without reading the text. At
     * the saved length the text has to be compared, which builds the whole document: a clean buffer does
     * that at once — it must never show as modified when it is not — and so does a dirty buffer that a
     * length-changing edit (a deletion, an undo of an insert) brought back to the saved length: that is how
     * a user lands on the saved text, and the tab marker, the Save button and the Git gutter's reset
     * ({@code GitGutterLines.cleaned}) expect the flag to clear then, not 150 ms later. A dirty buffer edited
     * with a {@code sameLength} change stays dirty and is compared when the edit settles (or when
     * {@link #isDirty()} is asked), so a run of same-length edits (move line up/down, overwrite, transpose)
     * no longer copies the document on every one of them — those runs, not a single landing, were the cost.
     * Against a sealed baseline the comparison is a hash, worked out off the FX thread ({@link #textMatchesSaved}).
     */
    void dirtyAfterEdit(boolean sameLength) {
        dirtyUnresolved = sameLength
                && eb.dirty.get()
                && !forcedDirty
                && eb.lineEnding.equals(cleanLineEnding)
                && eb.baseline != null
                && contentLength() == eb.baseline.length();
        if (!dirtyUnresolved) {
            eb.dirty.set(differsFromSaved());
        }
    }

    void resolveDirty() {
        if (dirtyUnresolved && Platform.isFxApplicationThread()) { // the property is only set on its thread
            dirtyUnresolved = false;
            eb.dirty.set(differsFromSaved());
        }
    }
    /**
     * Once the edits of a dirty buffer have settled, the baseline's text is no longer the snapshot and would be
     * a second document-sized String for as long as the tab stays modified: keep its fingerprint only. A
     * fingerprint no worker supplied is worked out off the FX thread first (PERF-01, Pauta 6.2.0); the text is let
     * go when it lands, if the buffer is still dirty then.
     */
    void sealBaselineIfDirty() {
        SavedBaseline b = eb.baseline;
        if (b == null || !eb.dirty.get() || dirtyUnresolved || b.sealIfFingerprinted()) {
            return;
        }
        String held = b.heldText();
        if (held == null || fingerprinting == b) {
            return;
        }
        fingerprinting = b;
        OffFx.then(EditorBuffer.DIRTY_HASH_SITE, () -> TextFingerprint.of(held), done -> {
            if (fingerprinting == b) {
                fingerprinting = null;
            }
            String fp = done.value();
            if (fp != null && eb.baseline == b && !eb.disposed) {
                b.knowFingerprint(held, fp);
                sealBaselineIfDirty();
            }
        });
    }
    /** The baseline whose fingerprint is being worked out off the FX thread, so a second settle starts none. */
    @Nullable
    private SavedBaseline fingerprinting;
    /** What the last hash made off the FX thread found: the text at {@code version} against {@code baseline}. */
    private record SavedHash(long version, SavedBaseline baseline, boolean matches) {}

    @Nullable
    private SavedHash savedHash;

    @Nullable
    private SavedBaseline hashingBaseline;
    /**
     * Whether the whole text is the saved text, with {@code b}'s length already equal. Without a hash when the
     * baseline holds its text. Against a sealed baseline the FX thread never hashes (PERF-01, Pauta 6.2.0): it
     * answers from the last hash made off the thread for this document version, and otherwise says "differs" —
     * the dirty state the cheap checks give, never "clean" for text that differs — and hashes a snapshot off the
     * thread; when that lands for the same version and baseline, the flag is set again from it, so an undo back
     * to the saved text clears it then.
     */
    private boolean textMatchesSaved(SavedBaseline b) {
        String current = eb.getContent();
        Boolean cheap = b.matchesWithoutHashing(current);
        if (cheap != null) {
            return cheap;
        }
        if (!Platform.isFxApplicationThread()) {
            return b.matches(current);
        }
        long version = eb.docVersion;
        SavedHash known = savedHash;
        if (known != null && known.version() == version && known.baseline() == b) {
            return known.matches();
        }
        if (eb.hashingVersion != version || hashingBaseline != b) {
            eb.hashingVersion = version;
            hashingBaseline = b;
            OffFx.then(EditorBuffer.DIRTY_HASH_SITE, () -> b.matches(current), done -> {
                if (eb.hashingVersion == version && hashingBaseline == b) {
                    eb.hashingVersion = -1;
                    hashingBaseline = null;
                }
                Boolean same = done.value();
                if (same == null || eb.disposed || eb.docVersion != version || eb.baseline != b) {
                    return; // an edit or a save since: its own check asks again
                }
                savedHash = new SavedHash(version, b, same);
                dirtyUnresolved = false;
                eb.dirty.set(differsFromSaved());
            });
            known = savedHash; // a test that runs OffFx in place has it already
            if (known != null && known.version() == version && known.baseline() == b) {
                return known.matches();
            }
        }
        return false;
    }
    /** Whether the saved baseline still holds its text rather than a fingerprint alone (tests). */
    boolean baselineHoldsText() {
        return eb.baseline != null && eb.baseline.holdsText();
    }
    /** Marks the current content as the saved baseline (after load/save); clears the dirty flag. */
    void markClean() {
        // The whole document, so narrowing never fakes a dirty flag. Widened, this is the snapshot String
        // itself — shared, not copied — and it stays shared until the next edit settles.
        eb.baseline = SavedBaseline.of(eb.getContent());
        cleanLineEnding = eb.lineEnding;
        forcedDirty = false;
        eb.dirty.set(false);
    }
    /** Whether the buffer differs from what is on disk: its text, line ending or encoding, or a forced flag. */
    boolean differsFromSaved() {
        return forcedDirty
                || !eb.lineEnding.equals(cleanLineEnding)
                || (eb.userCharset != null && !eb.userCharset.equals(eb.detectedCharset))
                || (eb.baseline != null && (contentLength() != eb.baseline.length() || !textMatchesSaved(eb.baseline)));
    }
    /** Marks content as not durably saved, even when it still equals its in-memory baseline. */
    void markUnsaved() {
        forcedDirty = true;
        eb.dirty.set(true);
    }
    /**
     * Acknowledges exactly what an asynchronous save wrote — its text and its line ending — so an edit or a
     * line-ending conversion made while the write was in flight is still unsaved, and undoing either is clean.
     */
    void acknowledgeSavedContent(String savedContent, String savedLineEnding) {
        acknowledgeSavedBaseline(SavedBaseline.of(savedContent == null ? "" : savedContent), savedLineEnding);
    }
    /**
     * As above, from what the save worker kept of the text it wrote: its {@link EditorBuffer#docVersion() document version},
     * length and {@link TextFingerprint fingerprint}, never the text. When the document has not changed since
     * the save was captured, the current snapshot <em>is</em> the saved text and is shared as the baseline;
     * otherwise the baseline starts sealed and the comparison is by hash.
     */
    void acknowledgeSavedContent(
            long savedVersion, int savedLength, String savedTextFingerprint, String savedLineEnding) {
        SavedBaseline saved = savedVersion == eb.docVersion
                        && eb.narrowing.narrowHeld == null
                        && !eb.logView.filtered()
                        && eb.area.getLength() == savedLength
                ? SavedBaseline.of(eb.documentTextSnapshot(), savedTextFingerprint)
                : SavedBaseline.sealed(savedLength, savedTextFingerprint);
        acknowledgeSavedBaseline(saved, savedLineEnding);
    }

    private void acknowledgeSavedBaseline(SavedBaseline saved, String savedLineEnding) {
        eb.baseline = saved;
        boolean current = savedLineEnding == null || savedLineEnding.equals(eb.getLineEnding());
        cleanLineEnding = current ? eb.lineEnding : savedLineEnding;
        forcedDirty = !current && eb.eolOverride != null; // a rule that arrived mid-save: no converting back to it
        eb.dirty.set(differsFromSaved());
        eb.gitLines.savedWithPendingEdits(); // a no-op when that left the buffer clean
    }
}
