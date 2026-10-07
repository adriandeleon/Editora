package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.PathDisplay;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.editorconfig.EditorConfigProperties;

import static com.editora.i18n.Messages.tr;

/**
 * What a save changed in the file beyond the user's own edits, so the status can say it instead of a bare
 * "Saved": an EditorConfig rule that re-encoded the file (added or removed a byte-order mark, changed the
 * charset or byte order) or left its trailing line breaks out, and mixed line endings that were made uniform.
 *
 * @param mixed how the file's mixed line endings were treated
 * @param source the mixed file as it was loaded, or null
 * @param lineEnding the line ending written on every line when {@link #mixed} normalised
 * @param charsetFrom the charset the file had on disk when the save changes it, else null
 * @param charsetTo the charset it has now
 * @param charsetRule the {@code charset} value that asked for it
 * @param newlinesDropped trailing line breaks that {@code insert_final_newline = false} left out
 */
record SaveNotes(
        MixedLineEndings.Decision mixed,
        MixedLineEndings.Source source,
        String lineEnding,
        String charsetFrom,
        String charsetTo,
        String charsetRule,
        int newlinesDropped) {

    static final SaveNotes NONE = new SaveNotes(MixedLineEndings.Decision.NORMAL, null, null, null, null, null, 0);

    /** A byte-preserving save of an unedited mixed file: nothing was changed, so there is nothing to say. */
    static SaveNotes keptBytes(MixedLineEndings.Source source) {
        return new SaveNotes(MixedLineEndings.Decision.KEEP_BYTES, source, null, null, null, null, 0);
    }

    /**
     * The notes for an ordinary (encoding) save. Pure.
     *
     * @param content the document text, in the editor's {@code \n} form
     * @param written the text after the EditorConfig transforms, in the line ending being written
     * @param onDisk the charset the file was read in (or last saved in)
     * @param effective the charset this save writes
     * @param reencodes false when the charset is a stand-in or the save fell back to UTF-8 — both are
     *     reported in their own way
     */
    static SaveNotes of(
            MixedLineEndings.Decision mixed,
            MixedLineEndings.Source source,
            String lineEnding,
            String content,
            String written,
            EditorConfigProperties rules,
            String onDisk,
            String effective,
            boolean reencodes) {
        boolean charsetChanged = reencodes && onDisk != null && effective != null && !onDisk.equals(effective);
        int dropped = Boolean.FALSE.equals(rules.insertFinalNewline())
                ? Math.max(0, terminators(content) - terminators(written))
                : 0;
        return new SaveNotes(
                mixed,
                source,
                lineEnding,
                charsetChanged ? onDisk : null,
                charsetChanged ? effective : null,
                charsetChanged ? (rules.charset() == null ? effective : rules.charset()) : null,
                dropped);
    }

    /** Line terminators in {@code text}: a {@code \r\n} pair is one. */
    static int terminators(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                count++;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return count;
    }

    boolean keepsBytes() {
        return mixed == MixedLineEndings.Decision.KEEP_BYTES;
    }

    boolean normalisesMixed() {
        return mixed == MixedLineEndings.Decision.NORMALISE
                || mixed == MixedLineEndings.Decision.NORMALISE_WITH_CONSENT;
    }

    boolean needsConsent() {
        return mixed == MixedLineEndings.Decision.NORMALISE_WITH_CONSENT;
    }

    boolean charsetChanged() {
        return charsetTo != null;
    }

    /** {@code saved} (the plain "Saved …" message) followed by one sentence per thing the save changed. */
    String appendTo(String saved) {
        List<String> notes = new ArrayList<>();
        if (normalisesMixed() && source != null) {
            java.nio.file.Path kept = source.kept().get();
            notes.add(tr("status.save.note.mixedLineEndings", lineEnding, PathDisplay.of(kept)));
        }
        if (charsetChanged()) {
            notes.add(tr(
                    "status.save.note.charset",
                    EditorConfigCharset.displayName(charsetFrom),
                    EditorConfigCharset.displayName(charsetTo),
                    charsetRule));
        }
        if (newlinesDropped > 0) {
            notes.add(tr("status.save.note.finalNewline", newlinesDropped));
        }
        return notes.isEmpty() ? saved : saved + " " + String.join(" ", notes);
    }
}
