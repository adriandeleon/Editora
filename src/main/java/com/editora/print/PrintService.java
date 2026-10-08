package com.editora.print;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javafx.application.Platform;
import javafx.print.PageLayout;
import javafx.print.PageRange;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.MarkdownPrintAssets;
import com.editora.editor.MarkdownRenderer;
import com.editora.editor.PreviewImageLoader;
import com.editora.editor.TextMateHighlighter;
import com.editora.mermaid.Mermaid;
import com.editora.pdf.HiDpiImage;
import com.editora.pdf.ImagePaging;
import com.editora.pdf.PageImage;
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
                deliver(onReady, new Prepared(layout -> List.of(imagePage(img, Mermaid.RENDER_SCALE, layout)), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares a print job from pre-rendered PNG images of unknown density, one image pixel per point (Typst
     * pages, a DOT/PlantUML render). See {@link #preparePageImages}.
     */
    public void prepareImages(List<byte[]> pngImages, Consumer<Prepared> onReady) {
        List<PageImage> images = new java.util.ArrayList<>();
        for (byte[] png : pngImages == null ? List.<byte[]>of() : pngImages) {
            if (png != null) {
                images.add(PageImage.of(png));
            }
        }
        preparePageImages(images, onReady);
    }

    /**
     * Prepares a print job from PNG snapshots of a Markwhen / JSON-YAML-TOML / XML / summary preview (see
     * {@code EditorBuffer.snapshotPreview}). Each image is laid out at its logical size, fitted to the
     * printable width and continued over pages when tall, cut between rows — the print analogue of
     * {@code pdf/ImagePdfWriter}, on the same {@link ImagePaging} geometry. The images stay encoded: the
     * {@code Paginator} decodes the one or two a page shows when that page is asked for.
     */
    public void preparePageImages(List<PageImage> pageImages, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                List<PageImage> images = pageImages == null
                        ? List.of()
                        : pageImages.stream()
                                .filter(i -> i != null
                                        && i.source().pixelWidth() > 0
                                        && i.source().pixelHeight() > 0)
                                .toList();
                if (images.isEmpty()) {
                    deliver(onReady, new Prepared(null, "nothing to print"));
                    return;
                }
                List<ImagePaging.Source> sources =
                        images.stream().map(PageImage::source).toList();
                deliver(onReady, new Prepared(imagePaginator(sources, decoding(images)), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares an SVG document: rasterized <b>here</b>, on the prepare thread (a large SVG takes hundreds of
     * milliseconds), at {@link PreviewImageLoader#PRINT_RASTER_SCALE}× and printed at the SVG's own size. A
     * failed rasterization reports {@code noImageMessage}.
     */
    public void prepareSvg(byte[] svg, String noImageMessage, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                PreviewImageLoader.SvgPng r = PreviewImageLoader.svgToPng(svg, PreviewImageLoader.PRINT_RASTER_SCALE);
                if (r == null) {
                    deliver(onReady, new Prepared(null, noImageMessage));
                    return;
                }
                List<PageImage> images = List.of(PageImage.of(r.png(), r.pixelScale()));
                deliver(
                        onReady,
                        new Prepared(imagePaginator(List.of(images.get(0).source()), decoding(images)), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Prepares already-rendered JavaFX images, such as a complete Project Map snapshot, for printing. A
     * {@link HiDpiImage} is laid out at its logical size.
     */
    public void prepareFxImages(List<Image> sourceImages, Consumer<Prepared> onReady) {
        exec.submit(() -> {
            try {
                List<Image> images = sourceImages == null
                        ? List.of()
                        : sourceImages.stream()
                                .filter(java.util.Objects::nonNull)
                                .toList();
                if (images.isEmpty()) {
                    deliver(onReady, new Prepared(null, "nothing to print"));
                    return;
                }
                List<ImagePaging.Source> sources = images.stream()
                        .map(img -> new ImagePaging.Source(
                                (int) Math.ceil(img.getWidth()),
                                (int) Math.ceil(img.getHeight()),
                                HiDpiImage.scaleOf(img),
                                null,
                                false))
                        .toList();
                deliver(onReady, new Prepared(imagePaginator(sources, images::get), null));
            } catch (Throwable e) {
                deliver(onReady, new Prepared(null, message(e)));
            }
        });
    }

    /**
     * Decodes PNG {@code i} when a page needs it, keeping the last two (a page shows at most the end of one
     * chunk and the start of the next). A 4,000-row tree at 2× is some 150 million pixels: decoded all at
     * once that is over half a gigabyte, encoded a few megabytes. FX thread only.
     */
    private static IntFunction<Image> decoding(List<PageImage> images) {
        java.util.Map<Integer, Image> recent = new java.util.LinkedHashMap<>(4, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Image> eldest) {
                return size() > 2;
            }
        };
        return i -> recent.computeIfAbsent(
                i, k -> new Image(new ByteArrayInputStream(images.get(k).png())));
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

    /**
     * A single page holding {@code img} — rendered at {@code pixelScale} image pixels per logical pixel —
     * shrunk (preserving ratio) to fit the printable area. A diagram smaller than the page keeps its size:
     * it is never enlarged past one logical pixel per point.
     */
    static Node imagePage(Image img, double pixelScale, PageLayout layout) {
        return imagePage(img, pixelScale, layout.getPrintableWidth(), layout.getPrintableHeight());
    }

    /** {@link #imagePage(Image, double, PageLayout)} for a printable area given in points. */
    public static Node imagePage(Image img, double pixelScale, double pw, double ph) {
        double w = img.getWidth() / pixelScale;
        double h = img.getHeight() / pixelScale;
        double scale = ImagePaging.fitWithin(w, h, pw, ph);
        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setFitWidth(w * scale);
        iv.setFitHeight(h * scale);
        StackPane root = new StackPane(iv);
        root.setPrefSize(pw, ph);
        return root;
    }

    /**
     * The paginator of raster images: the page plan is {@link ImagePaging#layout} for the layout's printable
     * area (never rotated — the orientation is the user's choice in Page Setup), and a page's node is built
     * when it is asked for, from the images {@code images} hands out.
     */
    static Paginator imagePaginator(List<ImagePaging.Source> sources, IntFunction<Image> images) {
        return new Paginator() {
            @Override
            public List<Node> paginate(PageLayout layout) {
                Pages pages = pages(layout);
                List<Node> nodes = new java.util.ArrayList<>();
                for (int i = 0; i < pages.count(); i++) {
                    nodes.add(pages.get(i));
                }
                return nodes;
            }

            @Override
            public Pages pages(PageLayout layout) {
                return imagePages(sources, images, layout.getPrintableWidth(), layout.getPrintableHeight());
            }
        };
    }

    /** The pages of {@code sources} on a printable area of {@code availW × availH} points. */
    public static Pages imagePages(
            List<ImagePaging.Source> sources, IntFunction<Image> images, double availW, double availH) {
        List<ImagePaging.Page> plan =
                ImagePaging.layout(sources, availW, availH, false, (i, row) -> blankRow(images.apply(i), row));
        return new Pages() {
            @Override
            public int count() {
                return Math.max(1, plan.size()); // nothing to draw still prints one (blank) page
            }

            @Override
            public Node get(int index) {
                Pane root = new Pane();
                root.setPrefSize(availW, availH);
                if (index < plan.size()) {
                    for (ImagePaging.Slice s : plan.get(index).slices()) {
                        ImageView iv = new ImageView(images.apply(s.image()));
                        iv.setViewport(
                                new javafx.geometry.Rectangle2D(s.srcX(), s.srcY(), s.srcWidth(), s.srcHeight()));
                        iv.setFitWidth(s.width());
                        iv.setFitHeight(s.height());
                        iv.setSmooth(true);
                        iv.relocate(s.x(), s.y());
                        root.getChildren().add(iv);
                    }
                }
                return root;
            }
        };
    }

    /** Whether pixel row {@code row} of {@code img} is one colour from edge to edge — a place to cut. */
    private static boolean blankRow(Image img, int row) {
        int w = img == null ? 0 : (int) img.getWidth();
        if (w < 1 || row < 0 || row >= (int) img.getHeight() || img.getPixelReader() == null) {
            return false;
        }
        int[] argb = new int[w];
        img.getPixelReader().getPixels(0, row, w, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        for (int v : argb) {
            if (v != argb[0]) {
                return false;
            }
        }
        return true;
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
