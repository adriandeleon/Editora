package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stop's frame files are looked at off the FX thread; the FX thread answers from that (PERF-01, Pauta 6.2.0). */
@Tag("fx")
class FrameFilesFxTest {

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

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.call());
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void warmedFramesAnswerOnFxWithoutALook(@TempDir Path dir) throws Exception {
        Path there = Files.writeString(dir.resolve("Main.java"), "class Main {}");
        Path gone = dir.resolve("Gone.java");
        FrameFiles.warm(List.of(
                new DapModels.StackFrameInfo(1, "a", there, 0, 0), new DapModels.StackFrameInfo(2, "b", gone, 0, 0)));
        Thread fx = onFx(Thread::currentThread);
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == fx) {
                throw new AssertionError(site + " looked on the FX thread");
            }
        });
        assertTrue(onFx(() -> FrameFiles.isFile(there)));
        assertFalse(onFx(() -> FrameFiles.isFile(gone)), "a frame with no file is told apart");
    }

    @Test
    void aFrameNothingLookedAtShowsAndIsLookedAtBehind(@TempDir Path dir) throws Exception {
        Path never = dir.resolve("Never.java");
        Thread fx = onFx(Thread::currentThread);
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == fx) {
                throw new AssertionError(site + " looked on the FX thread");
            }
        });
        assertTrue(onFx(() -> FrameFiles.isFile(never)), "unknown: the frame is not hidden");
        boolean seen = com.editora.testkit.Await.holdsWithin(
                () -> {
                    try {
                        return !onFx(() -> FrameFiles.isFile(never));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                java.time.Duration.ofSeconds(5));
        assertTrue(seen, "the look behind the answer lands");
    }

    @Test
    void aStopLooksAtEachFileOnceAndNotAgainWhileFresh(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("A.java"), "class A {}");
        Path b = Files.writeString(dir.resolve("B.java"), "class B {}");
        List<DapModels.StackFrameInfo> recursive = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            recursive.add(new DapModels.StackFrameInfo(i, "f", i % 2 == 0 ? a : b, i, 0));
        }
        recursive.add(new DapModels.StackFrameInfo(999, "native", null, 0, 0));
        assertEquals(java.util.Set.of(a, b), FrameFiles.toLook(recursive), "each file once");
        FrameFiles.warmAsync(recursive).get(10, TimeUnit.SECONDS);
        assertTrue(FrameFiles.toLook(recursive).isEmpty(), "looked at a moment ago: not again");
        assertTrue(onFx(() -> FrameFiles.isFile(a)));
        assertTrue(FrameFiles.warmAsync(recursive).isDone(), "nothing to look at: done at once");
    }

    @Test
    void theLooksAreNotMadeOnTheCallingThread(@TempDir Path dir) throws Exception {
        Path c = Files.writeString(dir.resolve("C.java"), "class C {}");
        Thread caller = Thread.currentThread();
        java.util.concurrent.atomic.AtomicReference<Thread> looked =
                new java.util.concurrent.atomic.AtomicReference<>();
        BlockingSites.listenForTest((site, thread) -> looked.compareAndSet(null, thread));
        FrameFiles.warmAsync(List.of(new DapModels.StackFrameInfo(1, "c", c, 0, 0)))
                .get(10, TimeUnit.SECONDS);
        assertNotEquals(caller, looked.get());
    }
}
