package com.editora.terminal;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pty4j's process throws from {@code toHandle()}, and Editora's process registry needs it — so a tracked
 * terminal shell crashed the start before this wrapper (the shell was left running, untracked).
 */
class PtyHandleProcessTest {

    @Test
    void aShellOnAPseudoTerminalHasAHandleAndDescendants() throws Exception {
        LocalPtyConnector connector = LocalShell.start(List.of(), null, 80, 24);
        try {
            Process process = connector.trackableProcess();
            assertEquals(connector.pid(), process.toHandle().pid());
            assertEquals(connector.pid(), process.pid());
            process.descendants().toList(); // goes through toHandle(): must not throw
            assertTrue(process.isAlive());
        } finally {
            connector.close();
        }
        Process ended = connector.trackableProcess();
        assertTrue(ended.waitFor(10, TimeUnit.SECONDS), "closing the connector ends the shell");
        assertFalse(ended.isAlive());
    }
}
