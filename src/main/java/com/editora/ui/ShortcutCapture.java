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

    /**
     * A read-only field that shows the chords pressed into it. {@code onCommit} receives the recorded
     * sequence on Enter; {@code onCancel} runs on Escape. The field's text is the sequence, so a Save
     * button beside it can commit {@code field.getText()} too.
     */
    static TextField field(Consumer<String> onCommit, Runnable onCancel) {
        TextField capture = new TextField();
        capture.setEditable(false);
        capture.setPromptText(tr("settings.shortcuts.recording"));
        capture.setAccessibleText(tr("settings.shortcuts.recording"));
        capture.getStyleClass().add("shortcut-capture");
        capture.setPrefWidth(280);
        capture.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            e.consume();
            boolean modified = e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown();
            switch (decide(e.getCode(), modified, capture.getText())) {
                case CANCEL -> onCancel.run();
                case COMMIT -> onCommit.accept(capture.getText());
                case RECORD -> {
                    String token = KeyDispatcher.chord(e);
                    if (token != null) { // null: a modifier-only press
                        String sofar = capture.getText();
                        capture.setText(sofar == null || sofar.isEmpty() ? token : sofar + ' ' + token);
                    }
                }
            }
        });
        return capture;
    }
}
