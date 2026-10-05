package com.editora.ui;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

/**
 * Which tabs keep the Git Log and Commit tool windows usable.
 *
 * <p>They are hidden on a tab with no Git context (Welcome, Settings). That used to mean "any tab that is
 * not an editor buffer" — which included the diff and review tabs these two windows open themselves, so
 * double-clicking a commit's file closed the log it was picked from, and Show Diff closed the Commit window.
 */
final class GitWindowGate {

    private GitWindowGate() {}

    /** Whether {@code selected} is an editor buffer, or a diff/review tab opened from the Git windows. */
    static boolean allows(Tab selected) {
        Object content = selected == null ? null : selected.getUserData();
        return content instanceof EditorBuffer
                || content instanceof DiffViewerPane
                || content instanceof PatchReviewPane
                || content instanceof DirectoryReviewPane
                || content instanceof PrReviewPane;
    }
}
