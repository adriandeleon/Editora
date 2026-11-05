package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStatusNarratorTest {

    private static final long QUIET = ServerStatusNarrator.QUIET_MILLIS;

    private final ServerStatusNarrator narrator = new ServerStatusNarrator();
    private final List<String> shown = new ArrayList<>();
    private long now = 1_000;

    private void status(String type, String message) {
        record(narrator.onStatus("java", type, message, now));
        now += 10;
    }

    private void quiet() {
        now += QUIET;
        narrator.tick(now).forEach(this::record);
    }

    private void record(ServerStatusNarrator.Line line) {
        if (line != null) {
            shown.add(line.ready() ? "READY" : line.text());
        }
    }

    /** The jdtls start the Messages popup used to end on "Publish Diagnostics (0%)". */
    @Test
    void jdtlsStartupEndsWithOneReadyLineAfterItsWorkIsDone() {
        status("Launching", null);
        status("Starting", "100% Starting Java Language Server - Opening 'test'.");
        status("ServiceReady", null); // the handshake: jdtls is still importing after it
        quiet();
        status("Starting", "Importing Maven project(s) - Opening 'test'.");
        status("Started", "Ready");
        status("Starting", "100% Starting Java Language Server - Opening 'test'.");
        status("ServiceReady", "ServiceReady");
        status("Progress", "Searching...");
        status("Progress", "Validate documents");
        status("ProgressEnd", null);
        status("Progress", "Publish Diagnostics");
        status("ProgressEnd", null);
        status("ProgressEnd", null);
        quiet();

        assertEquals(
                List.of(
                        "100% Starting Java Language Server - Opening 'test'.",
                        "Importing Maven project(s) - Opening 'test'.",
                        "100% Starting Java Language Server - Opening 'test'.",
                        "Searching...",
                        "Validate documents",
                        "Publish Diagnostics",
                        "READY"),
                shown);
        assertFalse(narrator.pending());
    }

    /**
     * A Gradle import, as jdtls 1.61 sent it (Java review C8): "Starting" lines keep coming after the worded
     * ServiceReady and no ready word follows, so readiness waited for ever. Percentages past 100 are dropped
     * (C17) and the run of identical lines they leave collapses.
     */
    @Test
    void aGradleImportIsCalledReadyAndItsPercentagesPast100AreDropped() {
        status("Launching", null);
        status("Starting", "0% Starting Java Language Server");
        status("ServiceReady", null);
        status("Starting", "70% Starting Java Language Server - Refreshing '/project'.");
        status("ProjectStatus", "OK");
        status("Starting", "100% Starting Java Language Server - Refreshing '/project'.");
        status("Starting", "1000% Starting Java Language Server - Refreshing '/project'.");
        status("Starting", "1005% Starting Java Language Server - Refreshing '/project'.");
        status("Starting", "1100% Starting Java Language Server - Refreshing '/project'.");
        status("Started", "Ready");
        status("ServiceReady", "ServiceReady");
        status("Starting", "1100% Starting Java Language Server - Synchronize Gradle project demo");
        status("Starting", "1100% Starting Java Language Server");
        quiet();

        assertEquals(
                List.of(
                        "0% Starting Java Language Server",
                        "70% Starting Java Language Server - Refreshing '/project'.",
                        "100% Starting Java Language Server - Refreshing '/project'.",
                        "Starting Java Language Server - Refreshing '/project'.",
                        "Starting Java Language Server - Synchronize Gradle project demo",
                        "Starting Java Language Server",
                        "READY"),
                shown);
    }

    /** "ProjectStatus WARNING" was shown as the bare word; it is a failed import, said as one (C17, C4). */
    @Test
    void aProjectThatDidNotImportIsReportedAsAProblem() {
        status("Starting", "50% Starting Java Language Server");
        var line = narrator.onStatus("java", "ProjectStatus", "WARNING", now);
        assertTrue(line.problem());
        assertEquals("WARNING", line.text());
        assertNull(narrator.onStatus("java", "ProjectStatus", "OK", now));
        assertFalse(new ServerStatusNarrator.Line("java", "x").problem());
        assertNull(ServerStatusNarrator.startingText("1100%"), "nothing left to say");
        assertEquals("42% x", ServerStatusNarrator.startingText("42% x"));
    }

    @Test
    void noReadyLineWhileWorkIsStillOutstanding() {
        status("ServiceReady", null);
        status("Progress", "Indexing");
        quiet();
        assertEquals(List.of("Indexing"), shown);
        assertTrue(narrator.pending());

        status("ProgressEnd", null);
        quiet();
        assertEquals(List.of("Indexing", "READY"), shown);
    }

    @Test
    void aServerWithoutStartupStatusesIsReadyWhenTheHandshakeSettles() {
        status("ServiceReady", null);
        quiet();
        assertEquals(List.of("READY"), shown);
    }

    @Test
    void quickBackgroundWorkAfterReadyStaysOutOfTheLog() {
        status("ServiceReady", null);
        quiet();
        status("Progress", "Publish Diagnostics");
        status("ProgressEnd", null);
        quiet();
        assertEquals(List.of("READY"), shown);
    }

    @Test
    void slowBackgroundWorkIsShownAndThenConcluded() {
        status("ServiceReady", null);
        quiet();
        status("Progress", "Building workspace");
        quiet();
        assertEquals(List.of("READY", "Building workspace"), shown);
        status("ProgressEnd", null);
        assertEquals(List.of("READY", "Building workspace", "READY"), shown);
    }

    @Test
    void aFailedStartIsNeverCalledReady() {
        status("Starting", "Init...");
        status("Error", "Failed to start language server: boom");
        quiet();
        assertEquals(List.of("Init...", "Failed to start language server: boom"), shown);
        assertFalse(narrator.pending());
    }

    @Test
    void aRestartedServerIsAnnouncedAgain() {
        status("ServiceReady", null);
        quiet();
        status("Error", null); // crashed
        status("Launching", null);
        status("ServiceReady", null);
        quiet();
        assertEquals(List.of("READY", "READY"), shown);
    }

    @Test
    void messagesPassThroughButAnImmediateRepeatDoesNot() {
        status("Message", "Classpath is incomplete");
        status("Message", "Classpath is incomplete");
        status("ProjectStatus", "OK");
        status("ProjectStatus", "WARNING");
        assertEquals(List.of("Classpath is incomplete", "WARNING"), shown);
    }

    /** Ticks as the coordinator's settle timer does — every {@link #QUIET} while a line could still be due. */
    private void waitFor(long millis) {
        long until = now + millis;
        while (now < until && narrator.pending()) {
            quiet();
        }
    }

    /** A Begin whose End never came held the ready line back for good: the user never heard the server was done. */
    @Test
    void workThatNeverEndsIsTakenAsOverOnceTheServerFallsSilent() {
        status("ServiceReady", null);
        status("Progress", "Indexing");
        waitFor(ServerStatusNarrator.STALL_MILLIS - 2 * QUIET);
        assertEquals(List.of("Indexing"), shown, "still within the stall time: no ready line yet");

        waitFor(4 * QUIET);
        assertEquals(List.of("Indexing", "READY"), shown);
        assertFalse(narrator.pending());

        // the count starts again from nothing: later work is shown and concluded as usual
        status("Progress", "Building workspace");
        quiet();
        status("ProgressEnd", null);
        assertEquals(List.of("Indexing", "READY", "Building workspace", "READY"), shown);
    }

    @Test
    void reportsKeepLongWorkFromBeingTakenAsStalled() {
        status("ServiceReady", null);
        status("Progress", "Indexing");
        for (int i = 0; i < 3; i++) {
            waitFor(ServerStatusNarrator.STALL_MILLIS / 2);
            status("ProgressReport", null);
        }
        assertEquals(List.of("Indexing"), shown);
        status("ProgressEnd", null);
        quiet();
        assertEquals(List.of("Indexing", "READY"), shown);
    }

    @Test
    void shownBackgroundWorkThatNeverEndsStillEndsWithAReadyLine() {
        status("ServiceReady", null);
        quiet();
        status("Progress", "Compiling");
        quiet();
        waitFor(ServerStatusNarrator.STALL_MILLIS + 2 * QUIET);
        assertEquals(List.of("READY", "Compiling", "READY"), shown);
        assertFalse(narrator.pending());
    }

    @Test
    void aStepsTextIsShownBeforeReadyAndReplacesHeldWorkAfter() {
        status("Progress", "Importing build");
        status("ProgressReport", "Compiling root");
        status("ServiceReady", null);
        status("ProgressEnd", null);
        quiet();
        status("Progress", "Compiling");
        status("ProgressReport", "Compiling tests");
        quiet();
        status("ProgressEnd", null);
        assertEquals(List.of("Importing build", "Compiling root", "READY", "Compiling tests", "READY"), shown);
    }

    @Test
    void aServerThatNeverSaidReadyIsNotCalledReadyByTheStall() {
        status("Starting", "Importing");
        status("Progress", "Indexing");
        waitFor(2 * ServerStatusNarrator.STALL_MILLIS);
        assertEquals(List.of("Importing", "Indexing"), shown);
    }
}
