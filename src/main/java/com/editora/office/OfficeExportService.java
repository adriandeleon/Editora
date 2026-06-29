package com.editora.office;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Exports the Markdown preview to MS Word ({@code .docx}, via {@link DocxWriter}) and OpenDocument Text
 * ({@code .odt}, via {@link OdtWriter}) off the JavaFX thread — the {@code PdfExportService} idiom: a single
 * daemon executor runs the (blocking) document generation and posts a {@link Result} back via
 * {@link Platform#runLater}.
 */
public final class OfficeExportService {

    /** Outcome of an export: {@code ok} plus an error {@code message} on failure. */
    public record Result(boolean ok, String message) {}

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "office-export");
        t.setDaemon(true);
        return t;
    });

    /**
     * Exports {@code markdown} to a {@code .docx}; {@code baseDir} resolves relative image paths and
     * {@code mmdcCommand} (or null) renders ```mermaid blocks as diagrams.
     */
    public void exportDocx(
            String markdown, Path baseDir, java.util.List<String> mmdcCommand, Path out, Consumer<Result> onResult) {
        run(() -> DocxWriter.write(markdown, baseDir, mmdcCommand, out), onResult);
    }

    /** Exports {@code markdown} to a {@code .odt}; see {@link #exportDocx} for {@code baseDir}/{@code mmdcCommand}. */
    public void exportOdt(
            String markdown, Path baseDir, java.util.List<String> mmdcCommand, Path out, Consumer<Result> onResult) {
        run(() -> OdtWriter.write(markdown, baseDir, mmdcCommand, out), onResult);
    }

    private interface Job {
        void run() throws Exception;
    }

    private void run(Job job, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                job.run();
                result = new Result(true, "");
            } catch (Throwable e) {
                // Throwable, not Exception: a jlink/resource Error would otherwise be swallowed by the
                // Future, hanging the "Exporting…" status forever (see PdfExportService).
                java.util.logging.Logger.getLogger(OfficeExportService.class.getName())
                        .log(java.util.logging.Level.SEVERE, "Office export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Stops the background export thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
