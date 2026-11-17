package com.editora.ui;

import java.time.Duration;
import java.util.function.DoubleUnaryOperator;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.editor.markdown.SvgImages;
import com.editora.testkit.Await;
import com.github.weisj.jsvg.ui.jfx.FXSVGCanvas;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standalone {@code .svg} preview renders through {@code FXSVGCanvas} rather than a rasterized image,
 * which is what makes zoom resolution-independent.
 *
 * <p>Driven through a real scene and a real layout pass on purpose. The sizing is the part that can silently
 * be wrong: {@code FXSVGCanvas} is a {@code Control} whose skin computes no preferred size, so a canvas that
 * is merely constructed and asserted on reports whatever was set, while one that is actually laid out inside
 * the centering host would collapse to zero if the sizes were not pinned.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SvgPreviewCanvasFxTest {

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='50'>"
            + "<rect width='200' height='50' fill='#4c1'/></svg>";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Builds the preview node, waits for the async fill, and lays it out in a real scene. */
    private Node laidOut(String svg, DoubleUnaryOperator sizer) throws Exception {
        Node host = FxTestSupport.callOnFx(() -> SvgImages.node(svg, sizer));
        StackPane root = new StackPane(host);
        FxTestSupport.runOnFx(() -> {
            Stage st = new Stage();
            st.setScene(new Scene(root, 800, 600));
            st.show();
        });
        Await.holdsWithin(() -> onFx(() -> canvasIn(host)) != null, Duration.ofSeconds(2));
        FxTestSupport.runOnFx(root::applyCss);
        FxTestSupport.runOnFx(root::layout);
        return host;
    }

    /** The share of sampled pixels (every second one each way) in the SVG's green fill. */
    private static double greenShare(WritableImage shot) {
        int w = (int) shot.getWidth();
        int h = (int) shot.getHeight();
        int painted = 0;
        var reader = shot.getPixelReader();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                Color c = reader.getColor(x, y);
                if (c.getOpacity() > 0.9 && c.getGreen() > 0.5 && c.getRed() < 0.5 && c.getBlue() < 0.5) {
                    painted++;
                }
            }
        }
        int sampled = ((h + 1) / 2) * ((w + 1) / 2);
        return sampled == 0 ? 0 : (double) painted / sampled;
    }

    private static FXSVGCanvas canvasIn(Node host) {
        if (host instanceof StackPane sp) {
            for (Node n : sp.getChildren()) {
                if (n instanceof FXSVGCanvas c) {
                    return c;
                }
            }
        }
        return null;
    }

    @Test
    void thePreviewIsAVectorCanvasCarryingTheParsedDocument() throws Exception {
        Node host = laidOut(SVG, w -> w);
        FXSVGCanvas canvas = FxTestSupport.callOnFx(() -> canvasIn(host));
        assertNotNull(canvas, "the preview filled with an FXSVGCanvas, not an ImageView");
        assertNotNull(FxTestSupport.callOnFx(canvas::getDocument), "the parsed document reached the canvas");
        assertNotNull(FxTestSupport.callOnFx(canvas::getViewBox), "a view box was set");
        assertTrue(
                FxTestSupport.callOnFx(canvas::getShowTransparentPattern),
                "transparency shows as a checkerboard rather than blending into the pane");
    }

    /**
     * The zoom contract: the width the caller asks for is the width the canvas actually occupies, with the
     * SVG's aspect ratio preserved. A rasterized preview could only honour this by scaling a fixed bitmap.
     */
    @Test
    void theCanvasTakesTheRequestedWidthAndKeepsTheAspectRatio() throws Exception {
        Node host = laidOut(SVG, w -> w * 4); // 200 -> 800 logical, i.e. well past the old 2x raster
        FXSVGCanvas canvas = FxTestSupport.callOnFx(() -> canvasIn(host));
        assertNotNull(canvas, "canvas present");
        double w = FxTestSupport.callOnFx(() -> canvas.getBoundsInParent().getWidth());
        double h = FxTestSupport.callOnFx(() -> canvas.getBoundsInParent().getHeight());
        assertEquals(800, w, 1.0, "laid out at the requested width");
        assertEquals(200, h, 1.0, "aspect ratio preserved (200x50 -> 800x200)");
    }

    /** A document that cannot be parsed shows the error label, not an empty pane. */
    @Test
    void unparseableSvgShowsAnError() throws Exception {
        Node host = FxTestSupport.callOnFx(() -> SvgImages.node("this is not svg at all", w -> w));
        // the message label, under a "Line N, column M" link when the parser said where (XML review C15)
        if (Await.holdsWithin(() -> onFx(() -> host.lookup(".svg-error") != null), Duration.ofSeconds(2))) {
            return;
        }
        assertInstanceOf(StackPane.class, host);
        throw new AssertionError("expected an .svg-error label for unparseable input");
    }

    /**
     * It actually draws. Every other assertion here passes for a canvas that is present, carries a document
     * and occupies the right box while rendering nothing at all — which is precisely the failure a native
     * renderer swap can produce, and it would look like an empty preview.
     *
     * <p>Snapshots the laid-out canvas and counts pixels matching the SVG's fill. Deliberately a colour
     * count rather than an image comparison: the exact rasterization is the library's business and will
     * differ across versions and platforms, whereas "did the green rectangle get painted" is the property
     * being claimed.
     */
    @Test
    void theCanvasActuallyPaintsTheDocument() throws Exception {
        Node host = laidOut(SVG, w -> w * 2); // 200x50 -> 400x100
        FXSVGCanvas canvas = FxTestSupport.callOnFx(() -> canvasIn(host));
        assertNotNull(canvas, "canvas present");
        // The skin's first paint lands on a later pulse: snapshot on each pulse until most of the box shows the
        // fill (or a while has passed), then check the last snapshot.
        WritableImage[] last = new WritableImage[1];
        Await.holdsWithin(
                () -> {
                    last[0] = onFx(() -> canvas.snapshot(new SnapshotParameters(), null));
                    return last[0] != null && greenShare(last[0]) > 0.5;
                },
                Duration.ofSeconds(2));
        WritableImage shot = last[0];
        assertNotNull(shot, "snapshot taken");
        int w = (int) shot.getWidth();
        int h = (int) shot.getHeight();
        assertTrue(w > 1 && h > 1, "snapshot has real dimensions, got " + w + "x" + h);
        int painted = 0;
        var reader = shot.getPixelReader();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                Color c = reader.getColor(x, y);
                // The SVG fills itself #4c1 — a strongly green, opaque pixel.
                if (c.getOpacity() > 0.9 && c.getGreen() > 0.5 && c.getRed() < 0.5 && c.getBlue() < 0.5) {
                    painted++;
                }
            }
        }
        int sampled = ((h + 1) / 2) * ((w + 1) / 2);
        assertTrue(
                painted > sampled / 2,
                "expected the canvas to paint the SVG's green fill over most of its box, but only " + painted
                        + " of " + sampled + " sampled pixels matched — a canvas that lays out correctly and"
                        + " draws nothing passes every other assertion in this class");
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
