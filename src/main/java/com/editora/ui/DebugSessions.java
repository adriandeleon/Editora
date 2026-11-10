package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.dap.DapManager;

/**
 * A window's debug sessions, and the one the Debug window and the editor show: the focused one, which the toolbar,
 * the Run menu's debug commands, the execution line, inline values and hovers act on. The window starts with one
 * session, on its own {@link DapManager}; the sessions after it each get a manager of their own that shares its
 * settings ({@link DapManager#newSession}). FX thread only.
 */
final class DebugSessions {

    private final List<DebugSession> sessions = new ArrayList<>();
    private DebugSession focused;

    DebugSessions(DapManager first) {
        focused = new DebugSession(first);
        sessions.add(focused);
    }

    /** The session the window shows and acts on. */
    DebugSession focused() {
        return focused;
    }

    /** Every session, oldest first. */
    List<DebugSession> all() {
        return List.copyOf(sessions);
    }

    int size() {
        return sessions.size();
    }

    /** Ends every session and releases their managers (the window closes). */
    void shutdown() {
        for (DebugSession s : sessions) {
            s.dap.shutdown();
        }
    }
}
