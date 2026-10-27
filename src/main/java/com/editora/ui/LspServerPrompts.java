package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Asks the user what a language server asks ({@code window/showMessageRequest}, Metals' input box and quick
 * pick) and carries out the editor actions it requests ({@link LspCoordinator}'s, apart for its size). Every method runs on the FX thread and returns at
 * once; the dialog completes the answer when the user closes it.
 */
final class LspServerPrompts implements com.editora.lsp.LspClientUi {

    /** Opens {@code file} at a 0-based line and column. */
    @FunctionalInterface
    interface Opener {
        void open(@Nullable Path file, int line, int column);
    }

    private final CoordinatorHost host;
    private final java.util.function.Function<String, String> serverLabel;
    private final Opener openAndGoto;

    LspServerPrompts(
            CoordinatorHost host, java.util.function.Function<String, String> serverLabel, Opener openAndGoto) {
        this.host = host;
        this.serverLabel = serverLabel;
        this.openAndGoto = openAndGoto;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> chooseAction(
            String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
        var answer = new java.util.concurrent.CompletableFuture<String>();
        List<javafx.scene.control.ButtonType> buttons = new java.util.ArrayList<>();
        for (String action : actions) {
            buttons.add(new javafx.scene.control.ButtonType(action, javafx.scene.control.ButtonBar.ButtonData.OTHER));
        }
        buttons.add(javafx.scene.control.ButtonType.CLOSE);
        javafx.scene.control.Alert alert = Dialogs.styled(new javafx.scene.control.Alert(
                alertType(type), message, buttons.toArray(javafx.scene.control.ButtonType[]::new)));
        alert.initOwner(host.window());
        alert.initModality(javafx.stage.Modality.NONE); // the editor stays usable while it is asked
        alert.setTitle(serverLabel.apply(serverId));
        alert.setHeaderText(null);
        alert.getDialogPane().setMinWidth(480);
        alert.setOnHidden(e -> {
            javafx.scene.control.ButtonType chosen = alert.getResult();
            answer.complete(
                    chosen == null || chosen == javafx.scene.control.ButtonType.CLOSE ? null : chosen.getText());
        });
        alert.show();
        return answer;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> inputBox(
            String serverId, @Nullable String prompt, @Nullable String value, @Nullable String placeHolder) {
        var answer = new java.util.concurrent.CompletableFuture<String>();
        javafx.scene.control.TextInputDialog dialog =
                Dialogs.styled(new javafx.scene.control.TextInputDialog(value == null ? "" : value));
        dialog.initOwner(host.window());
        dialog.setTitle(serverLabel.apply(serverId));
        dialog.setHeaderText(prompt == null || prompt.isBlank() ? null : prompt);
        if (placeHolder != null) {
            dialog.getEditor().setPromptText(placeHolder);
        }
        dialog.setOnHidden(e -> answer.complete(dialog.getResult()));
        dialog.show();
        return answer;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> quickPick(
            String serverId, @Nullable String placeHolder, List<PickItem> items) {
        var answer = new java.util.concurrent.CompletableFuture<String>();
        // The question goes in the title: as the field's placeholder it hid while the field had focus, so a
        // run of import choices asked nothing anyone could see (third review AT4).
        String question = placeHolder == null || placeHolder.isBlank() ? null : placeHolder;
        QuickOpen<PickItem> picker = new QuickOpen<>(
                question == null ? serverLabel.apply(serverId) : question + "  ·  " + serverLabel.apply(serverId),
                question == null ? tr("palette.setting.pick") : question,
                () -> items,
                PickItem::label,
                item -> item.description() == null ? "" : item.description(),
                chosen -> answer.complete(chosen == null ? null : chosen.id()));
        // No preview; the cancel hook is what tells the server the user chose nothing.
        picker.setPreview(item -> {}, () -> answer.complete(null));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
        // Cancelled: the request that asked has ended, so the question goes too (fifth review DV1).
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) {
                Platform.runLater(picker::hide);
            }
        });
        return answer;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> chooseImport(
            String serverId, String simpleName, int line, List<PickItem> items) {
        return quickPick(serverId, tr("picker.lsp.chooseImport", simpleName, line + 1), items);
    }

    @Override
    public void openLocation(String serverId, Path file, int line, int column) {
        openAndGoto.open(file, line, column);
    }

    private javafx.scene.control.Alert.AlertType alertType(org.eclipse.lsp4j.MessageType type) {
        if (type == null) {
            return javafx.scene.control.Alert.AlertType.INFORMATION;
        }
        return switch (type) {
            case Error -> javafx.scene.control.Alert.AlertType.ERROR;
            case Warning -> javafx.scene.control.Alert.AlertType.WARNING;
            default -> javafx.scene.control.Alert.AlertType.INFORMATION;
        };
    }
}
