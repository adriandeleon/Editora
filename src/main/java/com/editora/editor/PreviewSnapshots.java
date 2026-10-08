package com.editora.editor;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.transform.Transform;

import com.editora.structured.StructuredParser;
import com.editora.structured.XmlParser;

import static com.editora.i18n.Messages.tr;

/**
 * Preview → print/PDF snapshots (Markwhen / JSON-YAML-TOML / XML / the summary previews): the whole
 * tree/timeline — not just the visible viewport — rendered off-screen, always light, as PNG images that
 * know their density and where they may be cut ({@link Chunk}). The code behind
 * {@link EditorBuffer#snapshotPreviewChunks}; it lives here to keep that file under its size cap.
 *
 * <p>Laying out and snapshotting need the FX thread, so the work is cut up to keep the window alive: a row
 * list is taken one {@linkplain #ROWS_PER_CHUNK chunk} per runnable, and every image is PNG-encoded on a
 * worker thread; the next chunk starts only when the last one is encoded, so at most one chunk's raw pixels
 * exist at a time.
 */
public final class PreviewSnapshots {

    /**
     * Image pixels per logical pixel. 2× is 144 dpi at one logical pixel per point (the 1100 px previews,
     * fitted to a page, come out near 300 dpi). 3× would be sharper still but costs 2.25× the pixels at
     * every step, and the row cap makes that the limit: {@link #MAX_PRINT_ROWS} rows of a typical tree are
     * about 450 × 88,000 logical pixels — 160 million pixels at 2×, 360 million at 3× — to snapshot on the FX
     * thread, encode, decode again and draw.
     */
    public static final double SCALE = 2.0;

    /** Max rows captured for a tree (the parser already caps nodes at 50k; this bounds the image size). */
    static final int MAX_PRINT_ROWS = 4000;

    /**
     * Rows per snapshot chunk — each chunk is one bounded image (about 4,400 px tall at 2×), so a big tree
     * can't build a giant texture, and one chunk is a short stay on the FX thread.
     */
    static final int ROWS_PER_CHUNK = 100;

    /** Fixed width the Markwhen timeline and the summary previews are re-laid-out to for their snapshot. */
    static final double EXPORT_WIDTH = 1100;

    /** Width of the OpenAPI docs snapshot: running text, so narrower than the diagram-like previews. */
    public static final double DOCS_WIDTH = 760;

