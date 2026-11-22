package com.editora.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.animation.AnimationTimer;

/**
 * Waits for the JavaFX toolkit to run pulses (QUAL-07, Pauta 6.2.0: wait on the thing itself, not for a while). A
 * pulse is when the scene applies CSS, lays out and renders, so what a test means by "let the layout settle" is a
 * pulse or two, counted, not a pause.
 */
final class FxPulses {

    private FxPulses() {}

    /** Waits until {@code count} more pulses have run, with the FX queue drained before and after. */
    static void await(int count) throws Exception {
        FxTestSupport.drainFx();
        CountDownLatch pulses = new CountDownLatch(count);
        AnimationTimer counter = new AnimationTimer() {
            @Override
            public void handle(long now) {
                pulses.countDown();
            }
        };
        FxTestSupport.runOnFx(counter::start);
        try {
            if (!pulses.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("the FX toolkit ran no pulse for 20 s");
            }
        } finally {
            FxTestSupport.runOnFx(counter::stop);
        }
        FxTestSupport.drainFx();
    }
}
