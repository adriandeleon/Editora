package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

/**
 * What a language server may ask of the user, answered by the window: a choice between a message's actions
 * ({@code window/showMessageRequest}, for every server) and Metals' own input box, quick pick and client
 * commands. {@link LspManager} calls every method on the FX thread; each request returns at once and completes
 * when the user answers, so the server's reader thread is never held by a dialog.
 *
 * <p>A dismissed prompt completes with {@code null}.
 */
public interface LspClientUi {

    /** One row of a {@link #quickPick}: {@code id} is what the server is told was chosen. */
    record PickItem(
            String id,
            String label,
            @Nullable String description,
            @Nullable String detail) {}

    /**
     * Asks {@code serverId}'s question {@code message} with {@code actions} as its buttons; completes with the
     * chosen action's title, or null when the user closed it.
     */
    CompletableFuture<String> chooseAction(
            String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions);

    /** Asks for a line of text ({@code metals/inputBox}); completes with it, or null when cancelled. */
    CompletableFuture<String> inputBox(
            String serverId, @Nullable String prompt, @Nullable String value, @Nullable String placeHolder);

    /** Asks for one of {@code items} ({@code metals/quickPick}); completes with its id, or null when cancelled. */
    CompletableFuture<String> quickPick(String serverId, @Nullable String placeHolder, List<PickItem> items);

    /** Opens {@code file} at the 0-based {@code line} and {@code column} ({@code metals-goto-location}). */
    void openLocation(String serverId, Path file, int line, int column);
}
