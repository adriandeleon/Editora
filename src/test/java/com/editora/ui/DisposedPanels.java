package com.editora.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disposes every {@link ProjectPanel} a test made, after the test. A panel with a root holds a file watcher — on
 * Linux an inotify instance, of which a user has 128 across every program — and its thread until disposed.
 * Eight test classes never disposed theirs, so one surefire fork kept them all for the whole run (72 inotify
 * instances were counted in one fork), and with a few verifies at once the user's limit was reached: then no
 * watcher could be opened and the watcher tests failed.
 */
final class DisposedPanels implements AfterEachCallback {

    private static final List<ProjectPanel> MADE = new CopyOnWriteArrayList<>();

    /** Registers {@code panel} for disposal after the current test; returns it. */
    static ProjectPanel track(ProjectPanel panel) {
        MADE.add(panel);
        return panel;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        List<ProjectPanel> panels = List.copyOf(MADE);
        MADE.clear();
        if (!panels.isEmpty()) {
            FxTestSupport.runOnFx(() -> panels.forEach(ProjectPanel::dispose));
        }
    }
}
