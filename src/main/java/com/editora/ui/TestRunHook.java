package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;

/**
 * The seam by which {@link BuildCoordinator} hands a recognized test run to the Test Results feature
 * ({@code TestRunCoordinator}) without duplicating {@code BuildService}: the same single process keeps running
 * through the coordinator's own service, and its listener callbacks are forwarded here so the test tree can be
 * built from the very same output stream (or, for JVM tools, so the hook can start polling the reports dir).
 *
 * <p>{@link #onTestRunStart} returns whether the hook <em>claimed</em> the run: it declines (returns
 * {@code false}) when the feature is toggled off or suppressed (Simple UI mode), in which case the run behaves
 * exactly as before (no {@code -json} augmentation, no forwarding). When the hook is {@code null}, none of this
 * engages.
 */
interface TestRunHook {

    /**
     * Notifies that a test run is starting. {@code resolvedArgv} is the fully-resolved command (already
     * augmented, e.g. Go's {@code -json}). Returns {@code true} if the hook is active and claimed the run.
     */
    boolean onTestRunStart(
            BuildTool tool, Path workingDir, List<String> taskArgs, List<String> toggleArgs, List<String> resolvedArgv);

    /** A raw output line of a claimed run (forwarded from {@code BuildService.Listener.onOutput}). */
    void onTestOutput(String line, boolean stderr);

    /** The claimed run exited with {@code code}. */
    void onTestExit(int code);

    /** The claimed run failed to launch / errored. */
    void onTestError(String message);

    /**
     * What the raw Build Output console should show for {@code raw} during a claimed run — Go turns its
     * {@code -json} events back into readable text (or {@code null} to suppress a bookkeeping event). Default:
     * the line verbatim.
     */
    default String consoleLine(String raw, boolean stderr) {
        return raw;
    }
}
