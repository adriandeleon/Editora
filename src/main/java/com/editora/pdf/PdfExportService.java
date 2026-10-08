package com.editora.pdf;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;

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

    /**
     * Outcome of an export: {@code ok} plus an error {@code message} on failure. {@code unrendered} is the
     * number of characters no available font could draw (written as {@code ?}) — non-zero means the PDF was
     * produced but is not a faithful copy, and the caller says so instead of a plain "Exported".
     */
    public record Result(boolean ok, String message, int unrendered) {
        public Result(boolean ok, String message) {
            this(ok, message, 0);
        }
    }

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
                int unrendered = CodePdfWriter.write(text, spans, lineNumbers, tabSize, pageSize, out);
                result = new Result(true, "", unrendered);
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
                int unrendered = MarkdownPdfWriter.write(markdown, baseDir, pageSize, mmdcCommand, out);
                result = new Result(true, "", unrendered);
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Markdown PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports an already-built CommonMark {@code document} through {@link MarkdownPdfWriter} — the CSV
     * export's table, whose cells are data and must not be parsed as Markdown. Runs off the FX thread.
     */
    public void exportDocument(
            org.commonmark.node.Node document, String pageSize, Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                result = new Result(true, "", MarkdownPdfWriter.write(document, null, pageSize, null, out));
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Table PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports PNG images (produced by snapshotting an image/tree preview on the FX thread) into a single PDF
     * — each at its logical size, fitted to the page width and continued over pages when tall, cut between
     * rows (see {@link ImagePdfWriter}). Used by the Markwhen / JSON-YAML-TOML / XML / summary preview PDF
     * export. Runs off the FX thread and decodes one image at a time.
     */
    public void exportPageImages(
            java.util.List<PageImage> images, String pageSize, Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                writePageImages(images, pageSize, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Image PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    private static void writePageImages(java.util.List<PageImage> images, String pageSize, Path out)
            throws java.io.IOException {
        java.util.List<PageImage> usable = images == null
                ? java.util.List.of()
                : images.stream()
                        .filter(i -> i != null
                                && i.source().pixelWidth() > 0
                                && i.source().pixelHeight() > 0)
                        .toList();
        if (usable.isEmpty()) {
            throw new IllegalStateException("nothing to export (the preview produced no image)");
        }
        ImagePdfWriter.writePng(usable, pageSize, out);
    }

    /**
     * Exports an SVG document as a PDF page: rasterized <b>here</b>, off the FX thread (a large SVG takes
     * hundreds of milliseconds), at {@link com.editora.editor.PreviewImageLoader#PRINT_RASTER_SCALE}× and
     * drawn at the SVG's own size. A failed rasterization reports {@code noImageMessage}.
     */
    public void exportSvg(byte[] svg, String noImageMessage, String pageSize, Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                com.editora.editor.PreviewImageLoader.SvgPng r = com.editora.editor.PreviewImageLoader.svgToPng(
                        svg, com.editora.editor.PreviewImageLoader.PRINT_RASTER_SCALE);
                if (r == null) {
                    result = new Result(false, noImageMessage);
                } else {
                    writePageImages(java.util.List.of(PageImage.of(r.png(), r.pixelScale())), pageSize, out);
                    result = new Result(true, "");
                }
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "SVG PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports JavaFX images that are already cut to pages (the Project Map output), one per page at
     * {@code pointsPerPixel}, off the FX thread. See {@link ImagePdfWriter#writePages}.
     */
    public void exportFxPages(
            java.util.List<Image> pages,
            double pointsPerPixel,
            String pageSize,
            boolean landscape,
            Path out,
            Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                java.util.List<java.awt.image.BufferedImage> converted = new java.util.ArrayList<>();
                for (Image image : pages == null ? java.util.List.<Image>of() : pages) {
                    java.awt.image.BufferedImage buffered = toBufferedImage(image);
                    if (buffered != null) {
                        converted.add(buffered);
                    }
                }
                if (converted.isEmpty()) {
                    throw new IllegalStateException("nothing to export (the map produced no image)");
                }
                ImagePdfWriter.writePages(converted, pointsPerPixel, pageSize, landscape, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "JavaFX image PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result delivered = result;
            Platform.runLater(() -> onResult.accept(delivered));
        });
    }

    private static java.awt.image.BufferedImage toBufferedImage(Image image) {
        if (image == null || image.getPixelReader() == null) {
            return null;
        }
        int width = (int) Math.ceil(image.getWidth());
        int height = (int) Math.ceil(image.getHeight());
        if (width < 1 || height < 1) {
            return null;
        }
        int[] pixels = new int[Math.multiplyExact(width, height)];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        java.awt.image.BufferedImage buffered =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        return buffered;
    }

    /** Stops the background export thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
