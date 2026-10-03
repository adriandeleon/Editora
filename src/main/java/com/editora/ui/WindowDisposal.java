package com.editora.ui;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a window's shutdown steps so that each one is attempted.
 *
 * <p>{@code MainController.disposeWindow} releases some thirty independent owners in sequence — language
 * servers, the debug session, worker threads, the project watcher. Written as plain statements, the first one
 * to throw skipped every step after it, so a failure in (say) the git shutdown left the SFTP sessions, the
 * autosave thread and the file watcher running for the rest of the process.
 */
final class WindowDisposal {

    private static final Logger LOG = Logger.getLogger(WindowDisposal.class.getName());

    private WindowDisposal() {}

    /**
     * Runs every step in order. A step that throws is logged and the rest still run; a {@code null} step (an
     * owner that was never created) is skipped.
     *
     * @return how many steps failed
     */
    static int runAll(Runnable... steps) {
        int failed = 0;
        for (Runnable step : steps) {
            if (step == null) {
                continue;
            }
            try {
                step.run();
            } catch (RuntimeException | LinkageError e) {
                failed++;
                LOG.log(Level.WARNING, "A window shutdown step failed; continuing with the rest", e);
            }
        }
        return failed;
    }
}
