package com.editora.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleUnaryOperator;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.typst.TypstRenderer;

/**
 * Renders Typst documents to JavaFX nodes for the 3-mode preview, asynchronously and <b>multi-page</b>. The
 * document-oriented analogue of {@link MermaidImages}/{@link DiagramImages}: a static façade with a daemon
 * executor and an LRU result cache, so the synchronous {@code EditorBuffer} preview build can drop a node
 * and have it filled later off the FX thread. Each cache entry is a <em>list</em> of page images (Typst
 * documents paginate), stacked in a {@link VBox}.
 *
 * <p><b>Retain-last-image.</b> On every edit the source hash changes, so a naive re-render would blank to a
 * "rendering…" placeholder and flash. Instead, the last successfully-rendered pages are kept per preview
 * ({@code retainKey}) and shown immediately while the new render runs — a placeholder appears only on the
 * <em>first</em> render, and a compile failure keeps the last good pages visible under a non-destructive
 * error banner. So editing a multi-page document updates in place without flicker.
 *
 * <p>Configured (enabled flag, command) from {@code TypstCoordinator.applySupport} via {@link #configure} —
 * the editor package can't depend on {@code ui}/{@code typst-service}, like the Mermaid/diagram injection.
 */
public final class TypstImages {

    private record Loaded(Image image, double logicalWidth) {}

    /** {@code more} = pages beyond the display cap that were not rendered into the preview (0 = all shown). */
    private record Cached(List<Loaded> pages, String error, int more, long at) {
        Cached(List<Loaded> pages, String error, int more) {
            this(pages, error, more, System.currentTimeMillis());
        }

        boolean ok() {
            return error == null && pages != null && !pages.isEmpty();
        }

        /**
         * A failure is only worth reusing briefly. The key is the source — not the tool — so a document that
         * failed because typst wasn't installed stayed "broken" after installing it: the install flow
         * re-renders every preview, which is a cache hit on the same text. (Mirrors DiagramImages.)
         */
        boolean expired() {
            return error != null && System.currentTimeMillis() - at > FAILURE_TTL_MS;
        }
    }

    /** How long a failed render is reused before being retried — mirrors {@link PreviewImageLoader}. */
    private static final long FAILURE_TTL_MS = 60_000;

    /** Cap on pages stacked in the preview — a many-page document would otherwise pin one texture per page.
     *  Beyond this the preview shows the first N pages + a "… more pages" note; export/print use all pages. */
    private static final int MAX_PREVIEW_PAGES = 40;

    /**
     * Cache bound expressed in <b>pages</b>, not entries — the cost is one decoded {@link Image} (a pinned GPU
     * texture) per page, and one Typst entry is a whole {@code MAX_PREVIEW_PAGES}-page document, so a plain
     * entry-count LRU (copied from {@code DiagramImages}, where an entry is one image) let a single 40-page
     * doc retain ~570 MB and eight distinct renders ~4.5 GB — against the {@code -Xmx2g} packaged heap (#461).
     * At A4/192-PPI a page is ~14 MB decoded, so ~60 pages ≈ 850 MB worst case. Both {@link #CACHE} and
     * {@link #LAST_GOOD} are bounded by this after every insert (never evicting the just-inserted entry).
     */
    private static final int MAX_CACHED_PAGES = 60;

