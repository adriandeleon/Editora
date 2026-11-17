package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathKeys#canonical} never resolves a path on the FX thread (PERF-01, Pauta 6.0.1): there it answers what
 * it knew before the last invalidation, or the plain absolute form, and resolves behind; off it, it resolves now.
 */
@Tag("fx")
class PathKeysOffFxTest {

    @TempDir
    Path dir;

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
    void theFxThreadAnswersFromMemoryAndResolvesBehind() throws Exception {
        // The temp folder's own real path (macOS: /var is /private/var, which an earlier test may have resolved), and
        // the work on a thread of its own: a test before this one may have left it running in place.
        com.editora.perf.OffFx.runnerForTest(null);
        Path base = dir.toRealPath();
        Path real = Files.createDirectories(base.resolve("real"));
        Path link;
        try {
            link = Files.createSymbolicLink(base.resolve("link"), real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException noLinks) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
            return;
        }
        Path file = Files.writeString(real.resolve("a.txt"), "x");
        Path viaLink = link.resolve("a.txt");
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });

        Path cold = onFx(() -> PathKeys.canonical(viaLink));
        assertEquals(viaLink.toAbsolutePath().normalize(), cold, "never resolved: the plain form meanwhile");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Path landed;
        do {
            landed = onFx(() -> PathKeys.canonical(viaLink));
            assertTrue(System.nanoTime() < until, "the look behind never landed");
        } while (!landed.equals(file.toRealPath()));

        onFx(() -> {
            PathKeys.invalidateCanonicalCache(); // focus regained: the disk may have shifted
            return null;
        });
        assertEquals(file.toRealPath(), onFx(() -> PathKeys.canonical(viaLink)), "what was known, meanwhile");
        assertEquals(List.of(), onFx, "nothing resolved on the FX thread");

        assertEquals(file.toRealPath(), PathKeys.canonical(viaLink), "off the FX thread: resolved now");
    }
}
