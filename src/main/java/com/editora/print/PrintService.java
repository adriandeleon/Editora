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
import javafx.print.PageRange;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.MarkdownPrintAssets;
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

    /**
     * The pages of one pagination. {@link #get} may build its node on demand, so a long document need not
     * hold every page's nodes at once (code pages are line slices — see {@link CodePrintLayout#pages}); a
     * caller asks for a page when it shows or prints it and keeps no reference afterwards. FX thread only.
     */
    public interface Pages {
        int count();

        /** The node of page {@code index} (0-based). A lazy implementation builds a fresh node per call. */
        Node get(int index);

        /** Pages that are already built. */
        static Pages of(List<Node> nodes) {
            List<Node> copy = List.copyOf(nodes);
            return new Pages() {
                @Override
                public int count() {
                    return copy.size();
                }

                @Override
                public Node get(int index) {
                    return copy.get(index);
                }
            };
        }
    }

    /** Builds the printable page nodes for a given page layout. Runs on the FX thread. */
    @FunctionalInterface
    public interface Paginator {
        List<Node> paginate(PageLayout layout);

        /**
         * The same pages as {@link #paginate}, possibly built on demand. The default wraps the eager list;
         * a paginator whose pages are cheap to rebuild (code) overrides it.
         */
        default Pages pages(PageLayout layout) {
            return Pages.of(paginate(layout));
        }
    }

    /** Where printed pages go: a {@link PrinterJob} in the app, a recorder in tests. */
    public interface PageSink {
        boolean printPage(PageLayout layout, Node page);

        boolean endJob();

        static PageSink of(PrinterJob job) {
            return new PageSink() {
                @Override
                public boolean printPage(PageLayout layout, Node page) {
                    return job.printPage(layout, page);
                }

                @Override
                public boolean endJob() {
                    return job.endJob();
                }
            };
        }
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
                deliver(onReady, new Prepared(codePaginator(lines, lineNumbers), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares the lines {@code [start, end)} of {@code text} as code, numbered from {@code firstLineNumber}
     * — the excerpt's own line numbers in its file. The whole of {@code text} is highlighted and the
     * excerpt cut out of the result, so a selection that starts inside a block comment or a multi-line
     * string is coloured as it is in the editor.
     */
    public void prepareCodeLines(
            String text,
            int start,
            int end,
            int firstLineNumber,
            String fileName,
            boolean highlight,
            boolean lineNumbers,
            int tabSize,
            Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                StyleSpans<Collection<String>> spans = excerptSpans(text, start, end, highlight ? fileName : null);
                List<List<PdfText.Run>> lines =
                        PdfText.splitIntoLineRuns(text.substring(start, end), spans, Math.max(1, tabSize));
                deliver(
                        onReady,
                        new Prepared(
                                new Paginator() {
                                    @Override
                                    public List<Node> paginate(PageLayout layout) {
                                        Pages pages = pages(layout);
                                        List<Node> all = new java.util.ArrayList<>(pages.count());
                                        for (int i = 0; i < pages.count(); i++) {
                                            all.add(pages.get(i));
                                        }
                                        return all;
                                    }

                                    @Override
                                    public Pages pages(PageLayout layout) {
                                        return CodePrintLayout.pages(
                                                lines,
                                                layout,
                                                lineNumbers,
                                                Font.font(MONO_FAMILY, CodePrintLayout.FONT_SIZE),
                                                firstLineNumber);
                                    }
                                },
                                null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * The highlight spans of {@code text[start, end)}, cut from the spans of the whole text; null when
     * {@code fileName} is null (no highlighting asked for) or has no grammar.
     */
    public static StyleSpans<Collection<String>> excerptSpans(String text, int start, int end, String fileName) {
        IGrammar grammar = fileName == null ? null : GrammarRegistry.shared().forFileName(fileName);
        if (grammar == null || end <= start) {
            return null;
        }
        StyleSpans<Collection<String>> all = TextMateHighlighter.compute(text, grammar);
        return all == null || all.length() < end ? null : all.subView(start, end);
    }

    /** Prepares {@code markdown} as the rendered preview (block-aware pagination), always in the light theme. */
    public void prepareMarkdown(String markdown, Path baseDir, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(markdown);
                deliver(onReady, new Prepared(markdownPaginator(ast, baseDir), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares an already-built CommonMark {@code document} — content that is data rather than Markdown
     * source (the CSV print builds its table node by node, so a cell is never re-parsed as markup).
     */
    public void prepareDocument(org.commonmark.node.Node document, Path baseDir, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                deliver(onReady, new Prepared(markdownPaginator(document, baseDir), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * The paginator for a parsed document, with its images, Mermaid diagrams and code colours resolved
     * <b>here</b>, on the prepare thread. Pagination measures every block exactly once, so whatever is not
     * final by then is wrong on paper: an image still loading measures 0px and overflows its page when it
     * arrives, and highlighting applied a pulse later never reaches the printer at all.
     */
    private static Paginator markdownPaginator(org.commonmark.node.Node ast, Path baseDir) {
        MarkdownPrintAssets assets = MarkdownPrintAssets.resolve(ast, baseDir);
        return layout -> MarkdownPrintLayout.paginate(
                ast, baseDir, assets, layout.getPrintableWidth(), layout.getPrintableHeight());
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

    /**
     * Page nodes for images that are already cut to pages (the Project Map output): one image per page,
     * drawn from the top-left corner at {@code pointsPerPixel}. Must run on the FX thread.
     */
    public static List<Node> pagedImages(List<Image> images, double pointsPerPixel, PageLayout layout) {
        double availW = layout.getPrintableWidth();
        double availH = layout.getPrintableHeight();
        List<Node> pages = new java.util.ArrayList<>();
        for (Image img : images == null ? List.<Image>of() : images) {
            if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
                continue;
            }
            double scale = Math.min(
                    pointsPerPixel > 0 ? pointsPerPixel : 1.0,
                    Math.min(availW / img.getWidth(), availH / img.getHeight()));
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setFitWidth(img.getWidth() * scale);
            StackPane root = new StackPane(iv);
            StackPane.setAlignment(iv, javafx.geometry.Pos.TOP_LEFT);
            root.setPrefSize(availW, availH);
            pages.add(root);
        }
        if (pages.isEmpty()) {
            pages.add(new StackPane());
        }
        return pages;
    }

    /** A code paginator whose {@link Paginator#pages} builds each page only when it is asked for. */
    private static Paginator codePaginator(List<List<PdfText.Run>> lines, boolean lineNumbers) {
        return new Paginator() {
            @Override
            public List<Node> paginate(PageLayout layout) {
                return CodePrintLayout.paginate(lines, layout, lineNumbers, font());
            }

            @Override
            public Pages pages(PageLayout layout) {
                return CodePrintLayout.pages(lines, layout, lineNumbers, font());
            }

            private Font font() {
                return Font.font(MONO_FAMILY, CodePrintLayout.FONT_SIZE);
            }
        };
    }

    /**
     * The 0-based indices of the pages a job should print, ascending and without repeats, for the page
     * ranges chosen in the print dialog ({@code JobSettings.getPageRanges()}: 1-based, inclusive, possibly
     * several, possibly overlapping). {@code null} or empty means every page; a range is clamped to the
     * document, so one that lies wholly past the last page selects nothing.
     *
     * <p>The pages have to be chosen here: JavaFX hands the ranges to the platform job, which then asks its
     * pageable only for the page numbers inside them — and JavaFX answers each request with <em>the next
     * node the app submits</em>, whatever its number. Submitting every page therefore printed the
     * document's first pages under the requested numbers and failed the job on the first page too many.
     */
    public static int[] pageIndices(PageRange[] ranges, int pageCount) {
        if (pageCount <= 0) {
            return new int[0];
        }
        if (ranges == null || ranges.length == 0) {
            return java.util.stream.IntStream.range(0, pageCount).toArray();
        }
        java.util.BitSet chosen = new java.util.BitSet(pageCount);
        for (PageRange range : ranges) {
            if (range == null) {
                continue;
            }
            int from = Math.max(1, range.getStartPage());
            int to = Math.min(pageCount, range.getEndPage());
            if (from <= to) {
                chosen.set(from - 1, to);
            }
        }
        return chosen.stream().toArray();
    }

    /**
     * Prints the pages of {@code pages} that the job's page ranges select, ends the job, and returns the
     * result. Must run on the FX thread.
     */
    public static Result printPages(List<Node> pages, PageLayout layout, PrinterJob job) {
        Pages all = Pages.of(pages);
        return printPages(
                all, pageIndices(job.getJobSettings().getPageRanges(), all.count()), layout, PageSink.of(job));
    }

    /** Prints the pages at {@code indices}, in that order, to {@code sink} and ends the job. */
    public static Result printPages(Pages pages, int[] indices, PageLayout layout, PageSink sink) {
        boolean ok = true;
        for (int index : indices) {
            if (!sink.printPage(layout, pages.get(index))) {
                ok = false;
                break;
            }
        }
        boolean ended = sink.endJob();
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
