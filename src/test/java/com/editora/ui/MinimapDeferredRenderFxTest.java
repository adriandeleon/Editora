package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The minimap's first content render is deferred past the editor's first paint — but it must still happen.
 *
 * <p>Rendering the minimap forces two synchronous layout passes ({@code canvas.snapshot()} and, via
 * {@code drawViewport}, {@code firstVisibleParToAllParIndex()}), and at startup its triggers arrive as a
 * burst, so it was the largest single piece of app code running before the user's text appeared. Renders now
 * coalesce to one per pulse with the first held two animation frames.
 *
 * <p>That trade is only acceptable if the render still lands: a mistake in the deferral would leave the
 * minimap permanently blank, which no existing test would catch (the others assert its
 * <em>visibility</em>, which is a layout property and stays true for an unpainted canvas). So this asserts
 * the cached snapshot — the thing a render produces — goes from absent to present on its own.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinimapDeferredRenderFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theFirstMinimapRenderIsDeferredYetStillProducesItsCachedImage() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("public void method").append(i).append("() { return; }\n");
        }

        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(text.toString());
            // A real, sized, showing scene: the render bails out early on an unpaintable canvas, so without
            // one this would assert nothing.
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(b.getNode()), 900, 600));
            stage.show();
            return b;
        });

        try {
            // Nothing rendered synchronously: the burst of triggers from setContent/theme/tab-size is
            // coalesced and held, which is the whole point.
            assertNull(cachedImage(buffer), "the minimap must not render during the content set itself");

            assertNotNull(
                    waitForCachedImage(buffer),
                    "the deferred first render never ran — the minimap would stay blank forever");
        } finally {
            FxTestSupport.runOnFx(() -> {
                Stage s = (Stage) buffer.getNode().getScene().getWindow();
                s.close();
            });
        }
    }

    /** The minimap's cached snapshot, which only a completed content render produces. */
    private static WritableImage cachedImage(EditorBuffer buffer) throws Exception {
        Object minimap = FxTestSupport.field(buffer, "minimap");
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "contentImage"));
    }

    /** Polls for the cached snapshot to appear, for up to ~5 s. */
    private static WritableImage waitForCachedImage(EditorBuffer buffer) throws Exception {
        for (int i = 0; i < 250; i++) {
            WritableImage image = cachedImage(buffer);
            if (image != null) {
                return image;
            }
            Thread.sleep(20);
        }
        return null;
    }
}
