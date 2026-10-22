package com.editora.editor.minimap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A content render caches its image without touching the rest of the window. Snapshotting the on-screen
 * canvas made JavaFX run a CSS pass over the whole window first, outside the pulse, and that pass could
 * leave nodes styled with unresolved theme colours. Here the window has a pending style change when the
 * render runs, on the same FX turn: it must still be pending afterwards.
 */
@Tag("fx")
class MinimapRenderOffWindowFxTest {

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

    @AfterEach
    void close() throws Exception {
        onFx(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    void aContentRenderDoesNotRunCssOverTheWindow() throws Exception {
        FxToolkit.registerPrimaryStage();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("line ").append(i).append(" of a file\n");
        }
        var minimapRef = new AtomicReference<Minimap>();
        var labelRef = new AtomicReference<Label>();
        onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(text.toString());
            Minimap minimap = new Minimap(area);
            var scroll = new VirtualizedScrollPane<>(area);
            HBox.setHgrow(scroll, Priority.ALWAYS);
            Label label = new Label("elsewhere in the window");
            stage = new Stage();
            stage.setScene(new Scene(new HBox(label, scroll, minimap), 900, 600));
            stage.show();
            minimapRef.set(minimap);
            labelRef.set(label);
        });
        int before = Minimap.RENDERS_FOR_TEST.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (Minimap.RENDERS_FOR_TEST.get() == before || cached(minimapRef.get()) == null) {
            assertTrue(System.nanoTime() < deadline, "the first content render never ran");
            Thread.sleep(20);
        }

        var fillBefore = new AtomicReference<Paint>();
        var fillAfter = new AtomicReference<Paint>();
        var rendersDuring = new AtomicReference<Integer>();
        onFx(() -> {
            Minimap minimap = minimapRef.get();
            Label label = labelRef.get();
            fillBefore.set(label.getTextFill());
            label.setStyle("-fx-text-fill: #ff0000;"); // applied by the next CSS pass, whoever runs it
            minimap.setColors(Color.web("#123456"), Color.web("#0969da", 0.14)); // so the render redraws
            int start = Minimap.RENDERS_FOR_TEST.get();
            try {
                var renderNow = Minimap.class.getDeclaredMethod("renderNow");
                renderNow.setAccessible(true);
                renderNow.invoke(minimap);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            rendersDuring.set(Minimap.RENDERS_FOR_TEST.get() - start);
            fillAfter.set(label.getTextFill());
        });

        assertEquals(1, rendersDuring.get(), "the render ran and redrew");
        assertNotNull(cached(minimapRef.get()), "and cached its image");
        assertEquals(fillBefore.get(), fillAfter.get(), "the window's pending style change was left to the pulse");
    }

    private static WritableImage cached(Minimap minimap) throws Exception {
        var out = new AtomicReference<WritableImage>();
        onFx(() -> {
            try {
                var f = Minimap.class.getDeclaredField("contentImage");
                f.setAccessible(true);
                out.set((WritableImage) f.get(minimap));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        return out.get();
    }
}
