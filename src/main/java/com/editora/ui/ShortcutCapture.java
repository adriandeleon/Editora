package com.editora.ui;

import java.util.function.Consumer;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.KeyDispatcher;

import static com.editora.i18n.Messages.tr;

/**
 * The chord-recording field of the Settings shortcut editors (Keymaps list, Macros keybinding row).
 *
 * <p>The field records every key press as a chord token, so it cannot be left with Tab — which is why it
 * must be finishable from the keyboard on its own: a plain Enter saves what has been recorded and Escape
 * cancels. Enter is still recordable as the <em>first</em> key (nothing to save yet), and with a modifier
 * at any position.
 */
final class ShortcutCapture {

    /** What a key press means to a recording field. */
    enum Action {
        /** Leave recording without binding anything. */
        CANCEL,
        /** Bind the sequence recorded so far. */
        COMMIT,
        /** Append the key as the next chord of the sequence. */
        RECORD
    }

    private ShortcutCapture() {}

    /** Decides a key press. {@code modified} is any of Ctrl/Alt/Meta/Shift; {@code recorded} is the sequence so far. Pure. */
    static Action decide(KeyCode code, boolean modified, String recorded) {
        if (code == KeyCode.ESCAPE) {
            return Action.CANCEL;
        }
        if (code == KeyCode.ENTER && !modified && recorded != null && !recorded.isBlank()) {
            return Action.COMMIT;
        }
        return Action.RECORD;
    }

    /** Where the field keeps the recorded sequence in keymap tokens ({@code C-S-k f5}); it shows it formatted. */
    private static final Object RECORDED = new Object();

    /**
     * A read-only field that shows the chords pressed into it, in the active keymap's notation through
     * {@code display} ({@code Ctrl+Shift+K F5}, {@code ⇧⌘K}, or {@code C-S-k f5} under Emacs). {@code onCommit}
     * receives the recorded sequence in keymap tokens on Enter; {@code onCancel} runs on Escape. A Save button
     * beside it commits {@link #recorded}.
     */
    static TextField field(
            java.util.function.UnaryOperator<String> display, Consumer<String> onCommit, Runnable onCancel) {
        TextField capture = new TextField();
        capture.setEditable(false);
        capture.setPromptText(tr("settings.shortcuts.recording"));
        capture.setAccessibleText(tr("settings.shortcuts.recording"));
        capture.getStyleClass().add("shortcut-capture");
        capture.setPrefWidth(280);
        capture.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            e.consume();
            boolean modified = e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown();
            String sofar = recorded(capture);
            switch (decide(e.getCode(), modified, sofar)) {
                case CANCEL -> onCancel.run();
                case COMMIT -> onCommit.accept(sofar);
                case RECORD -> {
                    String token = KeyDispatcher.chord(e);
                    if (token != null) { // null: a modifier-only press
                        String seq = sofar.isEmpty() ? token : sofar + ' ' + token;
                        capture.getProperties().put(RECORDED, seq);
                        capture.setText(display.apply(seq));
                    }
                }
            }
        });
        return capture;
    }

    /** The sequence recorded into a {@link #field} so far, in keymap tokens; empty when nothing was. */
    static String recorded(TextField capture) {
        return capture.getProperties().get(RECORDED) instanceof String s ? s : "";
    }
}
