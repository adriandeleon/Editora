package com.editora.editor;

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
 * <p>{@link #clampDim} keeps the canvas size finite and in {@code [1, MAX_DIM]} so the texture is
 * always allocatable; {@link #paintable} reports whether the overlay's <em>region</em> currently has a
 * real size, so callers skip drawing content into a collapsed (or absurdly large) surface. Both are
 * pure, so they are unit-tested without a toolkit.
 */
final class CanvasGuards {

    /** Upper bound for a canvas dimension — the common GPU maximum texture size. */
    static final double MAX_DIM = 16384;

    private CanvasGuards() {}

    /** Clamps a requested canvas dimension to a finite value in {@code [1, MAX_DIM]} ({@code NaN}/≤0 → 1). */
    static double clampDim(double v) {
        if (Double.isNaN(v) || v < 1) {
            return 1;
        }
        return Math.min(v, MAX_DIM);
    }

    /**
     * Whether a region's current size is real and in range — i.e. worth painting into. Collapsed
     * ({@code ≤0}), {@code NaN}/infinite, or over-{@link #MAX_DIM} sizes return {@code false}.
     */
    static boolean paintable(double w, double h) {
        return Double.isFinite(w) && Double.isFinite(h) && w >= 1 && h >= 1 && w <= MAX_DIM && h <= MAX_DIM;
    }
}
