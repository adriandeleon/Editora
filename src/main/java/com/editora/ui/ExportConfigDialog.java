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
 * Asked before Export Configuration when the HTTP Client has saved responses: they hold response bodies and
 * full URLs (a query may carry a key), and an export is the thing people hand to someone else, so they are
 * left out unless the user ticks "Include HTTP response history" — off by default, every time.
 */
final class ExportConfigDialog {

    private ExportConfigDialog() {}

    /** True / false: export, with / without the HTTP response history; empty: cancelled. Blocks (modal). */
    static Optional<Boolean> ask(Window owner) {
        return build(owner).showAndWait();
    }

    /** The dialog, not yet shown (tests look inside it). */
    static Dialog<Boolean> build(Window owner) {
        Dialog<Boolean> dialog = Dialogs.styled(new Dialog<Boolean>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr("dialog.exportConfig.title"));
        dialog.setHeaderText(tr("dialog.exportConfig.ask"));
        CheckBox http = new CheckBox(tr("dialog.exportConfig.includeHttpHistory"));
        http.setId("include-http-history");
        http.setSelected(false);
        Label note = new Label(tr("dialog.exportConfig.includeHttpHistory.note"));
        note.setWrapText(true);
        note.setMaxWidth(460);
        dialog.getDialogPane().setContent(new VBox(8, http, note));
        ButtonType export = new ButtonType(tr("dialog.exportConfig.export"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(export, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == export ? http.isSelected() : null);
        return dialog;
    }
}
