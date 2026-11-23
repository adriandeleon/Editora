package com.editora.editor;

import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CanvasGuards#released}: the canvas, and so its texture, is replaced; a fresh placeholder is kept. */
@Tag("fx")
class CanvasGuardsReleaseFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void aGrownCanvasIsReplacedInPlaceAndAFreshOneIsKept() throws Exception {
        EditorFx.onFx(() -> {
            Rectangle before = new Rectangle(1, 1);
            Rectangle after = new Rectangle(1, 1);
            Canvas canvas = new Canvas(400, 300);
            canvas.relocate(3, 4);
            canvas.setMouseTransparent(true);
            Pane owner = new Pane(before, canvas, after);

            Canvas fresh = CanvasGuards.released(canvas, owner.getChildren());
            assertNotSame(canvas, fresh, "a canvas that held a texture is dropped");
            assertSame(fresh, owner.getChildren().get(1), "the fresh one takes its place");
            assertEquals(3, owner.getChildren().size());
            assertEquals(1, fresh.getWidth());
            assertEquals(1, fresh.getHeight());
            assertEquals(3, fresh.getLayoutX());
            assertEquals(4, fresh.getLayoutY());
            assertTrue(fresh.isMouseTransparent());
            assertSame(fresh, CanvasGuards.released(fresh, owner.getChildren()), "a fresh 1x1 canvas is kept");

            // Grown and shrunk again, it may have rendered at the larger size: released replaces it.
            fresh.setWidth(500);
            fresh.setHeight(200);
            CanvasGuards.shrink(fresh);
            assertEquals(1, fresh.getWidth());
            assertNotSame(fresh, CanvasGuards.released(fresh, owner.getChildren()), "shrinking keeps the texture");

            Canvas offscreen = new Canvas(90, 800);
            Canvas freshOffscreen = CanvasGuards.released(offscreen);
            assertNotSame(offscreen, freshOffscreen, "a canvas outside the scene graph is replaced too");
            assertSame(freshOffscreen, CanvasGuards.released(freshOffscreen));
        });
    }
}
