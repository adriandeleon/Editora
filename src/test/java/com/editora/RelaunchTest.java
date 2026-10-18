package com.editora;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Restart now": the command line it starts again, and the wait for the editor it replaces. */
class RelaunchTest {

    @Test
    void theCommandLineOfThisProcessIsKnownSoARestartCanBeOffered() {
        assertTrue(Relaunch.available());
        assertFalse(Relaunch.commandLine().orElseThrow().isEmpty());
    }

    @Test
    void aRelaunchedEditorWaitsForTheOneItReplacesAndAnOrdinaryLaunchDoesNot() throws Exception {
        Process previous = new ProcessBuilder(
                        ProcessHandle.current().info().command().orElseThrow(), "-version")
                .redirectErrorStream(true)
                .start();
        Relaunch.awaitExit(Long.toString(previous.pid()), 30);
        assertFalse(previous.isAlive(), "returns once the previous process is gone");
        previous.waitFor(5, TimeUnit.SECONDS);
        long start = System.nanoTime();
        Relaunch.awaitExit(null, 30);
        Relaunch.awaitExit("not a pid", 30);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "no wait for an ordinary launch");
    }

    @Test
    void aCancelledQuitDisarmsTheRestart() {
        try {
            assertTrue(Relaunch.arm());
        } finally {
            Relaunch.disarm(); // nothing starts when this test JVM exits
        }
    }
}
