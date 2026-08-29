package com.editora.ui;

import java.util.concurrent.TimeUnit;
import java.util.function.DoubleUnaryOperator;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.SvgImages;
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
        for (int i = 0; i < 100 && FxTestSupport.callOnFx(() -> canvasIn(host)) == null; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
            FxTestSupport.runOnFx(() -> {});
        }
        FxTestSupport.runOnFx(root::applyCss);
        FxTestSupport.runOnFx(root::layout);
        return host;
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
        for (int i = 0; i < 100; i++) {
            TimeUnit.MILLISECONDS.sleep(20);
            FxTestSupport.runOnFx(() -> {});
            Boolean errored = FxTestSupport.callOnFx(() -> ((StackPane) host)
                    .getChildren().stream().anyMatch(n -> n.getStyleClass().contains("svg-error")));
            if (errored) {
                return;
            }
        }
        assertInstanceOf(StackPane.class, host);
        throw new AssertionError("expected an .svg-error label for unparseable input");
    }
}
