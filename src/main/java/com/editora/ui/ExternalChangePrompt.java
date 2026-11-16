package com.editora.ui;

import java.util.List;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

import static com.editora.i18n.Messages.tr;

/** The words and the keyboard defaults of the "modified outside Editora" prompt. */
final class ExternalChangePrompt {

    private ExternalChangePrompt() {}

    /**
     * The words of the question asked when {@code fileName} changed on disk under an open tab. Its answer: Reload
     * (0), which discards the tab's unsaved edits when it is {@code dirty}. Its Cancel is Keep (Keep Mine when
     * dirty).
     */
    static Confirm.Choice question(String fileName, boolean dirty) {
        return new Confirm.Choice(
                tr("dialog.externalChange.title"),
                tr("dialog.externalChange.header", fileName),
                tr(dirty ? "dialog.externalChange.dirty" : "dialog.externalChange.clean"),
                List.of(tr("dialog.externalChange.reload")),
                tr(dirty ? "dialog.externalChange.keepMine" : "dialog.externalChange.keep"));
    }

    /**
     * The words of the question asked when a save finds that {@code fileName} changed on its host since it was
     * loaded. Its answers: Reload (0), which discards the unsaved edits, and Keep Mine (1), which overwrites the
     * file.
     */
    static Confirm.Choice saveConflictQuestion(String fileName) {
        return Confirm.choice(
                tr("dialog.externalChange.title"),
                tr("dialog.externalChange.header", fileName),
                tr("dialog.externalChange.dirty"),
                tr("dialog.externalChange.reload"),
                tr("dialog.externalChange.keepMine"));
    }

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

    /**
     * For a buffer without edits nothing is lost by reloading, so Enter reloads. Where Enter fires the button
     * that has the focus (Windows, Linux) the first button already was that; on macOS Enter only ever reaches
     * the default button — a focused one answers to Space — and with none the prompt ignored the key.
     */
    static void reloadIsTheKeyboardDefault(Alert alert, ButtonType reload) {
        if (alert.getDialogPane().lookupButton(reload) instanceof Button reloadButton) {
            reloadButton.setDefaultButton(true);
        }
    }
}