    /** A single (non-chunked) preview drops below {@link #SCALE} — never below 1× — past this many pixels. */
    static final long MAX_SINGLE_PIXELS = 24_000_000;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(PreviewSnapshots.class.getName());

    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "preview-snapshot");
        t.setDaemon(true);
        return t;
    });

    private PreviewSnapshots() {}

    /**
     * The images of one preview, top to bottom. For a tree, {@code shownRows} of its {@code totalRows} rows
     * were captured (both 0 for a preview that is not a row list).
     */
    /**
     * One snapshot: a PNG at {@code scale} image pixels per logical pixel, the pixel rows it may be cut at
     * ({@code cuts}, null when it has no rows), and whether it {@code continues} the chunk before it. The
     * print and PDF side turns it into its own page image — this package does not depend on that one.
     */
    public record Chunk(byte[] png, double scale, int[] cuts, boolean continues) {}

    public record Result(List<Chunk> images, int shownRows, int totalRows) {
        /** The tree was longer than the row cap: the output ends with a line saying so. */
        public boolean truncated() {
            return shownRows < totalRows;
        }
    }

    /** What a buffer's preview is, for a snapshot: one laid-out node, or a list of rows to chunk. */
    private record Plan(Node single, List<Node> rows, int totalRows, String treeClass) {
        static Plan of(VBox box) {
            box.getStyleClass().add("markdown-preview");
            return new Plan(box, null, 0, null);
        }
    }

    /**
     * Snapshots {@code b}'s preview and hands {@code done} the result — or {@code null} for a buffer whose
     * preview isn't snapshot-based (Markdown/CSV/Mermaid/diagram export semantically / via their CLI
     * instead), does not parse, or failed to render. Call on the FX thread; {@code progress} (chunks done,
     * chunks in all) and {@code done} are called on it too, {@code done} always later than this call.
     */
    static void capture(EditorBuffer b, String lightUa, BiConsumer<Integer, Integer> progress, Consumer<Result> done) {
        Plan plan;
        Scene scene;
        try {
            plan = plan(b);
            scene = plan == null ? null : scene(b, lightUa);
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "preview snapshot failed", e);
            plan = null;
            scene = null;
        }
        if (plan == null) {
            Platform.runLater(() -> done.accept(null));
        } else if (plan.single() != null) {
            Node node = plan.single();
            Scene sc = scene;
            step(done, () -> {
                Shot shot = shoot(sc, node, true);
                encode(shot, done, png -> {
                    progress.accept(1, 1);
                    Chunk image = new Chunk(png, shot.scale(), shot.cuts(), false);
                    done.accept(new Result(List.of(image), 0, 0));
                });
            });
        } else {
            new RowCapture(plan, scene, progress, done).next();
        }
    }

    private static Plan plan(EditorBuffer b) {
        String text = b.getArea().getText();
        if (b.hasGithubActionsPreview()) {
            return Plan.of(GithubActionsPreview.content(com.editora.ghactions.Workflow.parse(text), EXPORT_WIDTH));
        }
        if (b.isStructured()) {
            StructuredParser.Parsed p = StructuredParser.parse(text, b.structuredFormat());
            if (!p.ok()) {
                return null;
            }
            if (p.isOpenApi() && b.showsApiDocs()) { // what the preview shows: the docs, not the tree behind them
                VBox docs = OpenApiDoc.content(p.openApi());
                docs.setPrefWidth(DOCS_WIDTH);
                return new Plan(docs, null, 0, null);
            }
            return new Plan(
                    null,
                    StructuredTree.printableRows(p.root(), MAX_PRINT_ROWS),
                    StructuredTree.rowCount(p.root()),
                    "structured-tree");
        }
        if (b.hasPomPreview()) {
            return Plan.of(PomPreview.content(com.editora.maven.PomSummary.parse(text), EXPORT_WIDTH));
        }
        if (b.isXml()) {
            XmlParser.Parsed p = XmlParser.parse(text);
            return p.ok()
                    ? new Plan(
                            null,
                            XmlTree.printableRows(p.root(), MAX_PRINT_ROWS),
                            XmlTree.rowCount(p.root()),
                            "xml-tree")
                    : null;
        }
        if (b.isCrontab()) {
            return Plan.of(CrontabPreview.content(
                    com.editora.cron.Crontab.parse(text), java.time.LocalDateTime.now(), EXPORT_WIDTH));
        }
        if (b.isFstab()) {
            return Plan.of(FstabPreview.content(com.editora.fstab.Fstab.parse(text), EXPORT_WIDTH));
        }
        if (b.isSystemd()) {
            return Plan.of(SystemdPreview.content(
                    com.editora.systemd.SystemdUnit.parse(text), java.time.LocalDateTime.now(), EXPORT_WIDTH));
        }
        if (b.isSshConfig()) {
            return Plan.of(SshConfigPreview.content(com.editora.sshconfig.SshConfig.parse(text), EXPORT_WIDTH));
        }
        if (b.isDockerfile()) {
            return Plan.of(DockerfilePreview.content(com.editora.dockerfile.Dockerfile.parse(text), EXPORT_WIDTH));
        }
        if (b.isMarkwhen()) {
            com.editora.markwhen.Timeline model = com.editora.markwhen.MarkwhenParser.parse(text);
            Node timeline = b.getMarkwhenView() == EditorBuffer.MarkwhenView.CALENDAR
                    ? MarkwhenCalendar.build(model, 1.0, EXPORT_WIDTH)
                    : MarkwhenTimeline.build(model, 1.0, EXPORT_WIDTH);
            return Plan.of(new VBox(timeline));
        }
        return null;
    }

    /**
     * The throwaway off-screen scene the snapshots are laid out in, carrying the buffer's app/syntax
     * stylesheets so the token CSS resolves. Its user-agent stylesheet is forced to {@code lightUa} (Primer
     * Light) when given, so the {@code -color-*}-based tree/timeline colors resolve to an ink-friendly light
     * palette regardless of the app theme (a snapshot PDF/print is always light, like the native-vector
     * exporters); a null falls back to the inherited/global UA (e.g. in a headless test).
     */
    private static Scene scene(EditorBuffer b, String lightUa) {
        Scene sc = new Scene(new Group());
        Scene live = b.getNode().getScene();
        if (live != null) {
            sc.getStylesheets().setAll(live.getStylesheets());
        }
        if (lightUa != null) {
            sc.setUserAgentStylesheet(lightUa);
        } else if (live != null && live.getUserAgentStylesheet() != null) {
            sc.setUserAgentStylesheet(live.getUserAgentStylesheet());
        }
        return sc;
    }

    /** A snapshot's pixels (in {@code image}'s own array), its density and its row boundaries. */
    private record Shot(BufferedImage image, double scale, int[] cuts) {}

    /**
     * Lays {@code node} out in {@code scene} at its preferred size and snapshots it on white at
     * {@link #SCALE} ({@code mayReduce}: less for a very large single preview). The pixels are read straight
     * into a {@code BufferedImage} — one bulk copy — which is what the encoder thread compresses. FX thread.
     */
    private static Shot shoot(Scene scene, Node node, boolean mayReduce) {
        Group holder = (Group) scene.getRoot();
        holder.getChildren().setAll(node);
        holder.applyCss();
        holder.layout();
        Bounds bounds = node.getLayoutBounds();
        double scale = SCALE;
        double pixels = bounds.getWidth() * bounds.getHeight() * scale * scale;
        if (mayReduce && pixels > MAX_SINGLE_PIXELS) {
            scale = Math.max(1.0, scale * Math.sqrt(MAX_SINGLE_PIXELS / pixels));
        }
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.WHITE); // backstop behind any transparent margins
        sp.setTransform(Transform.scale(scale, scale));
        WritableImage img = node.snapshot(sp, null);
        int[] cuts = rowCuts(node, scale, img == null ? 0 : (int) img.getHeight());
        holder.getChildren().clear();
        if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
            return null;
        }
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); // opaque: the fill is white
        int[] data = ((DataBufferInt) buf.getRaster().getDataBuffer()).getData();
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), data, 0, w);
        return new Shot(buf, scale, cuts);
    }

    /**
     * The pixel rows of {@code node}'s snapshot at which it may be cut without splitting content: the gaps
     * between the children of a {@code VBox}, and of the {@code VBox}es stacked directly inside it (those
     * span its width, so a cut between their children crosses nothing else). Empty for any other node.
     */
    private static int[] rowCuts(Node node, double scale, int pixelHeight) {
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>();
        if (node instanceof VBox box) {
            collectCuts(box, box, scale, pixelHeight, cuts);
        }
        return cuts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void collectCuts(VBox root, VBox box, double scale, int pixelHeight, java.util.Set<Integer> cuts) {
        double top = root.getLayoutBounds().getMinY();
        Node previous = null;
        for (Node child : box.getChildren()) {
            if (!child.isManaged() || !child.isVisible()) {
                continue;
            }
            if (previous != null) {
                double above = root.sceneToLocal(previous.localToScene(previous.getBoundsInLocal()))
                        .getMaxY();
                double below = root.sceneToLocal(child.localToScene(child.getBoundsInLocal()))
                        .getMinY();
                int cut = (int) Math.round(((above + below) / 2 - top) * scale);
                if (cut > 0 && cut < pixelHeight) {
                    cuts.add(cut);
                }
            }
            if (child instanceof VBox nested) {
                collectCuts(root, nested, scale, pixelHeight, cuts);
            }
            previous = child;
        }
    }

    /** Runs one FX-thread stage later (so the caller returns first); a failure ends the capture with null. */
    private static void step(Consumer<Result> done, Runnable stage) {
        Platform.runLater(() -> {
            try {
                stage.run();
            } catch (RuntimeException | OutOfMemoryError e) {
                LOG.log(java.util.logging.Level.WARNING, "preview snapshot failed", e);
                done.accept(null);
            }
        });
    }

    /** PNG-encodes {@code shot} on the worker thread and continues with {@code then} on the FX thread. */
    private static void encode(Shot shot, Consumer<Result> done, Consumer<byte[]> then) {
        if (shot == null) {
            done.accept(null);
            return;
        }
        ENCODER.submit(() -> {
            byte[] png = null;
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                if (javax.imageio.ImageIO.write(shot.image(), "png", out)) {
                    png = out.toByteArray();
                }
            } catch (Throwable e) { // Throwable: an Error on a submit()'d task is otherwise swallowed
                LOG.log(java.util.logging.Level.WARNING, "preview snapshot encoding failed", e);
            }
            byte[] encoded = png;
            Platform.runLater(() -> {
                if (encoded == null) {
                    done.accept(null);
                } else {
                    then.accept(encoded);
                }
            });
        });
    }

    /** One row list being captured chunk by chunk; FX thread only. */
    private static final class RowCapture {
        private final Plan plan;
        private final Scene scene;
        private final BiConsumer<Integer, Integer> progress;
        private final Consumer<Result> done;
        private final List<Chunk> images = new ArrayList<>();
        private final int shown;
        private final int chunks;
        private int at;

        RowCapture(Plan plan, Scene scene, BiConsumer<Integer, Integer> progress, Consumer<Result> done) {
            this.plan = plan;
            this.scene = scene;
            this.progress = progress;
            this.done = done;
            this.shown = Math.min(plan.rows().size(), MAX_PRINT_ROWS);
            this.chunks = Math.max(1, (shown + ROWS_PER_CHUNK - 1) / ROWS_PER_CHUNK);
        }

        void next() {
            step(done, () -> {
                int end = Math.min(at + ROWS_PER_CHUNK, shown);
                VBox chunk = new VBox();
                chunk.getStyleClass().add(plan.treeClass());
                chunk.setFillWidth(false);
                // The 6 px frame of the whole list: its top on the first chunk, its bottom on the last.
                chunk.setPadding(new javafx.geometry.Insets(at == 0 ? 6 : 0, 6, end >= shown ? 6 : 0, 6));
                chunk.getChildren().addAll(plan.rows().subList(at, end));
                if (end >= shown && shown < plan.totalRows()) {
                    chunk.getChildren().add(truncationRow(shown, plan.totalRows()));
                }
                boolean first = at == 0;
                Shot shot = shoot(scene, chunk, false);
                chunk.getChildren().clear(); // the rows are done with: let them go chunk by chunk
                encode(shot, done, png -> {
                    images.add(new Chunk(png, shot.scale(), shot.cuts(), !first));
                    at = end;
                    progress.accept(images.size(), chunks);
                    if (at < shown) {
                        next();
                    } else {
                        done.accept(new Result(List.copyOf(images), shown, plan.totalRows()));
                    }
                });
            });
        }
    }

    /** The last line of a capped tree: says in the output itself that rows are missing. */
    private static Node truncationRow(int shown, int total) {
        Text note = new Text(tr("preview.snapshot.truncated", shown, total));
        note.getStyleClass().add("structured-punct");
        TextFlow row = new TextFlow(note);
        row.getStyleClass().add("structured-row");
        row.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        return row;
    }
}
