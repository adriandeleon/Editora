package com.editora.process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the process layer does when it is asked on the FX thread (PERF-01, Pauta 6.2.0): the augmented PATH is never
 * built there (a cold one is answered without the login shell, and the warm-up starts), the process ledger is never
 * written there, and Stop's walk of the process table is never made there.
 */
@Tag("fx")
class ProcessWorkOffFxThreadFxTest {

    /** A site heard, the thread it was heard on, and whether that was the FX thread. */
    private record Heard(String site, String thread, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void tearDown() {
        BlockingSites.listenForTest(null);
        ProcessRegistry.setLedgerFile(null);
    }

    private void listen() {
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));
    }

    private List<String> onFxSites() {
        return heard.stream().filter(Heard::fx).map(Heard::site).toList();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = {null};
        Throwable[] failure = {null};
        Platform.runLater(() -> {
            try {
                result[0] = work.call();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "the FX thread ran the work");
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws Exception {
        Await.until(condition, what);
    }

    @Test
    void aColdAugmentedPathIsNeverBuiltOnTheFxThread() throws Exception {
        ProcessRunner.forgetAugmentedPathForTest();
        listen();
        long began = System.nanoTime();
        String provisional = onFx(ProcessRunner::augmentedPath);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertFalse(onFxSites().contains("ProcessRunner.augmentedPath"), "built on the FX thread: " + heard);
        assertTrue(tookMillis < 5_000, "the FX caller did not wait for the login shell: " + tookMillis + " ms");
        String inherited = System.getenv("PATH");
        if (inherited != null && !inherited.isBlank()) {
            for (String entry : inherited.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    assertTrue(
                            List.of(provisional.split(File.pathSeparator)).contains(entry),
                            "the inherited PATH is in the provisional one: " + entry);
                }
            }
        }
        // The warm-up started from that ask builds it on its own thread, and FX callers get it from then on.
        await("the warm-up", ProcessRunner::augmentedPathReady);
        assertTrue(
                heard.stream()
                        .anyMatch(h -> h.site().equals("ProcessRunner.augmentedPath")
                                && h.thread().equals("path-warmup")),
                "built by the warm-up: " + heard);
        assertEquals(ProcessRunner.augmentedPath(), onFx(ProcessRunner::augmentedPath));
    }

    @Test
    void theLedgerIsWrittenOffTheFxThreadAndHoldsTheProgram(@TempDir Path dir) throws Exception {
        Path ledger = dir.resolve("spawned-servers.txt");
        ProcessRegistry.setLedgerFile(ledger);
        Path own = ProcessRegistry.ownLedgerFor(ledger, ProcessRegistry.Owner.of(ProcessHandle.current()));
        Process child = new ProcessBuilder(sleeper()).start();
        try {
            listen();
            onFx(() -> {
                ProcessRegistry.track(child); // as a start on the FX thread used to
                return null;
            });
            await("the ledger", () -> Files.exists(own) && read(own).contains(Long.toString(child.pid())));
            assertFalse(onFxSites().contains("ProcessRegistry.writeLedger"), "written on the FX thread: " + heard);

            onFx(() -> {
                ProcessRegistry.killTree(child); // Stop
                return null;
            });
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Stop still ends the program");
            await(
                    "the process table walk",
                    () -> heard.stream().anyMatch(h -> h.site().equals("ProcessRegistry.descendants")));
            assertEquals(List.of(), onFxSites(), "nothing on the FX thread: " + heard);
            assertTrue(
                    heard.stream()
                            .filter(h -> h.site().equals("ProcessRegistry.descendants"))
                            .allMatch(h -> h.thread().equals("editora-process-reaper")),
                    heard.toString());
        } finally {
            child.destroyForcibly();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static List<String> sleeper() {
        String java = Path.of(System.getProperty("java.home"), "bin", com.editora.Os.isWindows() ? "java.exe" : "java")
                .toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName());
    }

    /** A program that waits to be ended: it reads its stdin, which ends when the test's JVM does. */
    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            while (System.in.read() >= 0) {
                // nothing comes; the read returns -1 once this test's JVM, which holds the pipe, is gone
            }
        }
    }
}
