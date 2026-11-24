package com.editora.editor.minimap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import com.editora.perf.AfterFirstPaint;
import com.editora.testkit.Await;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup R3: during a launch the window paints frames before the file's text is in, so the minimap's first render
 * (a snapshot the FX thread waits for) counts its two frames from the launch's first painted content, held by
 * {@link AfterFirstPaint}, instead of landing just before that frame. Outside a launch nothing is held and the
 * render comes as before.
 */
@Tag("fx")
class MinimapFirstRenderAfterFirstPaintFxTest {

    private Stage stage;

    private static void onFx(Runnable r) throws Exception {
        var err = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out on the FX thread");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
    }

    /** Waits until {@code frames} pulses have run on the FX thread. */
    private static void awaitFrames(int frames) throws Exception {
        var latch = new CountDownLatch(frames);
        onFx(() -> new AnimationTimer() {
            @Override
            public void handle(long now) {
                latch.countDown();
                if (latch.getCount() == 0) {
                    stop();
                }
            }
        }.start());
        assertTrue(latch.await(30, TimeUnit.SECONDS), "the pulses ran");
    }

    @AfterEach
    void close() throws Exception {
        AfterFirstPaint.release();
        onFx(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    private void showMinimap() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append("line ").append(i).append('\n');
        }
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(text.toString());
            Minimap minimap = new Minimap(area);
            var scroll = new VirtualizedScrollPane<>(area);
            HBox.setHgrow(scroll, Priority.ALWAYS);
            stage = new Stage();
            stage.setScene(new Scene(new HBox(scroll, minimap), 800, 500));
            stage.show();
        });
    }

    @Test
    void duringALaunchTheFirstRenderWaitsForTheFirstPaintedContent() throws Exception {
        FxToolkit.registerPrimaryStage();
        AfterFirstPaint.hold();
        int before = Minimap.RENDERS_FOR_TEST.get();
        showMinimap();
        awaitFrames(10);
        assertEquals(before, Minimap.RENDERS_FOR_TEST.get(), "no render while the launch's first paint is pending");

        onFx(AfterFirstPaint::release);
        Await.until(
                com.editora.editor.EditorFx.check(() -> Minimap.RENDERS_FOR_TEST.get() != before),
                "the first render once the content has painted");
    }

    @Test
    void outsideALaunchTheFirstRenderComesWithoutWaiting() throws Exception {
        FxToolkit.registerPrimaryStage();
        AfterFirstPaint.release();
        int before = Minimap.RENDERS_FOR_TEST.get();
        showMinimap();
        Await.until(
                com.editora.editor.EditorFx.check(() -> Minimap.RENDERS_FOR_TEST.get() != before),
                "the first render, two frames after the editor painted");
    }
}
