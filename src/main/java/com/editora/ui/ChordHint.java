package com.editora.ui;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.i18n.Messages;

/**
 * A status message that mentions how to act on it — "Buffer is read-only — C-x C-q to allow edits" — with the
 * chord taken from the active keymap instead of being baked into the catalog text. A baked chord is only true
 * of one keymap (and of one platform's notation): the read-only hint named an Emacs chord to VS Code-keymap
 * users, and the AI rewrite hint named {@code C-z}, which the Emacs keymap does not bind at all.
 *
 * <p>Each such message has two catalog entries: {@code <key>} (no chord — used when the command is unbound)
 * and {@code <key>.chord} (with {@code {0}} for the chord).
 */
final class ChordHint {

    private ChordHint() {}

    /** The message for {@code key}, naming the app-wide keymap's chord for {@code commandId} when it has one. */
    static String tr(String key, String commandId) {
        return tr(TextInputKeymap.sharedKeymap(), key, commandId);
    }

    static String tr(KeymapManager keymap, String key, String commandId) {
        String chord = keymap == null ? null : keymap.displayChord(commandId);
        return chord == null || chord.isEmpty() ? Messages.tr(key) : Messages.tr(key + ".chord", chord);
    }
}
