package com.editora.externaltool;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * UI-facing façade that runs a resolved {@link ToolInvocation} off the JavaFX thread (single daemon
 * executor, mirroring {@code MermaidService}/{@code GitService}) and posts the {@link ProcessRunner.Result}
 * back via {@link Platform#runLater}. One-shot capture (stdin fed at launch, stdout/stderr captured on
 * completion); the augmented PATH comes from {@link ProcessRunner}, so a Finder-launched app still finds
 * Homebrew/Node/etc. tools.
 */
public final class ExternalToolService {

    /** Hard cap so a hung CLI can't leak a process/thread; the buffer is fed and drained off-thread. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "external-tool-service");
        t.setDaemon(true);
        return t;
    });

    /** Runs {@code inv} off-thread and delivers its result on the FX thread. */
    public void run(ToolInvocation inv, Duration timeout, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = ProcessRunner.run(inv.workingDir(), timeout, inv.argv(), Map.of(), inv.stdin());
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
