package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.Scene;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks every frame an overlay shows for a while after a change — not where it settles, which can hide a frame
 * painted for the old layout and put right one frame later.
 */
public final class FrameWatch {

    /**
     * How long after a change each frame is checked: the line-number gutter is re-padded on the fold manager's
     * 250 ms recompute, not by the edit that crossed a power of ten.
     */
    static final long WATCH_NANOS = 800_000_000L;

    private FrameWatch() {}

    /**
     * Runs {@code change}, then at the end of each pulse for {@link #WATCH_NANOS} — after the scene's layout and
     * the overlay's own post-layout paint, so what is checked is the frame about to be shown — asks {@code check},
     * which returns null when the frame is right and a description when it is not. Returns one line per frame
     * that was wrong.
     *
     * <p>A post-layout listener the overlay registers during a pulse's layout runs after this one in that pulse;
     * when the overlay has a paint pending that way, its frame is checked at the start of the next pulse instead
     * (nothing of the overlay's runs in between).
     *
     * @param overlay the overlay, whose {@code afterLayout} ({@link AfterLayoutPaint}) says whether it paints later
     *     in the pulse; an overlay without one paints between pulses, never during one
     */
    public static List<String> wrongFrames(EditorBuffer buffer, Object overlay, Supplier<String> check, Runnable change)
            throws Exception {
        List<String> wrong = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EditorFx.onFx(() -> {
            Scene scene = buffer.getNode().getScene();
            int[] pulse = {0};
            long[] began = {System.nanoTime()};
            boolean[] deferred = {false};
            Runnable[] last = new Runnable[1];
            Runnable[] first = new Runnable[1];
            Runnable verify = () -> {
                String problem = check.get();
                if (problem != null) {
                    wrong.add("frame " + pulse[0] + ": " + problem);
                }
            };
            last[0] = () -> {
                pulse[0]++;
                if (paintsLaterThisPulse(overlay, scene)) {
                    deferred[0] = true;
                } else {
                    verify.run();
                }
                if (System.nanoTime() - began[0] >= WATCH_NANOS && !deferred[0]) {
                    scene.removePreLayoutPulseListener(first[0]);
                    scene.removePostLayoutPulseListener(last[0]);
                    done.countDown();
                } else {
                    Platform.requestNextPulse();
                }
            };
            // Before each layout: the deferred check of the last frame, then the checker moved behind every
            // post-layout listener registered so far.
            first[0] = () -> {
                if (deferred[0]) {
                    deferred[0] = false;
                    verify.run();
                }
                scene.removePostLayoutPulseListener(last[0]);
                scene.addPostLayoutPulseListener(last[0]);
            };
            change.run();
            scene.addPreLayoutPulseListener(first[0]);
            Platform.requestNextPulse();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the pulses ran");
        return EditorFx.callFx(() -> List.copyOf(wrong));
    }

    private static boolean paintsLaterThisPulse(Object overlay, Scene scene) {
        Object afterLayout;
        try {
            afterLayout = EditorFx.field(overlay, "afterLayout");
        } catch (IllegalArgumentException paintsFromRunLater) {
            return false; // nothing of the overlay's runs in the pulse
        }
        return afterLayout instanceof AfterLayoutPaint paint && paint.paintsAfterLayoutOf(scene);
    }
}
