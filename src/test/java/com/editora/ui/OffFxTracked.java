package com.editora.ui;

import java.util.concurrent.atomic.AtomicInteger;

import com.editora.perf.OffFx;
import com.editora.testkit.Await;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs the work handed to {@link OffFx} where the app runs it (a virtual thread named for it), and counts what has not
 * finished, so a test can wait until the background looks a window started have landed (QUAL-07, Pauta 6.0.1: wait
 * on the thing itself, not for a while). {@link OffFxInPlace} runs the work in place instead, for a test that reads a
 * command's result as the command returns.
 */
public final class OffFxTracked implements BeforeAllCallback, AfterAllCallback {

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        OffFx.runnerForTest((name, task) -> {
            IN_FLIGHT.incrementAndGet();
            Thread.ofVirtual().name(name).start(() -> {
                try {
                    task.run(); // ends by queuing the answer for the FX thread
                } finally {
                    IN_FLIGHT.decrementAndGet();
                    Await.changed();
                }
            });
        });
    }

    @Override
    public void afterAll(ExtensionContext context) {
        OffFx.runnerForTest(null);
    }

    /**
     * Waits until no handed-off work is running and the FX thread has run every answer it queued; an answer that hands
     * off more work is waited for too.
     */
    static void awaitIdle() throws Exception {
        do {
            Await.until(() -> IN_FLIGHT.get() == 0, "the work handed off the FX thread to finish");
            FxTestSupport.drainFx(); // the answers it queued
        } while (IN_FLIGHT.get() > 0);
    }
}
