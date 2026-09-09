package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * Pure guards that keep an editor overlay's {@link javafx.scene.canvas.Canvas} backing texture valid.
 *
 * <p>JavaFX allocates a Prism {@code RTTexture} sized to a Canvas's width/height. A {@code 0},
 * negative, {@code NaN}, or larger-than-the-GPU-limit dimension makes that allocation return
 * {@code null}, and the render thread then NPEs deep inside {@code NGCanvas.RenderBuf.validate}
 * ({@code RTTexture.createGraphics()} on a null texture) — which stalls the whole render pipeline and
 * looks like a UI freeze. This happens transiently when the editor surface is collapsed to zero width
 * (e.g. a Markdown buffer opening straight into Preview/Split mode) while an overlay still has a
 * buffered draw command waiting to be force-rendered.
 *
 * <p>A Canvas dimension is expressed in logical pixels, but Prism allocates the backing texture in
 * output pixels. On a Retina window, for example, a 13,324-pixel-wide Canvas requests a roughly
 * 26,648-pixel texture. The logical size must therefore be constrained using the Window output and
 * render scales rather than compared directly with the GPU limit.
 *
 * <p>{@link #clampWidth} and {@link #clampHeight} keep the output-pixel texture dimensions below
 * the common GPU maximum. {@link #paintable} reports whether the overlay's <em>region</em> currently
 * has a real size, so callers skip drawing content into a collapsed (or absurdly large) surface.
 */
final class CanvasGuards {

    /** Common maximum texture dimension, in physical output pixels. */
    static final double MAX_TEXTURE_DIM = 16384;

    /** Safe one-to-one Canvas dimension, leaving a pixel of headroom for renderer rounding. */
    static final double MAX_DIM = MAX_TEXTURE_DIM - 1;

    private CanvasGuards() {}

    /** Clamps a requested canvas dimension to a finite value in {@code [1, MAX_DIM]} ({@code NaN}/≤0 → 1). */
    static double clampDim(double v) {
        return clampDim(v, 1);
    }

    /** Clamps a logical Canvas dimension so its scaled backing texture remains allocatable. */
    static double clampDim(double v, double outputScale) {
        if (Double.isNaN(v) || v < 1) {
            return 1;
        }
        double scale = Double.isFinite(outputScale) && outputScale > 1 ? outputScale : 1;
        double maxLogicalDimension = Math.max(1, Math.floor(MAX_DIM / scale));
        return Math.min(v, maxLogicalDimension);
    }

    static double clampWidth(Node owner, double width) {
        return clampDim(width, outputScale(owner, true));
    }

    static double clampHeight(Node owner, double height) {
        return clampDim(height, outputScale(owner, false));
    }

    private static double outputScale(Node owner, boolean horizontal) {
        Scene scene = owner == null ? null : owner.getScene();
        Window window = scene == null ? null : scene.getWindow();
        if (window == null) {
            return 1;
        }
        if (horizontal) {
            return Math.max(window.getOutputScaleX(), window.getRenderScaleX());
        }
        return Math.max(window.getOutputScaleY(), window.getRenderScaleY());
    }

    /**
     * Whether a region's current size is real and in range — i.e. worth painting into. Collapsed
     * ({@code ≤0}), {@code NaN}/infinite, or over-{@link #MAX_DIM} sizes return {@code false}.
     */
    static boolean paintable(double w, double h) {
        return Double.isFinite(w) && Double.isFinite(h) && w >= 1 && h >= 1 && w <= MAX_DIM && h <= MAX_DIM;
    }
}
