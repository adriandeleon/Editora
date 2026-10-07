package com.editora.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

/** Keyboard defaults of the "modified outside Editora" prompt. */
final class ExternalChangePrompt {

    private ExternalChangePrompt() {}

    /**
     * For a buffer with unsaved edits, Reload discards them. The prompt appears on focus gain, often while
     * the user is already typing, so Enter must reach the choice that keeps the edits: {@code keep} becomes
     * the default button and takes the initial focus. Reload stays one deliberate click (or Tab) away.
     */
    static void keepIsTheKeyboardDefault(Alert alert, ButtonType reload, ButtonType keep) {
        Button reloadButton = (Button) alert.getDialogPane().lookupButton(reload);
        Button keepButton = (Button) alert.getDialogPane().lookupButton(keep);
        if (reloadButton == null || keepButton == null) {
            return;
        }
        reloadButton.setDefaultButton(false);
        keepButton.setDefaultButton(true);
        // The dialog gives the initial focus to its first button, and Enter fires whichever button has the
        // focus (on Windows and Linux) before the default button is consulted.
        keepButton.requestFocus();
        alert.setOnShown(shown -> keepButton.requestFocus());
    }
}