    /** Access-ordered so a re-render of the visible doc keeps it; eviction is by {@link #evictToPageBudget}. */
    private static final Map<String, Cached> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Cached>(16, 0.75f, true));

    /** The last good render per preview surface, so a re-render shows it instead of a placeholder. */
    private static final Map<String, List<Loaded>> LAST_GOOD =
            Collections.synchronizedMap(new LinkedHashMap<String, List<Loaded>>(16, 0.75f, true));

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "typst-render");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean enabled;
    private static volatile List<String> command = List.of("typst");

    private TypstImages() {}

    /** Pushes the live Typst config (called at startup + every settings apply). */
    public static void configure(boolean enabled, List<String> command) {
        boolean toolChanged = TypstImages.enabled != enabled;
        TypstImages.enabled = enabled;
        if (command != null && !command.isEmpty()) {
            toolChanged |= !command.equals(TypstImages.command);
            TypstImages.command = List.copyOf(command);
        }
        if (toolChanged) {
            // The key is the source, never the tool — so a newly installed (or re-pointed) typst would
            // otherwise re-serve the cached "typst not found". That IS the install flow: it re-renders every
            // preview, hitting the same key. (Mirrors DiagramImages.configure.)
            CACHE.clear();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Evicts the least-recently-used entries of an access-ordered {@code cache} until the total page count
     * (via {@code pages}) is within {@code maxPages}, never removing the most-recent (just-inserted) entry.
     */
    private static <V> void evictToPageBudget(
            Map<String, V> cache, java.util.function.ToIntFunction<V> pages, int maxPages) {
        synchronized (cache) {
            List<Map.Entry<String, Integer>> oldestFirst = new ArrayList<>();
            for (Map.Entry<String, V> e : cache.entrySet()) {
                oldestFirst.add(Map.entry(e.getKey(), Math.max(0, pages.applyAsInt(e.getValue()))));
            }
            for (String k : keysToEvict(oldestFirst, maxPages)) {
                cache.remove(k);
            }
        }
    }

    /**
     * Given cache entries oldest→newest with their page counts, returns the oldest keys to drop so the total
     * retained pages fits {@code maxPages}, always keeping the newest entry (so the just-rendered document is
     * never evicted, even when it alone exceeds the budget). Pure; unit-tested.
     */
    static List<String> keysToEvict(List<Map.Entry<String, Integer>> oldestFirst, int maxPages) {
        int total = 0;
        for (Map.Entry<String, Integer> e : oldestFirst) {
            total += e.getValue();
        }
        List<String> evict = new ArrayList<>();
        int i = 0;
        while (total > maxPages && (oldestFirst.size() - evict.size()) > 1) {
            Map.Entry<String, Integer> e = oldestFirst.get(i++);
            evict.add(e.getKey());
            total -= e.getValue();
        }
        return evict;
    }

    /**
     * Returns a node for the Typst {@code source}, filled asynchronously with one {@link ImageView} per
     * page. {@code sizer} maps a page's logical width to the displayed fit width (the standalone preview
     * multiplies by the zoom factor). {@code retainKey} identifies the preview surface for retain-last-image;
     * {@code fileDir} is the saved file's own folder (where relative refs resolve) and {@code root} the
     * {@code --root} sandbox (possibly a higher project root) — both {@code null} for an untitled/remote buffer.
     */
    public static Node node(
            String source, DoubleUnaryOperator sizer, String retainKey, Path fileDir, Path root, String displayName) {
        StackPane host = new StackPane();
        host.getStyleClass().add("md-diagram");
        fill(host, source, sizer, retainKey, fileDir, root, displayName);
        return host;
    }

    private static void fill(
            StackPane host,
            String source,
            DoubleUnaryOperator sizer,
            String retainKey,
            Path fileDir,
            Path root,
            String displayName) {
        String key = key(source, fileDir, root);
        Cached hit = CACHE.get(key);
        if (hit != null && hit.expired()) {
            CACHE.remove(key);
            hit = null;
        }
        if (hit != null) {
            applyCached(host, hit, sizer, retainKey);
            return;
        }
        List<Loaded> retained = LAST_GOOD.get(retainKey);
        if (retained != null && !retained.isEmpty()) {
            host.getChildren().setAll(pagesColumn(retained, sizer, null, 0)); // keep last pages while re-rendering
        } else {
            host.getChildren().setAll(placeholder(Messages.tr("typst.rendering")));
        }
        List<String> cmd = command;
        EXEC.submit(() -> {
            TypstRenderer.Pages r = TypstRenderer.renderPages(cmd, source, fileDir, root, displayName);
            Cached result;
            if (r.ok()) {
                int total = r.pages().size();
                int show = Math.min(total, MAX_PREVIEW_PAGES);
                List<Loaded> pages = new ArrayList<>();
                for (int i = 0; i < show; i++) {
                    Image img = new Image(new ByteArrayInputStream(r.pages().get(i)));
                    if (img.isError() || img.getWidth() <= 0) {
                        pages = null;
                        break;
                    }
                    pages.add(new Loaded(img, img.getWidth() / TypstRenderer.RENDER_SCALE));
                }
                result = pages == null
                        ? new Cached(null, Messages.tr("typst.renderFailed"), 0)
                        : new Cached(pages, null, total - show);
            } else {
                result = new Cached(null, r.error(), 0);
            }
            CACHE.put(key, result);
            evictToPageBudget(CACHE, c -> c.pages() == null ? 0 : c.pages().size(), MAX_CACHED_PAGES);
            if (result.ok()) {
                LAST_GOOD.put(retainKey, result.pages());
                evictToPageBudget(LAST_GOOD, List::size, MAX_CACHED_PAGES);
            }
            Platform.runLater(() -> applyCached(host, result, sizer, retainKey));
        });
    }

    private static void applyCached(StackPane host, Cached c, DoubleUnaryOperator sizer, String retainKey) {
        if (c.ok()) {
            host.getChildren().setAll(pagesColumn(c.pages(), sizer, null, c.more()));
            return;
        }
        List<Loaded> retained = LAST_GOOD.get(retainKey);
        if (retained != null && !retained.isEmpty()) {
            // A compile error mid-edit: keep the last good pages, surface the error non-destructively.
            host.getChildren().setAll(pagesColumn(retained, sizer, c.error(), 0));
        } else {
            host.getChildren().setAll(errorNode(c.error()));
        }
    }

    private static VBox pagesColumn(List<Loaded> pages, DoubleUnaryOperator sizer, String error, int more) {
        VBox column = new VBox(12);
        column.getStyleClass().add("typst-pages");
        if (error != null) {
            column.getChildren().add(errorBanner(error));
        }
        for (Loaded p : pages) {
            ImageView view = new ImageView(p.image());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(Math.max(1, sizer.applyAsDouble(p.logicalWidth())));
            view.getStyleClass().add("typst-page");
            column.getChildren().add(view);
        }
        if (more > 0) {
            Label note = new Label(Messages.tr("typst.morePages", more));
            note.getStyleClass().add("diagram-placeholder");
            column.getChildren().add(note);
        }
        return column;
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("diagram-placeholder");
        return label;
    }

    private static Label errorBanner(String message) {
        Label label = new Label(banner(message));
        label.getStyleClass().add("typst-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label errorNode(String message) {
        Label label = new Label(banner(message));
        label.getStyleClass().add("diagram-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static String banner(String message) {
        return message == null || message.isBlank()
                ? Messages.tr("typst.renderFailed")
                : Messages.tr("typst.renderFailed") + "\n" + message.strip();
    }

    /**
     * Cache key = sha-256 of the source <b>plus the directory it renders in</b>, so editing invalidates but a
     * re-render of unchanged text hits.
     *
     * <p>The directory belongs in the key because a Typst document's {@code #import}/{@code #image} paths
     * resolve relative to it: keying on the source alone meant two different files with identical text (the
     * same boilerplate {@code main.typ} in two projects, each with its own {@code conf.typ}) served each
     * other's render.
     */
    private static String key(String source, Path fileDir, Path root) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(source.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(fileDir).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(root).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return source.length() + ":" + source.hashCode() + ":" + fileDir + ":" + root;
        }
    }
}
