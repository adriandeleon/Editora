package com.editora.config;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether another instance is here is never probed on the FX thread (PERF-01, Pauta 6.2.0). */
@Tag("fx")
class InstanceLockPresenceFxTest {

    @BeforeAll
    static void boot() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another FX test booted the toolkit in this JVM
        }
        Platform.setImplicitExit(false);
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    private static boolean onFx(InstanceLock lock) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        Platform.runLater(() -> done.complete(lock.othersPresent()));
        return done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theFxThreadAnswersWhatAProbeOffItFound(@TempDir Path dir) throws Exception {
        try (InstanceLock mine = InstanceLock.claim(dir)) {
            CompletableFuture<Thread> fx = new CompletableFuture<>();
            Platform.runLater(() -> fx.complete(Thread.currentThread()));
            Thread fxThread = fx.get(10, TimeUnit.SECONDS);
            BlockingSites.listenForTest((site, thread) -> {
                if (thread == fxThread) {
                    throw new AssertionError(site + " probed on the FX thread");
                }
            });
            assertTrue(onFx(mine), "before any probe: not knowing is not nobody");
            assertFalse(mine.othersPresent(), "off the FX thread the probe is live: alone");
            assertFalse(onFx(mine), "then the FX thread answers what that probe found");
        }
    }
}
