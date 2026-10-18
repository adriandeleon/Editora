package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
