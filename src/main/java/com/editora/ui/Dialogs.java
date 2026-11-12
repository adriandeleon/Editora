package com.editora.ui;

import java.util.Map;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;

import static com.editora.i18n.Messages.tr;

/**
 * Shared set-up for native dialogs ({@code Alert}, {@code TextInputDialog}, {@code ChoiceDialog}, a
 * {@code Dialog} the app fills itself). <b>Every dialog the app builds goes through {@link #styled}</b>
 * ({@code DialogsSourceTest} holds that), because a dialog is a scene of its own and takes nothing from the
 * window:
 *
 * <ul>
 *   <li><b>The app stylesheet.</b> Without {@code app.css} a dialog's buttons fall back to the bare theme
 *       (another corner radius, no keyboard-focus ring, none of the state colours). The {@code -color-*}
 *       tokens resolve either way: those come from the application-wide user-agent stylesheet.
 *   <li><b>One button order on every OS</b> ({@link #BUTTON_ORDER}): Cancel left of every action. JavaFX's
 *       own order changes with the operating system, so the same question read differently on each.
 *   <li><b>Buttons worded from the message catalog.</b> JavaFX labels its stock OK, Cancel, Yes, No, Apply and
 *       Close from its own resources, in the JVM's locale, so under menus in the language chosen in Settings a
 *       dialog could still say "Cancel" in another. Each stock button takes the catalog's word, now and
 *       whenever the dialog's buttons change.
 * </ul>
 *
 * A question before something is removed, deleted, discarded or overwritten is asked through {@link Confirm},
 * or, where it needs a layout of its own, marked with {@link #destructive}.
 */
final class Dialogs {

    private static final String APP_CSS = "/com/editora/styles/app.css";

    /**
     * One {@code ButtonBar} order for every OS: help at the left, then Cancel ({@code C}) before every action,
     * so the unsaved-changes question reads Cancel, Save, Discard. JavaFX's default differs per OS.
     */
    static final String BUTTON_ORDER = "L_HE+CUYNBXIAO_R";

    /** JavaFX's stock buttons and the catalog key that words each one. */
    private static final Map<ButtonType, String> STOCK = Map.of(
            ButtonType.OK, "dialog.ok",
            ButtonType.CANCEL, "dialog.cancel",
            ButtonType.YES, "dialog.yes",
            ButtonType.NO, "dialog.no",
            ButtonType.APPLY, "dialog.apply",
            ButtonType.CLOSE, "dialog.close");

    /** Marks a pane this class has set up, so a second {@link #styled} adds no second listener. */
    private static final String STYLED = "editora.dialogs.styled";

    private Dialogs() {}

    /** Sets {@code dialog} up as the class comment says (once) and returns it. */
    static <D extends Dialog<?>> D styled(D dialog) {
        DialogPane pane = dialog.getDialogPane();
        var css = Dialogs.class.getResource(APP_CSS);
        if (css != null) {
            String url = com.editora.editor.UiZoom.sheet(css.toExternalForm());
            var sheets = pane.getStylesheets();
            if (!sheets.contains(url)) {
                sheets.add(url);
            }
        }
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(BUTTON_ORDER);
        }
        wordStockButtons(pane);
        if (pane.getProperties().putIfAbsent(STYLED, Boolean.TRUE) == null) {
            // A button added after this call is worded too: DialogPane makes a new Button for each change.
            pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> wordStockButtons(pane));
        }
        return dialog;
    }

    /** Sets each stock button's text from the catalog; a button the app named itself is left alone. */
    private static void wordStockButtons(DialogPane pane) {
        for (ButtonType type : pane.getButtonTypes()) {
            String key = STOCK.get(type);
            if (key != null && pane.lookupButton(type) instanceof Button button) {
                button.setText(tr(key));
            }
        }
    }

    /** Cancel in the catalog's word, with the role that Escape and the window's close button answer. */
    static ButtonType cancel() {
        return new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /** A confirming button named {@code label}: the action's own verb, never OK or Yes. */
    static ButtonType action(String label) {
        return new ButtonType(label, ButtonBar.ButtonData.OK_DONE);
    }

    /**
     * Marks {@code dialog} as a question before a destructive action, for a dialog that cannot be a plain
     * {@link Confirm} (it has a checkbox, a third answer, a layout of its own): {@code action}'s button is
     * drawn in red and is no longer the default, and the dialog's Cancel becomes the one default button, so
     * it holds the focus when the question opens and Enter keeps what the user has. The red action then runs
     * only from its own button. Call it once the dialog's buttons are final: {@code DialogPane} makes new
     * buttons whenever they change.
     */
    static <D extends Dialog<?>> D destructive(D dialog, ButtonType action) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.lookupButton(action) instanceof Button act) {
            if (!act.getStyleClass().contains("danger")) {
                act.getStyleClass().add("danger");
            }
            act.setDefaultButton(false);
        }
        defaultTo(dialog, cancelOf(dialog));
        return dialog;
    }

    /**
     * Makes {@code type}'s button the dialog's one default button: it holds the focus when the dialog opens
     * and Enter presses it. Every other button stops being a default, because JavaFX makes an {@code OK_DONE}
     * button one by itself.
     */
    static <D extends Dialog<?>> D defaultTo(D dialog, ButtonType type) {
        DialogPane pane = dialog.getDialogPane();
        for (ButtonType other : pane.getButtonTypes()) {
            if (pane.lookupButton(other) instanceof Button button) {
                button.setDefaultButton(other == type);
            }
        }
        return dialog;
    }

    /** The dialog's Cancel: its button with the {@code CANCEL_CLOSE} role (the stock one when it has none). */
    static ButtonType cancelOf(Dialog<?> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                .findFirst()
                .orElse(ButtonType.CANCEL);
    }
}
