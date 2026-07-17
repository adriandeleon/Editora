package com.editora.mermaid;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * UI-facing façade for the Mermaid CLIs, mirroring {@code GitService}: work runs on a single daemon
 * executor and results are posted back on the JavaFX thread via {@link Platform#runLater}. Owns the
 * configured executable paths (mmdc/maid) and a cached availability probe; used by {@code MainController}
 * for the export command and the Settings tool-detection status. Diagram <em>preview</em> rendering
 * lives separately in {@code editor/MermaidImages} (with its own cache).
 */
public final class MermaidService {

    /** Which CLIs are present (resolved from the configured paths or PATH). */
    public record Availability(boolean mmdc, boolean maid) {}

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mermaid-service");
        t.setDaemon(true);
        return t;
    });

    /** Default commands when the user leaves the path blank: mmdc as a bare binary, maid via npx. */
    public static final String DEFAULT_MMDC = "mmdc";

    public static final String DEFAULT_MAID = "npx -y @probelabs/maid";

    private final java.util.concurrent.atomic.AtomicLong validateGen = new java.util.concurrent.atomic.AtomicLong();
    private volatile String mmdcPath = "";
    private volatile String maidPath = "";
    private volatile Availability cached;

    /**
     * Updates the configured executable paths/commands. Clears the cached availability <b>only when they
     * actually change</b>, so the next detect re-probes.
     *
     * <p>Clearing unconditionally meant every settings apply re-probed — and Settings is live-apply, so that
     * is once per control you touch, plus every theme apply. The default maid command is
     * {@code npx -y @probelabs/maid}, whose probe costs ~6.5 s, on the same single thread that serves live
     * {@code .mmd} linting and exports: toggling a checkbox parked a multi-second job in front of them.
     */
    public void setPaths(String mmdcPath, String maidPath) {
        String mmdc = mmdcPath == null ? "" : mmdcPath;
        String maid = maidPath == null ? "" : maidPath;
        if (!mmdc.equals(this.mmdcPath) || !maid.equals(this.maidPath)) {
            this.cached = null;
        }
        this.mmdcPath = mmdc;
        this.maidPath = maid;
    }

    /** The mmdc command (configured value or {@link #DEFAULT_MMDC}), tokenized. */
    public List<String> mmdcCommand() {
        return Mermaid.command(mmdcPath, DEFAULT_MMDC);
    }

    /** The maid command (configured value or {@link #DEFAULT_MAID}), tokenized. */
    public List<String> maidCommand() {
        return Mermaid.command(maidPath, DEFAULT_MAID);
    }

    /** Probes mmdc + maid presence off-thread (cached), posting {@link Availability} on the FX thread. */
    public void detect(Consumer<Availability> onResult) {
        Availability hit = cached;
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        exec.submit(() -> {
            Availability a = new Availability(Mermaid.detect(mmdcCommand()), Mermaid.detect(maidCommand()));
            cached = a;
            Platform.runLater(() -> onResult.accept(a));
        });
    }

    /** Renders {@code source} to {@code dest} (format by extension) via mmdc off-thread; posts the result. */
    public void export(String source, Path dest, boolean dark, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = Mermaid.exportTo(mmdcCommand(), source, dest, dark);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Lints {@code source} via maid off-thread, posting diagnostics on the FX thread. A generation guard
     * drops stale results so only the latest in-flight validation wins (live linting while typing).
     */
    public void validate(String source, Consumer<List<MaidOutput.Diagnostic>> onResult) {
        long gen = validateGen.incrementAndGet();
        exec.submit(() -> {
            List<MaidOutput.Diagnostic> diagnostics = Mermaid.validate(maidCommand(), source);
            if (gen == validateGen.get()) {
                Platform.runLater(() -> onResult.accept(diagnostics));
            }
        });
    }

    /** Stops the background render/validate thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
