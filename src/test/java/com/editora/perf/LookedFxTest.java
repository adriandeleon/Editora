package com.editora.perf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Looked}: a look made off the FX thread is kept, and the FX thread answers from it without looking (PERF-01,
 * Pauta 6.0.1); the FX thread looks itself, named to {@link BlockingSites}, only for a key never looked at.
 */
@Tag("fx")
class LookedFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                out.set(work.call());
            } catch (Exception e) {
                failed.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    @Test
    void theFxThreadAnswersFromWhatAPlanLookedAt() throws Exception {
        AtomicInteger looks = new AtomicInteger();
        AtomicReference<String> disk = new AtomicReference<>("first");
        Looked<String, String> looked = new Looked<>("guard-look", key -> {
            looks.incrementAndGet();
            return disk.get() == null ? null : key + ":" + disk.get();
        });
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });

        assertEquals("a:first", looked.get("a"), "a plan's look, off the FX thread");
        assertEquals("a:first", onFx(() -> looked.get("a")), "the FX thread answers from it");
        assertEquals(List.of(), onFx, "without looking");
        assertEquals(1, looks.get());

        disk.set("second");
        assertEquals("a:second", looked.get("a"), "the next plan's look refreshes it");
        assertEquals("a:second", onFx(() -> looked.get("a")));

        disk.set(null);
        assertNull(onFx(() -> looked.get("b")), "a key nobody looked at is looked at where asked");
        assertEquals(List.of("guard-look"), onFx, "and that look is named");
        assertNull(onFx(() -> looked.get("b")), "a null answer is kept too");
        assertEquals(List.of("guard-look"), onFx);
    }
}
