package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.snippet.SnippetParser;
import com.editora.snippet.SnippetSessions;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N3: a running snippet session is drawn over the text, and holds no texture once it has ended. */
@Tag("fx")
class SnippetFieldOverlayFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return out.get();
    }

    /** Lets queued runLaters and a layout pulse or two run. */
    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    private static int inked(SnippetFieldOverlay overlay) {
        Canvas canvas = (Canvas) overlay.getChildrenUnmodifiable().get(0);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage image = canvas.snapshot(params, null);
        int inked = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getPixelReader().getArgb(x, y) >>> 24) & 0xff) > 0) {
                    inked++;
                }
            }
        }
        return inked;
    }

    @Test
    void fieldsAreDrawnWhileTheSessionRunsAndTheCanvasIsReleasedAfter() throws Exception {
        CodeArea area = onFx(CodeArea::new);
        SnippetSessions sessions = new SnippetSessions();
        SnippetFieldOverlay overlay = onFx(() -> new SnippetFieldOverlay(area, sessions));
        Stage stage = onFx(() -> {
            sessions.setOnChanged(overlay::refresh);
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(new VirtualizedScrollPane<>(area), overlay), 520, 240));
            s.show();
            return s;
        });
        try {
            settle();
            assertFalse(onFx(overlay::isVisible), "nothing to show before a session starts");
            assertEquals(
                    1.0, onFx(() -> ((Canvas) overlay.getChildrenUnmodifiable().get(0)).getWidth()));

            onFx(() -> {
                sessions.start(
                        area,
                        SnippetParser.parse("for (${1:int} ${2:i} = 0; $2 < ${3:max}; $2++) {\n\t$0\n}", name -> null),
                        0,
                        0,
                        "");
                return null;
            });
            settle();
            assertTrue(onFx(overlay::isVisible));
            int running = onFx(() -> inked(overlay));
            assertTrue(running > 200, "the fields, the mirrors and $0 are painted: " + running + " px");

            // The marks follow the active field: moving on repaints (a different field is filled).
            onFx(() -> {
                sessions.next();
                return null;
            });
            settle();
            assertTrue(onFx(() -> inked(overlay)) > 200);

            onFx(() -> {
                sessions.cancel();
                return null;
            });
            settle();
            assertFalse(onFx(overlay::isVisible));
            assertEquals(
                    1.0, onFx(() -> ((Canvas) overlay.getChildrenUnmodifiable().get(0)).getWidth()));
        } finally {
            onFx(() -> {
                stage.hide();
                return null;
            });
        }
    }

    @Test
    void theMarksSayWhatEachRangeIs() throws Exception {
        java.util.List<String> marks = onFx(() -> {
            CodeArea area = new CodeArea();
            SnippetSessions sessions = new SnippetSessions();
            sessions.start(area, SnippetParser.parse("${1:a} $1 ${2:b}$0", name -> null), 0, 0, "");
            java.util.List<String> out = new java.util.ArrayList<>();
            sessions.marks((start, end, kind) -> out.add(start + "-" + end + ":" + kind));
            sessions.next();
            sessions.marks((start, end, kind) -> out.add(start + "-" + end + ":" + kind));
            assertEquals(2, sessions.progress()[0]);
            assertEquals(2, sessions.progress()[1]);
            sessions.cancel();
            sessions.marks((start, end, kind) -> out.add("after the end"));
            return out;
        });
        assertEquals(
                java.util.List.of(
                        "0-1:0", "2-3:2", "4-5:1", "5-5:3", // field 1 active, its mirror, field 2 waiting, $0
                        "0-1:1", "2-3:2", "4-5:0", "5-5:3"),
                marks);
    }
}
