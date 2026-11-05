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

    /** The name the user knows the answering server by ("Pyright"), for "Waiting for Pyright…"; null: unnamed. */
    default @Nullable String serverName() {
        return null;
    }

    /** {@code source}, naming its server through {@code name} (asked each time: a file's server can change). */
    static CompletionSource named(java.util.function.Supplier<@Nullable String> name, CompletionSource source) {
        return new CompletionSource() {
            @Override
            public Runnable request(
                    int line,
                    int character,
                    int triggerKind,
                    @Nullable String triggerCharacter,
                    Consumer<CompletionResult> result) {
                return source.request(line, character, triggerKind, triggerCharacter, result);
            }

            @Override
            public @Nullable String serverName() {
                return name.get();
            }
        };
    }
}
