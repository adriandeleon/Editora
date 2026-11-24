package com.editora.editor.logviewer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogSources;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The level overlay painted on screen: a merged log's lines carry their source's strip. */
@Tag("fx")
class LogHighlightOverlayFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static <T> T onFx(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aMergedLogsLinesCarryTheirSourcesStrip() throws Exception {
        Stage stage = FxToolkit.registerPrimaryStage();
        LogHighlightOverlay overlay = onFx(() -> {
            CodeArea area = new CodeArea("2026-11-24 10:00:00 INFO  api up\n2026-11-24 10:00:01 ERROR db down\n");
            LogHighlightOverlay o = new LogHighlightOverlay(area, new LogHighlightOverlay.LevelSource() {
                @Override
                public boolean filtered() {
                    return false;
                }

                @Override
                public LogLevel levelAt(int paragraph) {
                    return null;
                }
            });
            o.setMouseTransparent(true);
            stage.setScene(new Scene(new StackPane(area, o), 600, 200));
            stage.show();
            o.setRenderingActive(true);
            o.setActive(true);
            o.setSources(new LogSources(List.of("api.log", "db.log"), new int[] {0, 1, 0}));
            return o;
        });
        for (int i = 0; i < 5; i++) {
            onFx(() -> {
                overlay.applyCss();
                overlay.layout();
                return null;
            }); // a few pulses: layout, then the coalesced repaint after it
        }
        Color strip = onFx(() -> {
            WritableImage image = overlay.snapshot(new SnapshotParameters(), null);
            return image.getPixelReader().getColor((int) image.getWidth() - 2, 8);
        });
        Color expected = LogHighlightOverlay.sourceColor(0);
        assertEquals(expected.getRed(), strip.getRed(), 0.08, "the first line's strip is api's colour: " + strip);
        assertEquals(expected.getBlue(), strip.getBlue(), 0.08);
        assertTrue(!LogHighlightOverlay.sourceColor(0).equals(LogHighlightOverlay.sourceColor(1)));

        LogHighlightOverlay twin = onFx(() -> overlay.follower(new CodeArea()));
        onFx(() -> {
            overlay.setSources(null); // and the twin's go with them
            stage.hide();
            return null;
        });
        assertTrue(twin != null);
    }
}
