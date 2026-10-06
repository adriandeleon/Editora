package com.editora.editor;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The Canvas overlays re-resolve their colours when the editor theme repaints the editor background. */
@Tag("fx")
class OverlayThemeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static OverlayPalette.Colors colorsOf(Object overlay) throws Exception {
        Field f = overlay.getClass().getDeclaredField("colors");
        f.setAccessible(true);
        return (OverlayPalette.Colors) f.get(overlay);
    }

    private static Background ground(String hex) {
        return new Background(new BackgroundFill(Color.web(hex), CornerRadii.EMPTY, Insets.EMPTY));
    }

    @Test
    void everyOverlayFollowsTheEditorBackground() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                CodeArea area = new CodeArea("a b\tc");
                Object[] overlays = {
                    new WhitespaceOverlay(area),
                    new LspDiagnosticOverlay(area),
                    new MarkdownLintOverlay(area),
                    new SearchHighlightOverlay(area),
                    new InlineValuesOverlay(area)
                };
                OverlayPalette.Colors light = OverlayPalette.of(Color.WHITE);
                OverlayPalette.Colors dark = OverlayPalette.of(Color.web("#171a24"));
                assertNotEquals(light, dark);
                for (Object overlay : overlays) {
                    assertEquals(light, colorsOf(overlay), overlay.getClass().getSimpleName() + " before styling");
                }
                area.setBackground(ground("#171a24")); // what the Editora Dark editor theme does through CSS
                for (Object overlay : overlays) {
                    assertEquals(dark, colorsOf(overlay), overlay.getClass().getSimpleName() + " on dark");
                }
                area.setBackground(ground("#ffffff"));
                for (Object overlay : overlays) {
                    assertEquals(light, colorsOf(overlay), overlay.getClass().getSimpleName() + " back on light");
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
