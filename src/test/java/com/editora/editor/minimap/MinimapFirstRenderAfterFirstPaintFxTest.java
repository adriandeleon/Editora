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
    private Minimap minimap;

    /** This test's own minimap's renders, read on the FX thread. */
    private int renders() throws Exception {
        int[] n = new int[1];
        onFx(() -> n[0] = minimap.rendersForTest());
        return n[0];
    }

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

    /**
     * Shows a minimap in its own window and returns that window. Each test counts only its own minimap's
     * renders: the global {@link Minimap#RENDERS_FOR_TEST} also counts a minimap another test left alive in this
     * JVM, whose first render (started before this test's hold) can land inside the frames this test waits.
     */
    private Stage showMinimap() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append("line ").append(i).append('\n');
        }
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(text.toString());
            minimap = new Minimap(area);
            var scroll = new VirtualizedScrollPane<>(area);
            HBox.setHgrow(scroll, Priority.ALWAYS);
            stage = new Stage();
            stage.setScene(new Scene(new HBox(scroll, minimap), 800, 500));
            stage.show();
        });
        return stage;
    }

    @Test
    void duringALaunchTheFirstRenderWaitsForTheFirstPaintedContent() throws Exception {
        FxToolkit.registerPrimaryStage();
        AfterFirstPaint.hold();
        showMinimap();
        awaitFrames(10);
        assertEquals(0, renders(), "no render while the launch's first paint is pending");

        onFx(AfterFirstPaint::release);
        Await.until(
                com.editora.editor.EditorFx.checkOnFx(() -> minimap.rendersForTest() != 0),
                "the first render once the content has painted");
    }

    /**
     * Regression for a CI flake (expected 393, was 394): a minimap another test left showing, whose first render
     * was already counting its two frames when this launch's hold began, rendered inside the ten frames waited
     * here and was counted against this test's minimap.
     */
    @Test
    void anotherWindowsMinimapRenderingMeanwhileIsNotCountedAsThisOne() throws Exception {
        FxToolkit.registerPrimaryStage();
        AfterFirstPaint.release();
        Stage leftOver = showMinimap(); // its first render starts at once: the gate is open
        Minimap leftOverMinimap = minimap;
        try {
            AfterFirstPaint.hold();
            showMinimap();
            awaitFrames(10);
            assertEquals(0, renders(), "no render while the launch's first paint is pending");
            int[] other = new int[1];
            onFx(() -> other[0] = leftOverMinimap.rendersForTest());
            assertTrue(other[0] > 0, "the other window's minimap did render meanwhile");
        } finally {
            onFx(leftOver::hide);
        }
    }

    @Test
    void outsideALaunchTheFirstRenderComesWithoutWaiting() throws Exception {
        FxToolkit.registerPrimaryStage();
        AfterFirstPaint.release();
        showMinimap();
        Await.until(
                com.editora.editor.EditorFx.checkOnFx(() -> minimap.rendersForTest() != 0),
                "the first render, two frames after the editor painted");
    }
}
