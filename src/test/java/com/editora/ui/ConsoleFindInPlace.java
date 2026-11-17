package com.editora.ui;

import com.editora.perf.OffFx;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Runs a console find bar's search in place and without the typing pause, for a test class that reads the count as
 * the query is set (PERF-01, Pauta 6.0.1, moved the search off the FX thread and made typing wait for a pause).
 * Called on the FX thread, the answer then lands before the call returns. {@code HandlersConsoleOffFxThreadFxTest}
 * drives the bar the way the app runs it.
 */
public final class ConsoleFindInPlace implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        OffFx.runnerForTest((name, task) -> task.run());
        ConsoleFindBar.typingPauseForTest(0);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        OffFx.runnerForTest(null);
        ConsoleFindBar.typingPauseForTest(ConsoleFindBar.TYPING_PAUSE_MS);
    }
}
