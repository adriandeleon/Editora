package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

import com.editora.i18n.Messages;
import com.editora.mermaid.Mermaid;

/**
 * Renders Mermaid diagrams to JavaFX nodes for the preview, asynchronously. Mirrors
 * {@link PreviewImageLoader}: a static façade with a daemon executor and a result cache, so the
 * synchronous {@code MarkdownRenderer}/{@code EditorBuffer} preview build can drop a placeholder node
 * and have it filled later off the FX thread. The cache is keyed by a hash of the diagram source +
 * theme, so the debounced whole-document re-render does <b>not</b> re-invoke mmdc for unchanged
 * diagrams. Configured (enabled flag, mmdc path, theme) from {@code MainController.applyMermaidSupport}
 * via {@link #configure} — the editor package can't depend on {@code ui}, like the snippet/completion
 * provider injection.
 */
public final class MermaidImages {

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

    /** Cap on cached rendered diagrams. Each successful entry holds a GPU texture, so the cache is
     *  bounded (LRU) rather than growing unbounded as more diagrams are rendered. */
    private static final int MAX_CACHED = 48;

    private static final Map<String, Cached> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Cached>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Cached> eldest) {
                    return size() > MAX_CACHED;
                }
            });
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mermaid-render");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean enabled;
    private static volatile List<String> mmdc = List.of("mmdc");
    private static volatile List<String> maid = List.of("maid");
    private static volatile boolean dark;
    /** Whether maid was detected — a failed render only asks it for diagnostics when it is actually there. */
    private static volatile boolean maidAvailable;

    private MermaidImages() {}

    /** Pushes the live Mermaid config (called at startup + every settings/theme apply). The commands are
     *  tokenized (a bare binary or e.g. {@code npx -y @probelabs/maid}). */
    public static void configure(boolean enabled, List<String> mmdcCommand, List<String> maidCommand, boolean dark) {
        boolean toolChanged = MermaidImages.enabled != enabled;
        MermaidImages.enabled = enabled;
        if (mmdcCommand != null && !mmdcCommand.isEmpty()) {
            toolChanged |= !mmdcCommand.equals(MermaidImages.mmdc);
            MermaidImages.mmdc = List.copyOf(mmdcCommand);
        }
        if (maidCommand != null && !maidCommand.isEmpty()) {
            MermaidImages.maid = List.copyOf(maidCommand);
        }
        MermaidImages.dark = dark;
        if (toolChanged) {
            // The cache key is the source (+ theme), never the tool — so a different (or newly installed)
            // mmdc would otherwise re-serve the old result. That is exactly the install flow: it re-renders
            // every open preview, which hit the cached "render failed" from when mmdc was missing, and the
            // diagram stayed broken until the user edited it or restarted.
            CACHE.clear();
        }
    }

    /** Pushed once maid detection settles (see {@code MermaidCoordinator.gating}): a failed render only asks
     *  maid for diagnostics when it is actually installed. */
    public static void setMaidAvailable(boolean available) {
        MermaidImages.maidAvailable = available;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a node for the diagram {@code source}, filled asynchronously: a centered {@link ImageView}
     * on success, or a {@code .mermaid-error} label with mmdc's message on failure. Cache hits apply
     * immediately. {@code sizer} maps the diagram's logical width to the displayed fit width — embedded
     * Markdown blocks clamp to the content column, while a standalone {@code .mmd} preview multiplies by
     * the zoom factor (so zoom actually resizes the image).
     */
    public static Node node(String source, java.util.function.DoubleUnaryOperator sizer) {
        StackPane host = new StackPane();
        host.getStyleClass().add("md-mermaid");
        fill(host, source, sizer);
        return host;
    }

    private static void fill(StackPane host, String source, java.util.function.DoubleUnaryOperator sizer) {
        String key = key(source, dark);
        Cached hit = CACHE.get(key);
        if (hit != null && hit.expired()) {
            CACHE.remove(key);
            hit = null;
        }
        if (hit != null) {
            applyCached(host, hit, sizer);
            return;
        }
        host.getChildren().setAll(placeholder(Messages.tr("mermaid.rendering")));
        List<String> exe = mmdc;
        List<String> maidExe = maid;
        boolean useMaid = maidAvailable;
        boolean useDark = dark;
        EXEC.submit(() -> {
            Mermaid.Render r = Mermaid.renderPng(exe, source, useDark);
            Cached result;
            if (r.ok()) {
                javafx.scene.image.Image img =
                        new javafx.scene.image.Image(new java.io.ByteArrayInputStream(r.image()));
                if (img.isError() || img.getWidth() <= 0) {
                    result = new Cached(null, Messages.tr("mermaid.renderFailed"));
                } else {
                    // PNG is rendered at RENDER_SCALE×; display at logical (CSS-pixel) width.
                    result =
                            new Cached(new PreviewImageLoader.Loaded(img, img.getWidth() / Mermaid.RENDER_SCALE), null);
                }
            } else if (useMaid) {
                // On a render failure, prefer maid's precise line/column diagnostics over mmdc's raw error —
                // but only when maid is actually installed. Unconditionally, every failed render spawned the
                // default `npx -y @probelabs/maid` (~6.5 s, and `-y` may hit the npm registry): a Markdown
                // file with five fences and no mmdc spent ~30 s in npx to learn nothing.
                result = new Cached(null, diagnose(maidExe, source, r.error()));
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

    /**
     * Builds the error message for a failed render: maid's line/column diagnostics when available
     * (precise + fast), else mmdc's raw message.
     */
    private static String diagnose(List<String> maidExe, String source, String mmdcError) {
        var diags = com.editora.mermaid.Mermaid.validate(maidExe, source);
        if (diags.isEmpty()) {
            return mmdcError;
        }
        StringBuilder sb = new StringBuilder();
        for (var d : diags) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String code = d.code() == null || d.code().isBlank() ? "" : " [" + d.code() + "]";
            sb.append(Messages.tr("mermaid.diagnosticLine", d.line(), d.column(), code + " " + d.message()));
        }
        return sb.toString();
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mermaid-placeholder");
        return label;
    }

    private static Label errorNode(String message) {
        String base = message == null || message.isBlank()
                ? Messages.tr("mermaid.renderFailed")
                : Messages.tr("mermaid.renderFailed") + "\n" + message.strip();
        // mmdc fails with a long Puppeteer stack when its headless Chrome isn't installed — lead with the
        // one-line fix so the user isn't left to parse it (the common Linux "Could not find Chrome" case).
        String body = looksLikeChromeMissing(message) ? Messages.tr("mermaid.chromeMissing") + "\n\n" + base : base;
        Label label = new Label(body);
        label.getStyleClass().add("mermaid-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /** Whether {@code message} is mmdc's "headless Chrome / Puppeteer not installed" failure. Pure. */
    static boolean looksLikeChromeMissing(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("could not find chrome") || m.contains("chrome-headless-shell") || m.contains("puppeteer");
    }

    /** Cache key = sha-256 of theme + source (so editing invalidates, but re-renders of the same text hit). */
    private static String key(String source, boolean dark) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(dark ? (byte) 1 : (byte) 0);
            md.update(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return (dark ? "d" : "l") + source.length() + ":" + source.hashCode();
        }
    }
}
