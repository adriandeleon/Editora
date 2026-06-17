package com.editora.editor;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Runs the pure {@link MarkdownLint} off the FX thread and posts results back via
 * {@link Platform#runLater}, mirroring {@code MermaidService.validate}. The scan is cheap, but a
 * single daemon executor + a generation guard keep the FX thread clean and drop stale results while
 * the user is typing in a large document.
 */
public final class MarkdownLintService {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-lint");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong gen = new AtomicLong();

    /** Lints {@code source} off-thread; delivers diagnostics on the FX thread (latest request wins). */
    public void validate(String source, Consumer<List<MarkdownLint.Diagnostic>> onResult) {
        long mine = gen.incrementAndGet();
        exec.submit(() -> {
            List<MarkdownLint.Diagnostic> diags = MarkdownLint.lint(source);
            if (mine == gen.get()) {
                Platform.runLater(() -> {
                    if (mine == gen.get()) {
                        onResult.accept(diags);
                    }
                });
            }
        });
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
