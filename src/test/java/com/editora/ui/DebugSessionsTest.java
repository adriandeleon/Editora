package com.editora.ui;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A window starts with one debug session, on its own manager, and it is the one shown. */
class DebugSessionsTest {

    @Test
    void theFirstSessionIsTheWindowsManagerAndIsFocused() {
        DapManager dap = new DapManager(null);
        DebugSessions sessions = new DebugSessions(dap);
        assertEquals(1, sessions.size());
        assertSame(dap, sessions.focused().dap);
        assertSame(sessions.focused(), sessions.all().getFirst());
    }

    @Test
    void aSessionStartsWithNothingShownOrSent() {
        DebugSession s = new DebugSession(new DapManager(null));
        assertEquals(-1, s.debugFrameId);
        assertNull(s.shownFrame);
        assertEquals(-1, s.quietStop);
        assertEquals(0, s.recordedStops);
        assertEquals(-1, s.exceptionCardStop);
        assertEquals(true, s.sessionAnnounced);
        assertEquals(0, s.sentBreakpoints.size());
    }

    @Test
    void shutdownEndsEverySession() {
        DapManager dap = new DapManager(null);
        DebugSessions sessions = new DebugSessions(dap);
        sessions.shutdown();
        assertEquals(DapManager.State.INACTIVE, dap.state());
    }
}
