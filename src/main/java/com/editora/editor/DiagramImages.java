package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import com.editora.diagram.DiagramKind;
import com.editora.diagram.DiagramRenderer;
import com.editora.i18n.Messages;

/**
 * Renders diagram-as-code files (Graphviz DOT, PlantUML) to JavaFX nodes for the preview, asynchronously.
 * The generic analogue of {@link MermaidImages}: a static façade with a daemon executor and an LRU result
 * cache, so the synchronous {@code EditorBuffer} preview build can drop a placeholder node and have it
 * filled later off the FX thread. The cache is keyed by the kind + a hash of the source (+ theme only for
 * a theme-sensitive kind), so the debounced whole-document re-render does <b>not</b> re-invoke the tool
 * for unchanged diagrams. Configured (enabled flag, per-kind commands, theme) from
 * {@code DiagramCoordinator.applySupport} via {@link #configure} — the editor package can't depend on
 * {@code ui}, like the Mermaid/snippet/completion provider injection.
 */
public final class DiagramImages {

    /** A finished render: a {@code loaded} image (success) or an {@code error} message (failure). */
    private record Cached(PreviewImageLoader.Loaded loaded, String error, int extra, long at) {
        Cached(PreviewImageLoader.Loaded loaded, String error) {
            this(loaded, error, 0, System.currentTimeMillis());
        }

        Cached(PreviewImageLoader.Loaded loaded, String error, int extra) {
            this(loaded, error, extra, System.currentTimeMillis());
        }

        /**
         * A failure is only worth reusing briefly. The cache key is the source (+ theme) — not the tool — so
         * a diagram that failed because the CLI was missing stayed "broken" after installing it: the install
         * flow re-renders every preview, which is a cache hit on the same source. Successes never expire
         * (same source, same picture).
         */
        boolean expired() {
            return error != null && System.currentTimeMillis() - at > FAILURE_TTL_MS;
        }
    }

    /** How long a failed render is reused before being retried — mirrors {@link PreviewImageLoader}. */
    private static final long FAILURE_TTL_MS = 60_000;

    /** Cap on cached rendered diagrams (each successful entry pins a GPU texture — bounded LRU). */
    private static final int MAX_CACHED = 48;

