package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * The {@link LspClientUi} every session of an {@link LspManager} is given: the coordinator's UI (set later with
 * {@link LspManager#setClientUi}) reached from a session's reader thread, every call posted to the FX thread. A
 * question asked with no UI set, or after the manager closed, is answered with null.
 */
final class FxClientUi implements LspClientUi {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LspManager.class.getName());

    private final LspManager m;

    FxClientUi(LspManager m) {
        this.m = m;
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> chooseAction(
            String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
        return onFx(ui -> ui.chooseAction(serverId, type, message, actions));
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> inputBox(
            String serverId, @Nullable String prompt, @Nullable String value, @Nullable String placeHolder) {
        return onFx(ui -> ui.inputBox(serverId, prompt, value, placeHolder));
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> quickPick(
            String serverId, @Nullable String placeHolder, List<PickItem> items) {
        return onFx(ui -> ui.quickPick(serverId, placeHolder, items));
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> chooseImport(
            String serverId, String simpleName, int line, List<PickItem> items) {
        return onFx(ui -> ui.chooseImport(serverId, simpleName, line, items));
    }

    @Override
    public void openLocation(String serverId, Path file, int line, int column) {
        Platform.runLater(() -> {
            LspClientUi ui = m.clientUi;
            if (ui != null && !m.isClosed()) {
                ui.openLocation(serverId, file, line, column);
            }
        });
    }

    @Override
    public java.util.concurrent.CompletableFuture<Object> clientCommand(
            String serverId, String command, List<Object> arguments) {
        var answer = new java.util.concurrent.CompletableFuture<Object>();
        Platform.runLater(() -> {
            LspClientUi ui = m.clientUi;
            if (ui == null || m.isClosed()) {
                answer.complete(null);
                return;
            }
            try {
                ui.clientCommand(serverId, command, arguments)
                        .whenComplete((v, e) -> answer.complete(e == null ? v : null));
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.FINE, "client command not run: " + command, e);
                answer.complete(null);
            }
        });
        return answer;
    }

    private java.util.concurrent.CompletableFuture<String> onFx(
            java.util.function.Function<LspClientUi, java.util.concurrent.CompletableFuture<String>> ask) {
        var answer = new java.util.concurrent.CompletableFuture<String>();
        Platform.runLater(() -> {
            LspClientUi ui = m.clientUi;
            if (ui == null || m.isClosed() || answer.isDone()) {
                answer.complete(null);
                return;
            }
            try {
                var shown = ask.apply(ui);
                shown.whenComplete((v, e) -> answer.complete(e == null ? v : null));
                // The session cancels the question when the request that asked it ends: the picker goes too.
                // Cancelling only this wrapper left the picker on screen, answering nothing (sixth review DV1).
                answer.whenComplete((v, e) -> {
                    if (answer.isCancelled()) {
                        shown.cancel(false);
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.FINE, "server question not shown", e);
                answer.complete(null);
            }
        });
        return answer;
    }
}
