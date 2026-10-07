package com.editora.ui;

import com.editora.editor.EditorBuffer;

/**
 * The state of a buffer at the moment something decided to replace its text with the copy on disk. The read
 * happens off the FX thread, so the replacement is applied on a later turn; it may only go ahead if the
 * document is still the one the decision was made about. An edit made in between — or a buffer that has
 * become unsaved since — means the user now holds text the decision never covered.
 */
record ReloadGuard(long docVersion, boolean dirty) {

    static ReloadGuard capture(EditorBuffer buffer) {
        return new ReloadGuard(buffer.docVersion(), buffer.isDirty());
    }

    boolean stillHolds(EditorBuffer buffer) {
        return stillHolds(buffer.docVersion(), buffer.isDirty());
    }

    /** Pure decision: same document version, and not newly unsaved. */
    boolean stillHolds(long currentDocVersion, boolean currentlyDirty) {
        return currentDocVersion == docVersion && (dirty || !currentlyDirty);
    }
}
