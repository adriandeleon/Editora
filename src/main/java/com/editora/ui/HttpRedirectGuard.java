package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.http.ResponseRedirects;

/**
 * What happens before a {@code >>!} redirect replaces an existing file: a file open with unsaved changes is left
 * alone, else its content is recorded in Local History first (when the window has one), durably, before the run goes
 * on. Asked on the run's worker; decided on the FX thread, where the buffers and the history live.
 */
final class HttpRedirectGuard implements ResponseRedirects.Guard {

    private final CoordinatorHost host;
    private final Supplier<HistoryCoordinator> history;

    /** @param history the window's Local History, or null when it has none */
    HttpRedirectGuard(CoordinatorHost host, Supplier<HistoryCoordinator> history) {
        this.host = host;
        this.history = history;
    }

    /** How long a run waits for the previous content of a redirect target to become a durable revision. */
    private static final long CAPTURE_TIMEOUT_SECONDS = 30;

    /**
     * Called on the request's worker thread before {@code >>!} replaces an
     * existing file. The decision is made on the FX thread (buffers and history live there) and awaited here;
     * if it cannot be had — the window is closing, the history store does not answer — the file is left alone.
     */
    @Override
    public ResponseRedirects.Decision beforeOverwrite(Path target) {
        CompletableFuture<ResponseRedirects.Decision> decision = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                decideRedirectOverwrite(target, decision::complete);
            } catch (RuntimeException failure) {
                decision.complete(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED));
            }
        });
        try {
            return decision.get(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException unanswered) {
            // fall through: not preserved
        }
        return ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED);
    }

    /** FX thread: refuses a target that is open with unsaved changes, else records it in Local History. */
    private void decideRedirectOverwrite(Path target, Consumer<ResponseRedirects.Decision> completion) {
        Path wanted = target.toAbsolutePath().normalize();
        boolean[] unsaved = new boolean[1];
        host.forEachBuffer(b -> {
            if (b.getPath() != null
                    && b.isDirty()
                    && b.getPath().toAbsolutePath().normalize().equals(wanted)) {
                unsaved[0] = true;
            }
        });
        if (unsaved[0]) {
            completion.accept(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.REFUSED_UNSAVED_CHANGES));
            return;
        }
        HistoryCoordinator history = this.history.get();
        if (history == null) {
            completion.accept(ResponseRedirects.Decision.of(ResponseRedirects.Verdict.NOT_KEPT));
            return;
        }
        history.captureBeforeOverwriteDurably(target, capture -> {
            ResponseRedirects.Verdict verdict = !capture.safe()
                    ? ResponseRedirects.Verdict.REFUSED_NOT_PRESERVED
                    : capture.recorded()
                            ? ResponseRedirects.Verdict.KEPT_IN_HISTORY
                            : ResponseRedirects.Verdict.NOT_KEPT;
            completion.accept(new ResponseRedirects.Decision(verdict, capture.expectedBytes()));
        });
    }
}
