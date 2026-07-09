package com.editora.diagram;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * UI-facing façade for the diagram CLIs, mirroring {@code MermaidService}/{@code GitService}: work runs
 * on a single daemon executor and results are posted back on the JavaFX thread via
 * {@link Platform#runLater}. Owns the configured executable paths per {@link DiagramKind} and a cached
 * availability probe; used by {@code DiagramCoordinator} for the export command and the Settings
 * tool-detection status. Diagram <em>preview</em> rendering lives separately in {@code editor/DiagramImages}
 * (with its own cache).
 */
public final class DiagramService {

    /** Which tools are present, keyed by kind (resolved from the configured paths or PATH). */
    public record Availability(Map<DiagramKind, Boolean> present) {
        public boolean has(DiagramKind kind) {
            return present.getOrDefault(kind, false);
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diagram-service");
        t.setDaemon(true);
        return t;
    });

    private final Map<DiagramKind, String> paths = new EnumMap<>(DiagramKind.class);
    private volatile Availability cached;

    /** Updates the configured executable paths/commands; clears the cached availability so it re-probes. */
    public void setPaths(Map<DiagramKind, String> newPaths) {
        paths.clear();
        if (newPaths != null) {
            newPaths.forEach((k, v) -> paths.put(k, v == null ? "" : v));
        }
        cached = null;
    }

    /** The command for {@code kind} (configured value or its default), tokenized. */
    public List<String> command(DiagramKind kind) {
        return DiagramRenderer.command(paths.get(kind), kind.defaultCommand());
    }

    /** The live per-kind command map, for {@code editor/DiagramImages.configure}. */
    public Map<DiagramKind, List<String>> commands() {
        Map<DiagramKind, List<String>> out = new EnumMap<>(DiagramKind.class);
        for (DiagramKind k : DiagramKind.values()) {
            out.put(k, command(k));
        }
        return out;
    }

    /** Probes each tool's presence off-thread (cached), posting {@link Availability} on the FX thread. */
    public void detect(Consumer<Availability> onResult) {
        Availability hit = cached;
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        exec.submit(() -> {
            Map<DiagramKind, Boolean> present = new EnumMap<>(DiagramKind.class);
            for (DiagramKind k : DiagramKind.values()) {
                present.put(k, DiagramRenderer.detect(command(k)));
            }
            Availability a = new Availability(present);
            cached = a;
            Platform.runLater(() -> onResult.accept(a));
        });
    }

    /** Renders {@code source} to {@code dest} (format by extension) via {@code kind} off-thread; posts the result. */
    public void export(
            DiagramKind kind, String source, Path dest, boolean dark, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r = DiagramRenderer.exportTo(kind, command(kind), source, dest, dark);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Stops the background render/export thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
