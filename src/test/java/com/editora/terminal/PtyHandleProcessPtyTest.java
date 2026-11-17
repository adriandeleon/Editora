package com.editora.terminal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

import com.editora.testkit.Await;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Waiting for a terminal's process to end holds no common-pool thread. The JDK's {@code Process.onExit()}, which
 * pty4j's process inherits, parks a common-pool worker in {@code waitFor()} until the end. The registry asks every
 * tracked process for its exit, so each open terminal held one; a program that never finished exiting held one for
 * good, and with two of them a three-core macOS runner's common pool ran nothing else again (every DapClient test
 * after LocalPtyConnectorPtyTest failed in the runs of PRs 1424 and fix/js-review on 2026-11-04).
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX sh")
class PtyHandleProcessPtyTest {

    @Test
    void waitingForTheExitHoldsNoCommonPoolThread() throws Exception {
        LocalPtyConnector connector =
                LocalShell.startProgram(List.of("/bin/sh", "-c", "sleep 60"), null, 80, 24, Map.of());
        Process program = connector.trackableProcess();
        try {
            CompletableFuture<Process> registry = program.onExit();
            CompletableFuture<Process> stop = program.onExit();
            // The JDK's version takes up its worker at once: wait until the pool has taken everything submitted to
            // it and each of its workers is idle or already inside pty4j, so a waiting worker would show now.
            ForkJoinPool common = ForkJoinPool.commonPool();
            Await.until(
                    () -> common.getQueuedSubmissionCount() == 0 && common.getQueuedTaskCount() == 0,
                    "the common pool took what was submitted");
            Await.holdsWithin(PtyHandleProcessPtyTest::everyCommonPoolWorkerIsIdleOrInAPty, Await.DEFAULT_TIMEOUT);

            assertFalse(aCommonPoolThreadWaitsOnAPty(), "a common-pool thread waits for the pty process");

            ProcessHandle.of(program.pid()).ifPresent(ProcessHandle::destroyForcibly);
            assertSame(program, registry.get(10, TimeUnit.SECONDS));
            assertSame(program, stop.get(10, TimeUnit.SECONDS));
        } finally {
            ProcessHandle.of(program.pid()).ifPresent(ProcessHandle::destroyForcibly);
            connector.close();
        }
    }

    private static boolean everyCommonPoolWorkerIsIdleOrInAPty() {
        ForkJoinPool common = ForkJoinPool.commonPool();
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey() instanceof ForkJoinWorkerThread w && w.getPool() == common)
                .allMatch(e -> e.getKey().getState() != Thread.State.RUNNABLE
                        || List.of(e.getValue()).stream()
                                .anyMatch(f -> f.getClassName().startsWith("com.pty4j.")));
    }

    private static boolean aCommonPoolThreadWaitsOnAPty() {
        ForkJoinPool common = ForkJoinPool.commonPool();
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey() instanceof ForkJoinWorkerThread w && w.getPool() == common)
                .anyMatch(e -> List.of(e.getValue()).stream()
                        .anyMatch(f -> f.getClassName().startsWith("com.pty4j.")));
    }
}
