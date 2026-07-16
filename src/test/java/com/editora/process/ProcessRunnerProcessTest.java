package com.editora.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessRunner} against real processes. It is the single chokepoint every subprocess in the app goes
 * through — git, mermaid, the build tools, LSP/DAP detection, external tools — so its timeout has to actually
 * bound a run, and it must never be possible for a child to wedge the calling thread forever.
 *
 * <p>POSIX shell utilities, so Windows sits these out.
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerProcessTest {

    @Test
    void normalCommandsAreUnaffected(@TempDir Path dir) {
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(10), List.of("echo", "hello"));
        assertEquals(0, r.exit());
        assertEquals("hello", r.out().strip());
        assertTrue(r.ok());
    }

    @Test
    void stderrIsCaptured(@TempDir Path dir) {
        ProcessRunner.Result r =
                ProcessRunner.run(dir, Duration.ofSeconds(10), List.of("sh", "-c", "echo oops >&2; exit 3"));
        assertEquals(3, r.exit());
        assertEquals("oops", r.err().strip());
    }

    @Test
    void stdinIsPipedAndClosed(@TempDir Path dir) {
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(10), List.of("cat"), Map.of(), "piped\n");
        assertEquals(0, r.exit(), "cat must see EOF and exit");
        assertEquals("piped", r.out().strip());
    }

    /**
     * The timeout used to be unreachable: stdout was drained inline on the calling thread, and
     * {@code waitFor(timeout)} only ran once that drain hit EOF — which needs the child to exit. So a child
     * that outlived its timeout was simply waited for. Measured before the fix: a 1 s timeout on {@code sleep
     * 5} returned after 5011 ms with exit=0.
     */
    @Test
    void theTimeoutActuallyBoundsARun(@TempDir Path dir) {
        long t0 = System.nanoTime();
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofMillis(300), List.of("sleep", "10"));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(-1, r.exit());
        assertTrue(r.err().contains("timed out"), r.err());
        assertTrue(elapsed < 5_000, "should give up at ~300 ms, took " + elapsed + "ms");
    }

    /**
     * With no stdin to feed, the child's stdin must still be closed. Leaving it open meant anything that
     * reads stdin ({@code grep}, {@code cat}, {@code sort}, {@code jq}) blocked on input that could never
     * come — so it never exited, never closed stdout, and the inline drain never returned. The single-thread
     * service executor then blocked every later run for the session.
     */
    @Test
    void aChildThatReadsStdinSeesEofRatherThanHangingForever() throws Exception {
        AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
        Thread t = new Thread(
                () -> result.set(ProcessRunner.run(Path.of("/tmp"), Duration.ofSeconds(2), List.of("grep", "foo"))));
        t.setDaemon(true);
        t.start();
        t.join(15_000);
        assertFalse(t.isAlive(), "ProcessRunner wedged on a child waiting for stdin");
        assertEquals(1, result.get().exit(), "grep read EOF and found nothing");
    }

    /** A runaway tool must not be able to grow the capture buffer without bound (the app runs -Xmx2g). */
    @Test
    void capturedOutputIsCapped(@TempDir Path dir) {
        ProcessRunner.Result r =
                ProcessRunner.run(dir, Duration.ofSeconds(30), List.of("sh", "-c", "yes x | head -c 12000000"));
        assertEquals(0, r.exit(), "the child must not be blocked by the cap — keep draining, stop storing");
        assertTrue(r.out().length() <= 10 * 1024 * 1024, "captured " + r.out().length() + " bytes");
        assertTrue(
                r.out().length() > 1_000_000,
                "but it still captures a lot: " + r.out().length());
    }
}
