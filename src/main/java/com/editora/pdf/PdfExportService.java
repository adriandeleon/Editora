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
     * One submitted export: whether it was cancelled, and how many pages its writer has started. The worker
     * thread carries the ticket of the export it is running ({@link #CURRENT}), which is how the writers —
     * static methods that know nothing of this service — report a page and hear a cancel.
     */
    private final class Ticket {
        volatile boolean cancelled;

        PdfExportService owner() {
            return PdfExportService.this;
        }

        int pages;
        long lastReported;
    }

    private static final ThreadLocal<Ticket> CURRENT = new ThreadLocal<>();
    /** The message of a cancelled export's {@link Result}; compared by identity in {@link #cancelled(Result)}. */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String CANCELLED = new String("cancelled");
    /** Least time between two progress reports of one export, in ms. */
    private static final long PROGRESS_INTERVAL_MS = 500;

    /** Submitted exports that have not delivered a result yet, the running one first. */
    private final java.util.Queue<Ticket> outstanding = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private volatile boolean shutdown;
    private volatile java.util.function.IntConsumer progress = pages -> {};

    /** Whether {@code result} is that of an export stopped by {@link #cancelAll()} or {@link #shutdown()}. */
    public static boolean cancelled(Result result) {
        return result != null && result.message() == CANCELLED; // identity: no real message is this object
    }

    /**
     * Whether {@code failure} is an export being abandoned — cancelled, or its thread interrupted by a
     * shutdown — rather than something wrong with the document or its fonts. The writers ask before they
     * retry with fewer fonts: an interrupt closes the font file being read, which looks like a bad font.
     */
    static boolean abandoned(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.CancellationException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called by a writer each time it starts a page. Counts the page for the progress listener and stops a
     * cancelled export here, between pages, by throwing — nothing has been written to the output yet (the
     * writers save the document last). A no-op outside an export of this service (a writer called directly).
     */
    static void pageStarted() {
        Ticket ticket = CURRENT.get();
        if (ticket != null) {
            ticket.owner().pageStarted(ticket);
        }
    }

    private void pageStarted(Ticket ticket) {
        if (ticket.cancelled || shutdown) {
            throw new java.util.concurrent.CancellationException("export cancelled");
        }
        int pages = ++ticket.pages;
        long now = System.nanoTime() / 1_000_000;
        if (now - ticket.lastReported >= PROGRESS_INTERVAL_MS) {
            ticket.lastReported = now;
            java.util.function.IntConsumer listener = progress;
            Platform.runLater(() -> {
                if (outstanding.peek() == ticket && !ticket.cancelled && !shutdown) { // still the running one
                    listener.accept(pages);
                }
            });
        }
    }

    /** Sets who hears, on the FX thread and at most twice a second, how many pages the running export has. */
    public void onProgress(java.util.function.IntConsumer pagesStarted) {
        progress = pagesStarted == null ? pages -> {} : pagesStarted;
    }

    /** The number of exports submitted and not finished: the running one and those queued behind it. */
    public int pending() {
        return outstanding.size();
    }

    /**
     * Cancels the running export and every queued one; each reports a {@link #cancelled(Result) cancelled}
     * result. The running writer stops at its next page — a stage that produces no pages (tokenizing a large
     * file, an external diagram tool) finishes first. Returns whether there was anything to cancel.
     */
    public boolean cancelAll() {
        boolean any = false;
        for (Ticket ticket : outstanding) {
            ticket.cancelled = true;
            any = true;
        }
        return any;
    }

    /**
     * Runs {@code task} — one export, which ends in {@link #deliver} — on the export thread under a ticket.
     * An export cancelled while it was queued never starts.
     */
    private void submit(Consumer<Result> onResult, Runnable task) {
        Ticket ticket = new Ticket();
        outstanding.add(ticket);
        try {
            exec.submit(() -> {
                CURRENT.set(ticket);
                try {
                    if (ticket.cancelled || shutdown) {
                        deliver(new Result(false, CANCELLED), onResult);
                    } else {
                        task.run();
                    }
                } finally {
                    CURRENT.remove();
                    outstanding.remove(ticket); // also when the task died without delivering
                }
            });
        } catch (RuntimeException rejected) { // shut down: nothing will run or report
            outstanding.remove(ticket);
            throw rejected;
        }
    }

    /**
     * Posts an export's result to the FX thread. A cancelled or shut-down export reports that and nothing
     * else: whatever its writer returned was produced while being stopped — an interrupted font read turns
     * into "characters could not be rendered" — and must not be taken for a finished PDF.
     */
    private void deliver(Result result, Consumer<Result> onResult) {
        Ticket ticket = CURRENT.get();
        boolean stopped = shutdown || (ticket != null && ticket.cancelled);
        Result delivered = stopped ? new Result(false, CANCELLED) : result;
        if (ticket != null) {
            outstanding.remove(ticket);
        }
        Platform.runLater(() -> onResult.accept(delivered));
    }

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
        submit(onResult, () -> {
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
            deliver(r, onResult);
        });
    }

    /**
     * Exports the lines {@code [start, end)} of {@code text} as a code PDF numbered from
     * {@code firstLineNumber} — the excerpt's own line numbers in its file. Highlighted from the whole text,
     * as {@code PrintService.prepareCodeLines} does.
     */
    public void exportCodeLines(
            String text,
            int start,
            int end,
            int firstLineNumber,
            String fileName,
            boolean highlight,
            boolean lineNumbers,
            int tabSize,
            String pageSize,
            Path out,
            Consumer<Result> onResult) {
        submit(onResult, () -> {
            Result result;
            try {
                StyleSpans<Collection<String>> spans =
                        com.editora.print.PrintService.excerptSpans(text, start, end, highlight ? fileName : null);
                int unrendered = CodePdfWriter.write(
                        text.substring(start, end), spans, lineNumbers, firstLineNumber, tabSize, pageSize, out);
                result = new Result(true, "", unrendered);
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Code PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            deliver(r, onResult);
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
        submit(onResult, () -> {
            Result result;
            try {
                int unrendered = MarkdownPdfWriter.write(markdown, baseDir, pageSize, mmdcCommand, out);
                result = new Result(true, "", unrendered);
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Markdown PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            deliver(r, onResult);
        });
    }

    /**
     * Exports an already-built CommonMark {@code document} through {@link MarkdownPdfWriter} — the CSV
     * export's table, whose cells are data and must not be parsed as Markdown. Runs off the FX thread.
     */
    public void exportDocument(
            org.commonmark.node.Node document, String pageSize, Path out, Consumer<Result> onResult) {
        submit(onResult, () -> {
            Result result;
            try {
                result = new Result(true, "", MarkdownPdfWriter.write(document, null, pageSize, null, out));
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Table PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            deliver(r, onResult);
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
        submit(onResult, () -> {
            Result result;
            try {
                writePageImages(images, pageSize, out);
                result = new Result(true, "");
            } catch (Throwable e) {
                LOG.log(java.util.logging.Level.SEVERE, "Image PDF export failed", e);
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            deliver(r, onResult);
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
        submit(onResult, () -> {
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
            deliver(r, onResult);
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
        submit(onResult, () -> {
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
            deliver(delivered, onResult);
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

    /**
     * Stops the background export thread (called when the owning window closes). Queued exports never run
     * and the running one is interrupted; whatever it still delivers is a cancelled result.
     */
    public void shutdown() {
        shutdown = true;
        exec.shutdownNow();
        outstanding.clear();
    }
}
