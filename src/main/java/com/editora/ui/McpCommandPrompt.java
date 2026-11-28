package com.editora.ui;

import java.util.concurrent.CompletableFuture;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The question before an MCP agent's command runs ({@code execute_command}): Run, Always Allow (this command from
 * now on) or Don't Run, which is the default, so a reflexive Enter runs nothing. It does not block the FX thread:
 * the agent's call waits for the answer on its own thread ({@link WindowMcpBridge#commandDenial}).
 */
final class McpCommandPrompt {

    private McpCommandPrompt() {}

    /** Asks; {@code answered} hears the answer. Returns what closes the question when it was answered too late. */
    static Runnable ask(
            @Nullable Window owner,
            String id,
            String title,
            String agent,
            CompletableFuture<WindowMcpBridge.CommandAnswer> answered) {
        ButtonType run = Dialogs.action(tr("dialog.mcpCommand.run"));
        ButtonType always = new ButtonType(tr("dialog.mcpCommand.always"), ButtonBar.ButtonData.OTHER);
        ButtonType decline = new ButtonType(tr("dialog.mcpCommand.decline"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.mcpCommand.body", agent, title, id), decline, always, run));
        alert.setTitle(tr("dialog.mcpCommand.title"));
        alert.setHeaderText(tr("dialog.mcpCommand.header", agent, title));
        if (owner != null) {
            alert.initOwner(owner);
        }
        Dialogs.defaultTo(alert, decline);
        Dialogs.show(
                alert,
                result -> answered.complete(
                        result == run
                                ? WindowMcpBridge.CommandAnswer.RUN
                                : result == always
                                        ? WindowMcpBridge.CommandAnswer.ALWAYS
                                        : WindowMcpBridge.CommandAnswer.DECLINE));
        return () -> {
            if (alert.isShowing()) {
                alert.close();
            }
        };
    }
}
