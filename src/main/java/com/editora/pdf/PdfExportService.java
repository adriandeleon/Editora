package com.editora.pdf;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Generates PDFs off the JavaFX thread (the {@code GitService}/{@code MermaidService} idiom): a single
 * daemon executor runs the (blocking) PDFBox work and posts a {@link Result} back via
 * {@link Platform#runLater}. Tokenization for the code PDF also runs here, off the FX thread.
 */
public final class PdfExportService {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(PdfExportService.class.getName());

    /** Outcome of an export: {@code ok} plus an error {@code message} on failure. */
    public record Result(boolean ok, String message) {}

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-export");
        t.setDaemon(true);
        return t;
    });

    /**
     * Exports {@code text} as a code PDF. Highlighting (when {@code highlight}) is computed from the
     * grammar for {@code fileName}; a file with no bundled grammar exports as plain text.
     */
    public void exportCode(
            String text,
            String fileName,
            boolean highlight,
            boolean lineNumbers,
            int tabSize,
            String pageSize,
            Path out,
            Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                StyleSpans<Collection<String>> spans = null;
                if (highlight) {
                    IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
                    if (grammar != null) {
                        spans = TextMateHighlighter.compute(text, grammar);
                    }
                }
                CodePdfWriter.write(text, spans, lineNumbers, tabSize, pageSize, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                // Throwable, not Exception: an Error (e.g. a jlink/resource NoClassDefFoundError) on this
                // submit()'d task would otherwise be swallowed by the Future, hanging the "Exporting…" status.
                LOG.log(java.util.logging.Level.SEVERE, "Code PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports {@code markdown} as a native-vector PDF. {@code mmdcCommand} (or null) renders embedded
     * ```mermaid blocks as diagrams. Runs off the FX thread.
     */
    public void exportMarkdown(
            String markdown,
            Path baseDir,
            String pageSize,
            java.util.List<String> mmdcCommand,
            Path out,
            Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                MarkdownPdfWriter.write(markdown, baseDir, pageSize, mmdcCommand, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Markdown PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports one or more PNG images (produced by snapshotting an image/tree preview on the FX thread) into a
     * single PDF — each image scaled to the page width and sliced across pages when tall (see
     * {@link ImagePdfWriter}). Used by the SVG / Markwhen / JSON-YAML-TOML / XML preview PDF export. Runs off
     * the FX thread.
     */
    public void exportImages(java.util.List<byte[]> pngImages, String pageSize, Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                java.util.List<java.awt.image.BufferedImage> imgs = new java.util.ArrayList<>();
                for (byte[] png : pngImages) {
                    if (png == null) {
                        continue;
                    }
                    java.awt.image.BufferedImage img =
                            javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                    if (img != null) {
                        imgs.add(img);
                    }
                }
                if (imgs.isEmpty()) {
                    throw new IllegalStateException("nothing to export (the preview produced no image)");
                }
                ImagePdfWriter.write(imgs, pageSize, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Image PDF export failed", e);
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
