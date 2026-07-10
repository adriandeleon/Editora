package com.editora.print;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.MarkdownRenderer;
import com.editora.editor.TextMateHighlighter;
import com.editora.mermaid.Mermaid;
import com.editora.pdf.PdfText;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Prepares a document for printing via {@code javafx.print} (the {@code PdfExportService} idiom): the
 * blocking/CPU work (tokenizing, parsing, running mmdc) runs on a single daemon thread and produces a
 * {@link Paginator} — a layout-agnostic recipe that builds the printable page nodes for any
 * {@link PageLayout}. The same paginator drives both the on-screen {@code PrintPreview} and the final
 * print, so what you preview is what prints. Building nodes and {@link PrinterJob#printPage} happen on
 * the FX thread.
 *
 * <p>Uses {@code javafx.print} specifically because AWT printing throws {@code HeadlessException}
 * under the app's {@code java.awt.headless=true} guard.
 */
public final class PrintService {

    /** Outcome of a print: {@code ok} plus an error {@code message} on failure. */
    public record Result(boolean ok, String message) {}

    /** Builds the printable page nodes for a given page layout. Runs on the FX thread. */
    @FunctionalInterface
    public interface Paginator {
        List<Node> paginate(PageLayout layout);
    }

    /** Result of the off-thread prepare step: a {@link Paginator} on success, else an {@code error}. */
    public record Prepared(Paginator paginator, String error) {
        public boolean ok() {
            return paginator != null;
        }
    }

    /** Bundled monospace family used for printed code (matches the editor/PDF default). */
    private static final String MONO_FAMILY = "JetBrains Mono";

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "print");
        t.setDaemon(true);
        return t;
    });

    /** Prepares {@code text} as code. Highlighting (when {@code highlight}) uses the grammar for {@code fileName}. */
    public void prepareCode(
            String text,
            String fileName,
            boolean highlight,
            boolean lineNumbers,
            int tabSize,
            Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                StyleSpans<Collection<String>> spans = null;
                if (highlight) {
                    IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
                    if (grammar != null) {
                        spans = TextMateHighlighter.compute(text, grammar);
                    }
                }
                List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns(text, spans, Math.max(1, tabSize));
                deliver(
                        onReady,
                        new Prepared(
                                layout -> CodePrintLayout.paginate(
                                        lines, layout, lineNumbers, Font.font(MONO_FAMILY, CodePrintLayout.FONT_SIZE)),
                                null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /** Prepares {@code markdown} as the rendered preview (block-aware pagination). */
    public void prepareMarkdown(String markdown, Path baseDir, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(markdown);
                deliver(onReady, new Prepared(layout -> MarkdownPrintLayout.paginate(ast, baseDir, layout), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /** Prepares a standalone Mermaid diagram (rendered to PNG via mmdc, scaled to fit one page). */
    public void prepareMermaid(String source, List<String> mmdc, boolean dark, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                Mermaid.Render r = Mermaid.renderPng(mmdc, source, dark);
                if (r.image() == null) {
                    deliver(onReady, new Prepared(null, r.error()));
                    return;
                }
                Image img = new Image(new ByteArrayInputStream(r.image()));
                deliver(onReady, new Prepared(layout -> List.of(imagePage(img, layout)), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares a print job from pre-rendered PNG images (a snapshot of an SVG / Markwhen / JSON-YAML-TOML /
     * XML / DOT-PlantUML preview — see {@code EditorBuffer.snapshotPreviewChunks}). Each image is scaled to
     * the printable width and, when tall, sliced across pages — the print analogue of
     * {@code pdf/ImagePdfWriter}. The PNGs are decoded off the FX thread; the {@code Paginator} then builds
     * the {@code ImageView} pages on the FX thread for the chosen {@link PageLayout}.
     */
    public void prepareImages(List<byte[]> pngImages, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                List<Image> images = new java.util.ArrayList<>();
                for (byte[] png : pngImages) {
                    if (png != null) {
                        images.add(new Image(new ByteArrayInputStream(png)));
                    }
                }
                if (images.isEmpty()) {
                    deliver(onReady, new Prepared(null, "nothing to print"));
                    return;
                }
                deliver(onReady, new Prepared(layout -> imagePages(images, layout), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /** Prints each page node, ends the job, and returns the result. Must run on the FX thread. */
    public static Result printPages(List<Node> pages, PageLayout layout, PrinterJob job) {
        boolean ok = true;
        for (Node page : pages) {
            if (!job.printPage(layout, page)) {
                ok = false;
                break;
            }
        }
        boolean ended = job.endJob();
        return ok && ended ? new Result(true, "") : new Result(false, "print job failed");
    }

    /** A single page holding {@code img} scaled (preserving ratio) to fit the printable area. */
    private static Node imagePage(Image img, PageLayout layout) {
        double pw = layout.getPrintableWidth();
        double ph = layout.getPrintableHeight();
        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(pw);
        iv.setFitHeight(ph);
        StackPane root = new StackPane(iv);
        root.setPrefSize(pw, ph);
        return root;
    }

    /** Lays each image across pages: scaled to the printable width, sliced by page height via an ImageView
     *  viewport (shares the pure fit/slice geometry with {@code pdf/ImagePdfWriter}). */
    private static List<Node> imagePages(List<Image> images, PageLayout layout) {
        double availW = layout.getPrintableWidth();
        double availH = layout.getPrintableHeight();
        List<Node> pages = new java.util.ArrayList<>();
        for (Image img : images) {
            int iw = (int) Math.ceil(img.getWidth());
            int ih = (int) Math.ceil(img.getHeight());
            if (iw < 1 || ih < 1) {
                continue;
            }
            double scale = com.editora.pdf.ImagePdfWriter.fitScale(iw, availW);
            int srcPageRows = com.editora.pdf.ImagePdfWriter.rowsPerPage(availH, scale);
            double drawW = iw * scale;
            for (int y = 0; y < ih; y += srcPageRows) {
                int h = Math.min(srcPageRows, ih - y);
                ImageView iv = new ImageView(img);
                iv.setViewport(new javafx.geometry.Rectangle2D(0, y, iw, h));
                iv.setPreserveRatio(true);
                iv.setFitWidth(drawW);
                StackPane root = new StackPane(iv);
                StackPane.setAlignment(iv, javafx.geometry.Pos.TOP_LEFT);
                root.setPrefSize(availW, availH);
                pages.add(root);
            }
        }
        if (pages.isEmpty()) {
            pages.add(new StackPane());
        }
        return pages;
    }

    private static void deliver(Consumer<Prepared> onReady, Prepared prepared) {
        Platform.runLater(() -> onReady.accept(prepared));
    }

    private static String message(Throwable e) {
        // Throwable, not Exception: a jlink/resource Error on the submit()'d task would otherwise be
        // swallowed by the Future, hanging the "Preparing…" status (see PdfExportService).
        java.util.logging.Logger.getLogger(PrintService.class.getName())
                .log(java.util.logging.Level.SEVERE, "Print prepare failed", e);
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Stops the background prepare thread (called when the owning window closes). */
    public void shutdown() {
        exec.shutdownNow();
    }
}
