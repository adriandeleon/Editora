package com.editora.typst;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * UI-facing façade for the {@code typst} CLI, mirroring {@code DiagramService}/{@code MermaidService}: work
 * runs on a single daemon executor and results are posted back on the JavaFX thread via
 * {@link Platform#runLater}. Owns the configured executable path + a cached availability probe; used by
 * {@code TypstCoordinator} for the export command and the Settings tool-detection status. Preview rendering
 * lives separately in {@code editor/TypstImages} (with its own cache).
 */
public final class TypstService {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "typst-service");
        t.setDaemon(true);
        return t;
    });

    private volatile String path = "";
    private volatile Boolean cached;

    /** Updates the configured executable path/command; clears the cached availability so it re-probes. */
    public void setPath(String newPath) {
        this.path = newPath == null ? "" : newPath;
        this.cached = null;
    }

    /** The command (configured value or {@code "typst"}), tokenized. */
    public List<String> command() {
        return TypstRenderer.command(path);
    }

    /** Probes {@code typst}'s presence off-thread (cached), posting the result on the FX thread. */
    /** The last cached detection result ({@code false} if never probed) — a synchronous read for the
     *  installer's "already installed?" pre-check. */
    public boolean cachedAvailable() {
        Boolean c = cached;
        return c != null && c;
    }

    public void detect(Consumer<Boolean> onResult) {
        Boolean hit = cached;
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        exec.submit(() -> {
            boolean present = TypstRenderer.detect(command());
            cached = present;
            Platform.runLater(() -> onResult.accept(present));
        });
    }

    /** Renders {@code source} to per-page PNG bytes off-thread (empty list on failure); posts on the FX thread.
     *  Used by the print path, which paginates the images. {@code fileDir}/{@code root} as in
     *  {@link TypstRenderer#renderPages}. */
    public void renderPages(String source, Path fileDir, Path root, Consumer<List<byte[]>> onResult) {
        exec.submit(() -> {
            TypstRenderer.Pages r = TypstRenderer.renderPages(command(), source, fileDir, root);
            List<byte[]> pages = r.ok() ? r.pages() : List.of();
            Platform.runLater(() -> onResult.accept(pages));
        });
    }

    /** Exports {@code source} to {@code dest} (format by extension) off-thread; posts the result. */
    public void export(String source, Path dest, Path fileDir, Path root, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = TypstRenderer.exportTo(command(), source, dest, fileDir, root);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Stops the background worker (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
