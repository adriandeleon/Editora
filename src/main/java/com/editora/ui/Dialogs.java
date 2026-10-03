package com.editora.ui;

import javafx.scene.control.Dialog;

/**
 * Shared set-up for native dialogs ({@code Alert}, {@code TextInputDialog}, …).
 *
 * <p>A dialog lives in its own scene, so it does <em>not</em> inherit the main window's {@code app.css}:
 * its buttons fall back to the bare theme (different corner radius, no keyboard-focus ring, none of the
 * state colours). Pass every dialog through {@link #styled} so it looks and behaves like the rest of the
 * app. (The {@code -color-*} tokens resolve either way — those come from the application-wide user-agent
 * stylesheet.)
 */
final class Dialogs {

    private static final String APP_CSS = "/com/editora/styles/app.css";

    private Dialogs() {}

    /** Attaches the app stylesheet to {@code dialog}'s pane (once) and returns the dialog. */
    static <D extends Dialog<?>> D styled(D dialog) {
        var css = Dialogs.class.getResource(APP_CSS);
        if (css != null) {
            String url = css.toExternalForm();
            var sheets = dialog.getDialogPane().getStylesheets();
            if (!sheets.contains(url)) {
                sheets.add(url);
            }
        }
        return dialog;
    }
}
