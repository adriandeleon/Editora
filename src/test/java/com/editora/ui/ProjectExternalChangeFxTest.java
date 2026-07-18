package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #529: when an external program changes files under the repo while Editora already has focus,
 * the filesystem watcher's coalesced refresh must also fire the {@code onExternalChange} hook (which the window
 * wires to a Git / Commit-stripe / build-marker / diff refresh) — but a change the app made itself (guarded by
 * the self-change window) must not.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectExternalChangeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Fire the watcher's coalesced-refresh handler (the {@code watchDebounce} {@code onFinished}) directly. */
    private static void fireWatchDebounce(ProjectPanel panel) {
        PauseTransition pt = FxTestSupport.field(panel, "watchDebounce");
        pt.getOnFinished().handle(new ActionEvent());
    }

    @Test
    void anExternalChangeFiresTheHookButASelfChangeDoesNot(@TempDir Path root) throws Exception {
        AtomicInteger fired = new AtomicInteger();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false);
            p.setOnExternalChange(fired::incrementAndGet);
            p.setRoot(root);
            return p;
        });

        // An external filesystem change → the hook fires (Git/Commit/build/diffs re-evaluate).
        FxTestSupport.runOnFx(() -> fireWatchDebounce(panel));
        assertEquals(1, fired.get(), "an external change fires the hook");

        // A change the app just made itself (within the self-change window) must NOT fire the hook.
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(panel, "markLocalChange");
            fireWatchDebounce(panel);
        });
        assertEquals(1, fired.get(), "a self-change does not fire the hook");

        FxTestSupport.runOnFx(panel::dispose);
    }
}
