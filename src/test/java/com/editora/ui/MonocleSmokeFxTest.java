package com.editora.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 smoke test: proves the JavaFX toolkit boots and a scene graph can be built headlessly via the
 * vendored Monocle backend (no display/xvfb). If this is green, the harness works. Tagged {@code fx}
 * so the pure suite can exclude it with {@code -DexcludedGroups=fx}.
 */
@Tag("fx")
class MonocleSmokeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void buildsSceneGraphHeadlessly() throws Exception {
        var text = new AtomicReference<String>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Button button = new Button("hello");
                new Scene(button, 120, 40);
                text.set(button.getText());
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(15, SECONDS), "FX thread did not run — toolkit failed to start headlessly");
        assertEquals("hello", text.get());
    }
}
