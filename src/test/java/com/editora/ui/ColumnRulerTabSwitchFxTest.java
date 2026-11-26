package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Showing a tab must not draw its column ruler in a {@link Line}'s default stroke (black) for a frame before the
 * theme's colour arrives. The ruler is placed after the pulse's layout, which is after that pulse's CSS pass, so a
 * line created there was rendered unstyled once: the first time each tab was shown.
 *
 * <p>Sampled at the start of each pulse (a pre-layout listener, before CSS): what a line looks like then is what
 * the previous pulse rendered.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColumnRulerTabSwitchFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void showingATabNeverRendersItsRulerInTheDefaultBlack() throws Exception {
        List<String> black = new ArrayList<>();
        int[] seen = {0};
        TabPane tabs = FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "tabPane"));
        Scene scene = FxTestSupport.callOnFx(tabs::getScene);
        Runnable sample = () -> {
            if (!(FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}) instanceof EditorBuffer b)) {
                return;
            }
            Group ruler = FxTestSupport.field(b, "columnRuler");
            if (!ruler.isVisible() || ruler.getScene() == null) {
                return;
            }
            for (Node n : ruler.getChildren()) {
                if (n instanceof Line line && line.isVisible()) {
                    seen[0]++;
                    if (Color.BLACK.equals(line.getStroke())) {
                        black.add("tab " + tabs.getSelectionModel().getSelectedIndex());
                    }
                }
            }
        };
        FxTestSupport.runOnFx(() -> scene.addPreLayoutPulseListener(sample));
        try {
            for (String name : new String[] {"a", "b", "c"}) {
                Path file = Files.createTempFile("editora-ruler-" + name + "-", ".java");
                Files.writeString(file, "class A {\n" + ("    // " + "x".repeat(160) + "\n").repeat(30) + "}\n");
                FxTestSupport.runOnFx(() -> FxTestSupport.call(
                        FxTestSupport.field(fx.controller, "fileWorkflows"),
                        "openPath",
                        new Class<?>[] {Path.class},
                        file));
                settle(20);
            }
            for (int i = 0; i < 6; i++) {
                FxTestSupport.runOnFx(() -> {
                    var model = tabs.getSelectionModel();
                    model.select((model.getSelectedIndex() + 1) % tabs.getTabs().size());
                });
                settle(12);
            }
        } finally {
            FxTestSupport.runOnFx(() -> scene.removePreLayoutPulseListener(sample));
        }
        assertTrue(seen[0] > 0, "the ruler was never shown");
        assertTrue(black.isEmpty(), "a ruler line was rendered in the default black stroke: " + black);
    }

    private static void settle(int pulses) throws Exception {
        CountDownLatch frames = new CountDownLatch(pulses);
        AnimationTimer counter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                frames.countDown();
            }
        };
        FxTestSupport.runOnFx(counter::start);
        try {
            assertTrue(frames.await(10, TimeUnit.SECONDS), pulses + " pulses ran");
        } finally {
            FxTestSupport.runOnFx(counter::stop);
        }
        FxTestSupport.drainFx();
    }
}
