package com.editora.ui;

import com.editora.perf.OffFx;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs the disk work a command hands to {@link OffFx} in place, for a test class that reads a command's result as the
 * command returns (PERF-01, Pauta 6.0.1, moved that work off the FX thread). Called on the FX thread, the work and its
 * answer then land before the call returns, as they did before. {@code FilesOffFxThreadFxTest} runs the same commands
 * where the app runs them.
 */
public final class OffFxInPlace implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        OffFx.runnerForTest((name, task) -> task.run());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        OffFx.runnerForTest(null);
    }
}
