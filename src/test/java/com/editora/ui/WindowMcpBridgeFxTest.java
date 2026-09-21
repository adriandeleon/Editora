package com.editora.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class WindowMcpBridgeFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void interruptedCallerCannotLeaveAQueuedFxOperationBehind() throws Exception {
        CountDownLatch fxBlocked = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        CountDownLatch callerStarted = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        Platform.runLater(() -> {
            fxBlocked.countDown();
            try {
                releaseFx.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(fxBlocked.await(2, TimeUnit.SECONDS));
        Thread caller = Thread.ofVirtual().start(() -> {
            callerStarted.countDown();
            try {
                assertThrows(
                        RuntimeException.class,
                        () -> WindowMcpBridge.mcpOnFx(() -> {
                            ran.set(true);
                            return true;
                        }));
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        });
        assertTrue(callerStarted.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2_000);
        releaseFx.countDown();
        FxTestSupport.drainFx();
        assertFalse(caller.isAlive());
        assertNull(callerFailure.get());
        assertFalse(ran.get());
    }
}
