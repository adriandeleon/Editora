package com.editora.completion;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/** Async completion boundary; callbacks run on FX and cancellation reaches the server request. */
@FunctionalInterface
public interface CompletionSource {
    Runnable request(
            int line,
            int character,
            int triggerKind,
            @Nullable String triggerCharacter,
            Consumer<CompletionResult> result);
}
