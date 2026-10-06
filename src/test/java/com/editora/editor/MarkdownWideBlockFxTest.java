package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One wide block (a diagram, an image, a display formula) must not stop the Markdown preview from
 * wrapping: in a narrow Split pane the column fits the viewport and only the wide block is scaled down.
 */
@Tag("fx")
class MarkdownWideBlockFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static void onFx(Runnable task) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void shrinkToFitScalesAWideImageAndLeavesANarrowOneAlone() throws Exception {
        onFx(() -> {
            ImageView wide = new ImageView(new WritableImage(900, 300));
            ShrinkToFit fit = new ShrinkToFit(wide);
            VBox column = new VBox(fit);
            new Scene(column, 450, 400);
            column.applyCss();
            column.layout();
            assertEquals(0, fit.minWidth(-1), 0.01, "a wide block no longer sets the column's minimum width");
            assertEquals(450, fit.getWidth(), 0.5);
            assertEquals(450, wide.getBoundsInParent().getWidth(), 1.0, "scaled to the available width");
            assertEquals(150, fit.getHeight(), 1.0, "and the row is only as tall as the scaled image");
            assertEquals(0.5, ShrinkToFit.factor(900, 450), 1e-9);
            assertEquals(1.0, ShrinkToFit.factor(300, 450), 1e-9, "never enlarges");

            column.resize(1200, 400);
            column.layout();
            assertEquals(900, wide.getBoundsInParent().getWidth(), 1.0, "natural size when there is room");
            assertEquals(300, fit.getHeight(), 1.0);
        });
    }

    @Test
    void aWideBlockDoesNotStopTheColumnFromWrappingInANarrowPane() throws Exception {
        boolean mathWasOn = MathImages.isEnabled();
        try {
            MathImages.configure(true, false);
            StringBuilder formula = new StringBuilder("x_0");
            for (int i = 1; i < 60; i++) {
                formula.append(" + x_{").append(i).append('}');
            }
            String md = "# Title\n\n"
                    + "A paragraph of ordinary prose that is comfortably longer than five hundred pixels and so has"
                    + " to wrap onto several lines when the preview pane is narrow, as it is in Split view.\n\n"
                    + "$$" + formula + "$$\n\n"
                    + "Another paragraph after the wide block, which must wrap to the pane as well.\n";
            onFx(() -> {
                Node rendered = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(md), null);
                ScrollPane pane = new ScrollPane(rendered);
                pane.setFitToWidth(true);
                Scene scene = new Scene(pane, 500, 600);
                pane.applyCss();
                pane.layout();
                Region wrap = (Region) rendered;
                double viewport = pane.getViewportBounds().getWidth();
                assertTrue(viewport > 300 && viewport <= 500, "viewport " + viewport);

                // The formula really is wider than the pane (otherwise this proves nothing)…
                ImageView image = (ImageView) wrap.lookup(".md-math-block");
                assertTrue(image.getLayoutBounds().getWidth() > viewport, "test formula is not wide enough");
                // …yet the content is exactly as wide as the viewport: no horizontal scrolling.
                assertTrue(wrap.getWidth() <= viewport + 0.5, "content " + wrap.getWidth() + " > viewport " + viewport);
                assertTrue(wrap.minWidth(-1) <= viewport, "min width " + wrap.minWidth(-1));
                // Prose wraps inside the pane, and the formula is scaled into it.
                for (Node n : wrap.lookupAll(".md-paragraph")) {
                    TextFlow paragraph = (TextFlow) n;
                    double right =
                            paragraph.localToScene(paragraph.getBoundsInLocal()).getMaxX();
                    assertTrue(right <= scene.getWidth() + 0.5, "paragraph runs to " + right);
                }
                StackPane formulaWrap = (StackPane) wrap.lookup(".md-math-block-wrap");
                assertTrue(
                        formulaWrap.localToScene(formulaWrap.getBoundsInLocal()).getMaxX() <= scene.getWidth() + 0.5,
                        "the formula is scaled into the pane");
            });
        } finally {
            MathImages.configure(mathWasOn, false);
        }
    }
}