    private static final Map<String, Cached> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Cached>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            });
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "diagram-render");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Long> LATEST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    private static volatile boolean enabled;
    private static volatile Map<DiagramKind, List<String>> commands = new EnumMap<>(DiagramKind.class);
    private static volatile boolean dark;

    private DiagramImages() {}

    /** Pushes the live diagram config (called at startup + every settings/theme apply). Commands per kind
     *  are tokenized (a bare binary or a multi-word invocation). */
    public static void configure(boolean enabled, Map<DiagramKind, List<String>> commandsByKind, boolean dark) {
        boolean toolChanged = DiagramImages.enabled != enabled;
        DiagramImages.enabled = enabled;
        if (commandsByKind != null && !commandsByKind.isEmpty()) {
            toolChanged |= !commandsByKind.equals(DiagramImages.commands);
            DiagramImages.commands = new EnumMap<>(commandsByKind);
        }
        DiagramImages.dark = dark;
        if (toolChanged) {
            // The key is the source (+ theme), never the tool — so a newly installed or re-pointed dot/
            // plantuml would re-serve the cached "render failed" from when it was missing (the install flow
            // re-renders every preview, which is a hit on the same source).
            CACHE.clear();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a node for the {@code kind} diagram {@code source}, filled asynchronously: a centered
     * {@link ImageView} on success, or a {@code .diagram-error} label with the tool's message on failure.
     * Cache hits apply immediately. {@code sizer} maps the diagram's logical width to the displayed fit
     * width (a standalone preview multiplies by the zoom factor, so zoom actually resizes the image).
     */
    public static Node node(DiagramKind kind, String source, java.util.function.DoubleUnaryOperator sizer) {
        return node(kind, source, sizer, null);
    }

    /** As {@link #node(DiagramKind, String, java.util.function.DoubleUnaryOperator)}, but coalesces queued
     *  live-preview renders for a non-null stable surface key. */
    public static Node node(
            DiagramKind kind, String source, java.util.function.DoubleUnaryOperator sizer, String surfaceKey) {
        StackPane host = new StackPane();
        host.getStyleClass().add("md-diagram");
        fill(host, kind, source, sizer, surfaceKey);
        return host;
    }

    private static void fill(
            StackPane host,
            DiagramKind kind,
            String source,
            java.util.function.DoubleUnaryOperator sizer,
            String surfaceKey) {
        boolean useDark = dark && kind.themeSensitive();
        String key = key(kind, source, useDark);
        Cached hit = CACHE.get(key);
        if (hit != null && hit.expired()) {
            CACHE.remove(key);
            hit = null;
        }
        if (hit != null) {
            applyCached(host, hit, sizer);
            return;
        }
        host.getChildren().setAll(placeholder(Messages.tr("diagram.rendering")));
        List<String> cmd = commands.getOrDefault(kind, List.of(kind.defaultCommand()));
        long gen = surfaceKey == null ? -1 : SEQ.incrementAndGet();
        if (surfaceKey != null) {
            LATEST.put(surfaceKey, gen);
        }
        EXEC.submit(() -> {
            if (surfaceKey != null && superseded(surfaceKey, gen, LATEST.get(surfaceKey))) {
                return;
            }
            DiagramRenderer.Render r = DiagramRenderer.renderPng(kind, cmd, source, useDark);
            if (surfaceKey != null && superseded(surfaceKey, gen, LATEST.get(surfaceKey))) {
                return;
            }
            Cached result;
            if (r.ok()) {
                javafx.scene.image.Image img =
                        new javafx.scene.image.Image(new java.io.ByteArrayInputStream(r.image()));
                if (img.isError() || img.getWidth() <= 0) {
                    result = new Cached(null, Messages.tr("diagram.renderFailed"));
                } else {
                    result = new Cached(new PreviewImageLoader.Loaded(img, img.getWidth()), null, r.extra());
                }
            } else {
                result = new Cached(null, r.error());
            }
            CACHE.put(key, result);
            synchronized (CACHE) {
                ImageCacheBudget.trim(
                        CACHE,
                        c -> c.loaded() == null
                                ? 0
                                : ImageCacheBudget.footprint(c.loaded().image()),
                        ImageCacheBudget.DIAGRAM_BUDGET_BYTES);
            }
            Platform.runLater(() -> applyCached(host, result, sizer));
            if (surfaceKey != null) {
                LATEST.remove(surfaceKey, gen);
            }
        });
    }

    static boolean superseded(String surfaceKey, long gen, Long latest) {
        return surfaceKey != null && latest != null && latest.longValue() != gen;
    }

    private static void applyCached(StackPane host, Cached c, java.util.function.DoubleUnaryOperator sizer) {
        if (c.loaded() != null) {
            ImageView view = new ImageView(c.loaded().image());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(Math.max(1, sizer.applyAsDouble(c.loaded().logicalWidth())));
            if (c.extra() > 0) {
                // The tool wrote more than one diagram (a multi-@startuml PlantUML file); show the first + a
                // note so the rest aren't silently dropped (#459).
                javafx.scene.control.Label note = new javafx.scene.control.Label(
                        Messages.tr("diagram.moreDiagrams", Integer.toString(c.extra())));
                note.getStyleClass().add("diagram-extra-note");
                javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(6, view, note);
                box.setAlignment(javafx.geometry.Pos.CENTER);
                host.getChildren().setAll(box);
            } else {
                host.getChildren().setAll(view);
            }
        } else {
            host.getChildren().setAll(errorNode(c.error()));
        }
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("diagram-placeholder");
        return label;
    }

    private static Label errorNode(String message) {
        String body = message == null || message.isBlank()
                ? Messages.tr("diagram.renderFailed")
                : Messages.tr("diagram.renderFailed") + "\n" + message.strip();
        Label label = new Label(body);
        label.getStyleClass().add("diagram-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /** Cache key = kind + sha-256 of (theme bit +) source, so editing invalidates but a re-render hits. */
    private static String key(DiagramKind kind, String source, boolean dark) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) kind.ordinal());
            md.update(dark ? (byte) 1 : (byte) 0);
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return kind.name() + ':' + HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return kind.name() + ':' + (dark ? "d" : "l") + source.length() + ':' + source.hashCode();
        }
    }
}
