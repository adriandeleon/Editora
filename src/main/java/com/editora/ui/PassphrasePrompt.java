package com.editora.ui;

import java.util.Arrays;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.editora.i18n.Messages.tr;

/**
 * Editora's own passphrase prompt — for a passphrase-only file, and for a secret key when gpg-agent cannot show
 * its own dialog (or Settings say Editora asks). An in-scene card that stays open while gpg runs, so a wrong
 * passphrase is said right there and the user types again. The passphrase is handed over as a {@code char[]},
 * never a String kept anywhere, and the field is cleared as soon as it is read. FX thread.
 */
final class PassphrasePrompt {

    private PassphrasePrompt() {}

    /** How a submitted passphrase went: call exactly one, on the FX thread. */
    interface Result {
        /** It worked: the card closes. */
        void done();

        /** It did not: the card says {@code message} and asks again. */
        void failed(String message);
    }

    /**
     * Starts the operation with {@code passphrase} and reports through {@code result}. The array is wiped when
     * {@code submit} returns: work that continues on another thread takes its own copy first (and wipes that).
     */
    interface Attempt {
        void submit(char[] passphrase, Result result);
    }

    /**
     * @param message  what the passphrase is for ("Passphrase for notes.md.gpg")
     * @param note     a line under it (why Editora asks, not GnuPG), or null
     * @param okLabel  the primary button ("Decrypt")
     * @param onCancel run when the card closes without success (Esc, Cancel, a click outside)
     */
    static void show(
            OverlayHost host,
            String title,
            String message,
            String note,
            String okLabel,
            Attempt attempt,
            Runnable onCancel) {
        if (host == null) {
            return;
        }
        boolean[] succeeded = {false};
        boolean[] busy = {false};
        Label heading = new Label(title);
        heading.getStyleClass().add("palette-title");
        Label text = new Label(message);
        text.setWrapText(true);
        PasswordField field = new PasswordField();
        field.setPromptText(tr("encryption.passphrase.prompt"));
        Label error = new Label();
        error.getStyleClass().add("overlay-form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        Button cancel = new Button(tr("dialog.cancel"));
        cancel.setOnAction(e -> host.hide());
        Button ok = new Button(okLabel);
        ok.getStyleClass().add("accent");
        ok.disableProperty().bind(field.textProperty().isEmpty());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, spacer, progress, cancel, ok);
        footer.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(10, heading, text);
        if (note != null && !note.isBlank()) {
            Label noteLabel = new Label(note);
            noteLabel.getStyleClass().add("overlay-note");
            noteLabel.setWrapText(true);
            card.getChildren().add(noteLabel);
        }
        card.getChildren().addAll(field, error, footer);
        card.getStyleClass().addAll("command-palette", "overlay-form", "encryption-passphrase");
        card.setPadding(new Insets(14));
        card.setPrefWidth(420);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);

        Runnable submit = () -> {
            if (busy[0] || field.getText().isEmpty()) {
                return;
            }
            char[] passphrase = field.getText().toCharArray();
            field.clear();
            busy[0] = true;
            field.setDisable(true);
            error.setVisible(false);
            error.setManaged(false);
            progress.setVisible(true);
            progress.setManaged(true);
            boolean[] reported = {false};
            attempt.submit(passphrase, new Result() {
                @Override
                public void done() {
                    if (reported[0]) {
                        return;
                    }
                    reported[0] = true;
                    succeeded[0] = true;
                    busy[0] = false;
                    host.hide();
                }

                @Override
                public void failed(String why) {
                    if (reported[0]) {
                        return;
                    }
                    reported[0] = true;
                    busy[0] = false;
                    field.setDisable(false);
                    progress.setVisible(false);
                    progress.setManaged(false);
                    error.setText(why == null ? "" : why);
                    error.setVisible(true);
                    error.setManaged(true);
                    field.requestFocus();
                }
            });
            Arrays.fill(passphrase, '\0'); // the attempt took its own copy, if it needed one
        };
        ok.setOnAction(e -> submit.run());
        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.getTarget() == cancel) {
                    cancel.fire();
                } else {
                    submit.run();
                }
                e.consume();
            }
        });
        host.show(card, true, field::requestFocus, () -> {
            field.clear();
            if (!succeeded[0] && onCancel != null) {
                onCancel.run();
            }
        });
    }
}
