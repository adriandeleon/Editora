package com.editora.editor;

import java.util.List;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Region;
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
public final class CanvasGuards {

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

    public static double clampWidth(Node owner, double width) {
        return clampDim(width, outputScale(owner, true));
    }

    public static double clampHeight(Node owner, double height) {
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
    public static boolean paintable(double w, double h) {
        return Double.isFinite(w) && Double.isFinite(h) && w >= 1 && h >= 1 && w <= MAX_DIM && h <= MAX_DIM;
    }

    /** Sizes {@code canvas} to {@code owner}'s current (clamped) size; a no-op when it already matches. */
    public static void fit(Region owner, Canvas canvas) {
        double w = clampWidth(owner, owner.getWidth());
        double h = clampHeight(owner, owner.getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
    }

    /**
     * Clears {@code canvas} and shrinks it to 1x1, for an overlay that has nothing to draw for now. This is cheap
     * and can flap with scrolling or typing, but it does <em>not</em> free the backing texture: JavaFX's
     * {@code NGCanvas} keeps the largest texture it has rendered until the canvas itself is gone. An overlay that
     * is switched off or whose tab goes to the background calls {@link #released} instead.
     */
    public static void shrink(Canvas canvas) {
        if (canvas.getWidth() != 1 || canvas.getHeight() != 1) {
            canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.setWidth(1);
            canvas.setHeight(1);
        }
    }

    /**
     * Frees {@code canvas}'s viewport-sized backing texture, and returns the canvas the owner keeps from now on.
     * Every overlay does this when it is switched off or its tab goes to the background.
     *
     * <p>Shrinking a canvas does not free its texture: {@code NGCanvas} only ever grows the texture it holds, and
     * only when the canvas is rendered (a background tab never is). The texture goes when the canvas does, so the
     * canvas is replaced in {@code children}, at its place, by a fresh 1x1 one with the same position, visibility
     * and mouse transparency, and the old one is collected with its texture. A fresh canvas this returned that has
     * not been made larger since is returned as it is. The owner holds its canvas in a field it reassigns from the
     * result, and re-adds anything it put on the canvas itself (event handlers).
     *
     * @param children the owner's children, {@code canvas} among them
     */
    public static Canvas released(Canvas canvas, List<Node> children) {
        if (isPlaceholder(canvas)) {
            return canvas;
        }
        Canvas fresh = placeholder();
        fresh.setLayoutX(canvas.getLayoutX());
        fresh.setLayoutY(canvas.getLayoutY());
        fresh.setVisible(canvas.isVisible());
        fresh.setMouseTransparent(canvas.isMouseTransparent());
        fresh.setManaged(canvas.isManaged());
        int at = children.indexOf(canvas);
        if (at >= 0) {
            children.set(at, fresh);
        }
        return fresh;
    }

    /** As {@link #released(Canvas, List)}, for a canvas that is not in the scene graph (one only snapshotted). */
    public static Canvas released(Canvas canvas) {
        return isPlaceholder(canvas) ? canvas : placeholder();
    }

    /** Marks a canvas {@link #released} made, until it is first made larger than 1x1. */
    private static final Object PLACEHOLDER = new Object();

    /** A 1x1 canvas that has held no texture larger than that (the mark goes as soon as it grows). */
    static boolean isPlaceholder(Canvas canvas) {
        return canvas.hasProperties() && canvas.getProperties().containsKey(PLACEHOLDER);
    }

    private static Canvas placeholder() {
        Canvas fresh = new Canvas(1, 1);
        fresh.getProperties().put(PLACEHOLDER, Boolean.TRUE);
        InvalidationListener grown = new InvalidationListener() {
            @Override
            public void invalidated(Observable o) {
                if (fresh.getWidth() != 1 || fresh.getHeight() != 1) {
                    fresh.getProperties().remove(PLACEHOLDER);
                    fresh.widthProperty().removeListener(this);
                    fresh.heightProperty().removeListener(this);
                }
            }
        };
        fresh.widthProperty().addListener(grown);
        fresh.heightProperty().addListener(grown);
        return fresh;
    }
}
