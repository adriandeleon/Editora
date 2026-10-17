package com.editora.ui;

import java.util.Optional;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import static com.editora.i18n.Messages.tr;

/**
 * Asked before Export Configuration when the export would otherwise leave something sensitive out: the HTTP
 * Client's saved responses (bodies and full URLs, where a query may carry a key) and the AI provider API keys
 * in {@code settings.json} (billable credentials, written blank unless asked for). An export is the thing
 * people hand to someone else, so both are off by default, every time.
 */
final class ExportConfigDialog {

    private ExportConfigDialog() {}

    /** What to put in the zip besides the configuration. */
    record Choice(boolean includeHttpHistory, boolean includeApiKeys) {}

    /** The user's choice, or empty when cancelled. Blocks (modal). */
    static Optional<Choice> ask(Window owner, boolean askHttpHistory, boolean askApiKeys) {
        return build(owner, askHttpHistory, askApiKeys).showAndWait();
    }

    /** The dialog, not yet shown (tests look inside it). Only the questions that apply are shown. */
    static Dialog<Choice> build(Window owner, boolean askHttpHistory, boolean askApiKeys) {
        Dialog<Choice> dialog = Dialogs.styled(new Dialog<Choice>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr("dialog.exportConfig.title"));
        dialog.setHeaderText(tr("dialog.exportConfig.ask"));
        VBox content = new VBox(8);
        CheckBox http = option(
                content,
                askHttpHistory,
                "include-http-history",
                "dialog.exportConfig.includeHttpHistory",
                "dialog.exportConfig.includeHttpHistory.note");
        CheckBox keys = option(
                content,
                askApiKeys,
                "include-api-keys",
                "dialog.exportConfig.includeApiKeys",
                "dialog.exportConfig.includeApiKeys.note");
        dialog.getDialogPane().setContent(content);
        ButtonType export = new ButtonType(tr("dialog.exportConfig.export"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(export, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == export ? new Choice(http.isSelected(), keys.isSelected()) : null);
        return dialog;
    }

    /** An off-by-default checkbox and its note, added to {@code content} only when {@code shown}. */
    private static CheckBox option(VBox content, boolean shown, String id, String key, String noteKey) {
        CheckBox box = new CheckBox(tr(key));
        box.setId(id);
        box.setSelected(false);
        if (shown) {
            Label note = new Label(tr(noteKey));
            note.setWrapText(true);
            note.setMaxWidth(460);
            content.getChildren().addAll(box, note);
        }
        return box;
    }
}
