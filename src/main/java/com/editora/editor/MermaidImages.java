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
    private record Cached(PreviewImageLoader.Loaded loaded, String error) { }

    /** Cap on cached rendered diagrams. Each successful entry holds a GPU texture, so the cache is
     *  bounded (LRU) rather than growing unbounded as more diagrams are rendered. */
    private static final int MAX_CACHED = 48;
    private static final Map<String, Cached> CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Cached>(32, 0.75f, true) {
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

    private MermaidImages() {
    }

    /** Pushes the live Mermaid config (called at startup + every settings/theme apply). The commands are
     *  tokenized (a bare binary or e.g. {@code npx -y @probelabs/maid}). */
    public static void configure(boolean enabled, List<String> mmdcCommand, List<String> maidCommand, boolean dark) {
        MermaidImages.enabled = enabled;
        if (mmdcCommand != null && !mmdcCommand.isEmpty()) {
            MermaidImages.mmdc = List.copyOf(mmdcCommand);
        }
        if (maidCommand != null && !maidCommand.isEmpty()) {
            MermaidImages.maid = List.copyOf(maidCommand);
        }
        MermaidImages.dark = dark;
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
        if (hit != null) {
            applyCached(host, hit, sizer);
            return;
        }
        host.getChildren().setAll(placeholder(Messages.tr("mermaid.rendering")));
        List<String> exe = mmdc;
        List<String> maidExe = maid;
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
                    result = new Cached(
                            new PreviewImageLoader.Loaded(img, img.getWidth() / Mermaid.RENDER_SCALE), null);
                }
            } else {
                // On a render failure, prefer maid's precise line/column diagnostics over mmdc's raw error.
                result = new Cached(null, diagnose(maidExe, source, r.error()));
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
        String body = message == null || message.isBlank() ? Messages.tr("mermaid.renderFailed")
                : Messages.tr("mermaid.renderFailed") + "\n" + message.strip();
        Label label = new Label(body);
        label.getStyleClass().add("mermaid-error");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
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
