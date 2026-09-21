package com.editora.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.agent.runtime.AgentCancellation;

/** Cancellable FX handoff: a timed-out/cancelled caller cannot leave a queued mutation behind. */
final class AgentFx {
    private AgentFx() {}

    static <T> T call(AgentCancellation cancellation, Supplier<T> action) throws Exception {
        cancellation.check();
        if (Platform.isFxApplicationThread()) {
            return action.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (result.isDone() || cancellation.isCancelled()) {
                return;
            }
            try {
                cancellation.check();
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try (var hook = cancellation.onCancel(() -> result.cancel(false))) {
            try {
                return result.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw failure;
            }
        } finally {
            result.cancel(false);
        }
    }
}
