package com.editora.editor;

import java.util.concurrent.atomic.AtomicInteger;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AfterLayoutPaint}: the overlays over one area share one listener and one measure of the text's start per
 * pulse, the checks after a paint ask for no pulse of their own, and a pulse that moves the text still repaints
 * an overlay nobody asked.
 */
@Tag("fx")
class AfterLayoutPaintFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void overlaysShareOneMeasureAndAskForNoPulsesAfterAPaint() throws Exception {
        CodeArea[] area = new CodeArea[1];
        Stage[] stage = new Stage[1];
        AtomicInteger paintsA = new AtomicInteger();
        AtomicInteger paintsB = new AtomicInteger();
        AfterLayoutPaint[] paints = new AfterLayoutPaint[2];
        AtomicInteger pulses = new AtomicInteger();
        EditorFx.onFx(() -> {
            area[0] = new CodeArea("    alpha\n    beta\n    gamma\n");
            area[0].setFocusTraversable(false); // no caret blink: the only pulses are the ones this test makes
            area[0].setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.OFF);
            Region a = new Region();
            Region b = new Region();
            a.setMouseTransparent(true);
            b.setMouseTransparent(true);
            paints[0] = new AfterLayoutPaint(a, area[0], paintsA::incrementAndGet, () -> true);
            paints[1] = new AfterLayoutPaint(b, area[0], paintsB::incrementAndGet, () -> true);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(area[0], a, b), 400, 200);
            scene.addPostLayoutPulseListener(pulses::incrementAndGet);
            stage[0].setScene(scene);
            stage[0].show();
        });
        try {
            EditorFx.awaitPulses(3);
            EditorFx.drain();

            // Other windows of the test JVM may be animating; then every pulse runs the checks too.
            boolean quiet = quiet(pulses);
            int looks = EditorFx.callFx(paints[0]::measuresForTest);
            int pulsesBefore = pulses.get();
            EditorFx.onFx(() -> {
                paints[0].request();
                paints[1].request();
            });
            pulse(stage[0].getScene());
            assertEquals(1, paintsA.get(), "the first overlay painted once");
            assertEquals(1, paintsB.get(), "and so did the second");
            int looked = EditorFx.callFx(paints[0]::measuresForTest) - looks;
            assertTrue(
                    looked >= 1 && looked <= pulses.get() - pulsesBefore,
                    "one look at the text per pulse served both overlays: " + looked + " looks");

            if (quiet) {
                // Nothing moves: the checks wait for pulses that come anyway, and ask for none.
                assertTrue(EditorFx.callFx(paints[0]::armedForTest), "the checks after the paint are due");
                int before = pulses.get();
                Thread.sleep(300);
                assertTrue(
                        pulses.get() - before <= 1,
                        "no pulses asked for after a paint: " + (pulses.get() - before) + " ran");
            }
            if (EditorFx.callFx(paints[0]::listeningForTest)) {
                // A pulse that moves the text repaints both overlays, though nothing asked them to.
                EditorFx.onFx(() -> area[0].setPadding(new Insets(0, 0, 0, 40)));
                pulse(stage[0].getScene());
                assertEquals(2, paintsA.get(), "the moved text was followed");
                assertEquals(2, paintsB.get(), "by both overlays");
            }
            int painted = paintsA.get();

            // Pulses that move nothing use the checks up, and the listener comes off.
            for (int i = 0; i < AfterLayoutPaint.CHECKS_AFTER_PAINT + 1; i++) {
                pulse(stage[0].getScene());
            }
            assertEquals(painted, paintsA.get(), "nothing moved: no repaint");
            assertFalse(EditorFx.callFx(paints[0]::listeningForTest), "the listener came off");
        } finally {
            EditorFx.onFx(() -> stage[0].close());
        }
    }

    /** Waits (up to three seconds) for 300 ms without a pulse; returns whether it came. */
    private static boolean quiet(AtomicInteger pulses) throws InterruptedException {
        long end = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < end) {
            int before = pulses.get();
            Thread.sleep(300);
            if (pulses.get() == before) {
                return true;
            }
        }
        return false;
    }

    /** Runs one pulse, and waits for it (and the FX tasks queued by then). */
    private static void pulse(Scene scene) throws Exception {
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        EditorFx.onFx(() -> {
            Runnable[] once = new Runnable[1];
            once[0] = () -> {
                scene.removePostLayoutPulseListener(once[0]);
                ran.countDown();
            };
            scene.addPostLayoutPulseListener(once[0]);
            javafx.application.Platform.requestNextPulse();
        });
        assertTrue(ran.await(10, java.util.concurrent.TimeUnit.SECONDS), "the pulse ran");
        EditorFx.drain();
    }
}
