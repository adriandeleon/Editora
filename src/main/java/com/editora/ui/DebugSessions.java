package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.dap.DapManager;

/**
 * A window's debug sessions, and the one the Debug window and the editor show: the focused one, which the toolbar,
 * the Run menu's debug commands, the execution line, inline values and hovers act on. The window starts with one
 * session, on its own {@link DapManager}; each one started while another is live gets a manager of its own that
 * shares the first one's settings ({@link DapManager#newSession}). A session that has ended stays, with its output,
 * until its tab is closed or a new session needs its place ({@link #MAX_SESSIONS}). FX thread only.
 */
final class DebugSessions {

    /** Sessions kept at most: past it, a new one takes the place of the oldest that has ended. */
    static final int MAX_SESSIONS = 8;

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

    /** Shows and acts on {@code session} from now on; one not kept here is ignored. */
    void focus(DebugSession session) {
        if (sessions.contains(session)) {
            focused = session;
        }
    }

    /** Every session, oldest first. */
    List<DebugSession> all() {
        return List.copyOf(sessions);
    }

    int size() {
        return sessions.size();
    }

    /** Keeps {@code session}, after the others. */
    void add(DebugSession session) {
        sessions.add(session);
    }

    /**
     * Forgets {@code session} and releases its manager; the focus moves to the newest of the others when it was the
     * focused one. The last session is never removed: the window always has one.
     */
    void remove(DebugSession session) {
        if (sessions.size() < 2 || !sessions.remove(session)) {
            return;
        }
        if (focused == session) {
            focused = sessions.getLast();
        }
        session.dap.shutdown();
    }

    /** Ends every session and releases their managers (the window closes). */
    void shutdown() {
        for (DebugSession s : sessions) {
            s.dap.shutdown();
        }
    }
}
