package com.editora.ui;

import com.editora.dap.DapManager;

/**
 * What Debug ▸ Start (F5, the green button) does, given the session there is. Pure (unit-tested).
 *
 * <p>A launch still getting ready counts as a session: a jdtls build before it, or one the manager is starting
 * — compiling, waiting for jdtls, or showing the main-class picker. F5 in another tab used to replace such a
 * launch without asking, and its picker stayed open over the next session (review A106).
 */
enum DebugStartChoice {
    /** No session: launch the active file. */
    LAUNCH,
    /** The session is paused (or mid-step): F5 continues it. */
    CONTINUE,
    /** The session is the active file's, or the file cannot be debugged: say it is already running. */
    ALREADY_RUNNING,
    /** Another debuggable file: ask before ending the session for it. */
    ASK_RETARGET;

    /**
     * @param active the manager has a connected session
     * @param state the manager's state
     * @param stepping a step is under way
     * @param preparing a jdtls build before a launch is running
     * @param differentFile the active file is not the one the session is for
     * @param debuggable the active file can be debugged
     */
    static DebugStartChoice of(
            boolean active,
            DapManager.State state,
            boolean stepping,
            boolean preparing,
            boolean differentFile,
            boolean debuggable) {
        boolean live = active || preparing || state == DapManager.State.STARTING;
        if (!live) {
            return LAUNCH;
        }
        if (active && (state == DapManager.State.SUSPENDED || stepping)) {
            return CONTINUE;
        }
        return differentFile && debuggable ? ASK_RETARGET : ALREADY_RUNNING;
    }
}
