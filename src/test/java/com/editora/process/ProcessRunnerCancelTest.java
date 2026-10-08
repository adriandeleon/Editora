package com.editora.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerCancelTest {

    private static void assumeUnix() {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "uses sh");
    }

    @Test
    void cancellingALiveCommandKillsItAndReportsCancelled() {
        assumeUnix();
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        List<String> lines = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        ProcessRunner.Result r = ProcessRunner.runLiveInUserLocale(
                null,
                Duration.ofMinutes(1),
                // The kill reaches `sleep` before its shell (children first), and a shell whose child died of
                // a signal says so — "Terminated" — on its own stderr, which is live output too. Whether that
                // line is written before the shell is itself killed is a race, so the shell's stderr is closed
                // off after the one line this test reads; the command is still a parent with a live child.
                List.of("sh", "-c", "echo ready; exec 2>/dev/null; sleep 60"),
                Map.of(),
                (line, transientLine) -> {
                    lines.add(line);
                    cancel.cancel(); // from the reader thread, once the child is known to be running
                },
                cancel);
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertTrue(r.cancelled(), r.toString());
        assertFalse(r.ok());
        assertEquals(ProcessRunner.CANCELLED, r.message());
        assertEquals(List.of("ready"), lines);
        assertTrue(seconds < 30, "killed, not waited out: " + seconds + " s");
    }

    @Test
    void aCancelThatArrivesBeforeTheChildStartsStillStopsIt() {
        assumeUnix();
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        cancel.cancel();
        ProcessRunner.Result r = ProcessRunner.runLiveInUserLocale(
                null, Duration.ofMinutes(1), List.of("sleep", "60"), Map.of(), (line, transientLine) -> {}, cancel);
        assertTrue(r.cancelled(), r.toString());
    }

    @Test
    void aCommandNobodyCancelledIsNotCancelled() {
        assumeUnix();
        ProcessRunner.Result r = ProcessRunner.runLiveInUserLocale(
                null,
                Duration.ofMinutes(1),
                List.of("sh", "-c", "echo done"),
                Map.of(),
                (line, transientLine) -> {},
                new ProcessRunner.Cancellation());
        assertTrue(r.ok(), r.toString());
        assertFalse(r.cancelled());
    }
}
