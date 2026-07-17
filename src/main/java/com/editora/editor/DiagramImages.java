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
    private record Cached(PreviewImageLoader.Loaded loaded, String error, long at) {
        Cached(PreviewImageLoader.Loaded loaded, String error) {
            this(loaded, error, System.currentTimeMillis());
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
        StackPane host = new StackPane();
        host.getStyleClass().add("md-diagram");
        fill(host, kind, source, sizer);
        return host;
    }

    private static void fill(
            StackPane host, DiagramKind kind, String source, java.util.function.DoubleUnaryOperator sizer) {
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
        EXEC.submit(() -> {
            DiagramRenderer.Render r = DiagramRenderer.renderPng(kind, cmd, source, useDark);
            Cached result;
            if (r.ok()) {
                javafx.scene.image.Image img =
                        new javafx.scene.image.Image(new java.io.ByteArrayInputStream(r.image()));
                if (img.isError() || img.getWidth() <= 0) {
                    result = new Cached(null, Messages.tr("diagram.renderFailed"));
                } else {
                    result = new Cached(new PreviewImageLoader.Loaded(img, img.getWidth()), null);
                }
            } else {
                result = new Cached(null, r.error());
            }
            CACHE.put(key, result);
            Platform.runLater(() -> applyCached(host, result, sizer));
        });
    }

    private static void applyCached(StackPane host, Cached c, java.util.function.DoubleUnaryOperator sizer) {
        if (c.loaded() != null) {
            ImageView view = new ImageView(c.loaded().image());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(Math.max(1, sizer.applyAsDouble(c.loaded().logicalWidth())));
            host.getChildren().setAll(view);
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
