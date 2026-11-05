package com.editora.ui;

import com.editora.dap.DapManager;

/**
 * What a request to debug a file does, given the launch there is. Pure (unit-tested).
 *
 * <p>Every way to start goes through this one check (review 3, C202–C204, A208, A209): F5 and the green button
 * ({@link #of}), and Debug File in the editor menu, the Debug File handler and Debug Test ({@link #forFile}). A
 * launch still getting ready counts as a live one: a jdtls build before it, a before-launch step, a test run
 * waiting for its JVM, or one the manager is starting — compiling, waiting for jdtls, or showing the main-class
 * picker. F5 in another tab used to replace such a launch without asking, and its picker stayed open over the next
 * session (review A106); the editor menu's Debug File ended a paused session without asking (review 3, C202).
 */
enum DebugStartChoice {
    /** No launch is live: launch the file. */
    LAUNCH,
    /** The session is paused (or mid-step): F5 continues it. */
    CONTINUE,
    /** The launch is the file's own, or the file cannot be debugged: say it is already being debugged. */
    ALREADY_RUNNING,
    /** Another debuggable file: ask before ending the live launch for it. */
    ASK_RETARGET;

    /**
     * F5 / Debug ▶ for the active file.
     *
     * @param active the manager has a connected session
     * @param state the manager's state
     * @param stepping a step is under way
     * @param preparing a launch is getting ready with no session yet (a build, a before-launch step, a test run)
     * @param differentFile the active file is not the one the launch is for
     * @param debuggable the active file can be debugged
     */
    static DebugStartChoice of(
            boolean active,
            DapManager.State state,
            boolean stepping,
            boolean preparing,
            boolean differentFile,
            boolean debuggable) {
        if (!live(active, state, preparing)) {
            return LAUNCH;
        }
        if (active && (state == DapManager.State.SUSPENDED || stepping)) {
            return CONTINUE;
        }
        return differentFile && debuggable ? ASK_RETARGET : ALREADY_RUNNING;
    }

    /**
     * Debug File or Debug Test for a file the user named: it never continues a paused session — the user asked for
     * this file — so a live launch of another file is replaced after asking, and the file's own is left alone.
     */
    static DebugStartChoice forFile(boolean active, DapManager.State state, boolean preparing, boolean differentFile) {
        if (!live(active, state, preparing)) {
            return LAUNCH;
        }
        return differentFile ? ASK_RETARGET : ALREADY_RUNNING;
    }

    private static boolean live(boolean active, DapManager.State state, boolean preparing) {
        return active || preparing || state == DapManager.State.STARTING;
    }

    /**
     * How far the live launch has got, which decides the wording: "is running" was said of a launch still being
     * built or showing its main-class picker (review 3, A209, C204).
     */
    enum Phase {
        STARTING("status.debug.alreadyStarting", "dialog.debug.retargetStarting"),
        RUNNING("status.debug.alreadyRunning", "dialog.debug.retargetContent"),
        PAUSED("status.debug.alreadyPaused", "dialog.debug.retargetPaused");

        /** The status line for {@link #ALREADY_RUNNING}: {0} is the live launch's file. */
        final String busyKey;

        /** The question for {@link #ASK_RETARGET}: {0} is the live launch's file, {1} the one asked for. */
        final String retargetKey;

        Phase(String busyKey, String retargetKey) {
            this.busyKey = busyKey;
            this.retargetKey = retargetKey;
        }

        static Phase of(boolean active, DapManager.State state, boolean stepping, boolean preparing) {
            if (preparing || !active || state == DapManager.State.STARTING) {
                return STARTING;
            }
            return state == DapManager.State.SUSPENDED || stepping ? PAUSED : RUNNING;
        }
    }
}
