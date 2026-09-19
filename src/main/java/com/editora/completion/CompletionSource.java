package com.editora.completion;

import java.util.function.Consumer;

/** Async completion boundary; callbacks run on FX and cancellation reaches the server request. */
@FunctionalInterface
public interface CompletionSource {
    Runnable request(
            int line, int character, int triggerKind, String triggerCharacter, Consumer<CompletionResult> result);
}
