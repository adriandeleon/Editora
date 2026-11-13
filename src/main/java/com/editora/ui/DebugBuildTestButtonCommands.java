package com.editora.ui;

import com.editora.command.Command;
import com.editora.test.TestFilter.Bucket;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the buttons of the Debug window, the build tools' task trees and the Test Results window
 * that only the mouse reached: each runs the method its button runs, on the session, tree or panel the window
 * shows, and says so on the status line when there is none.
 */
final class DebugBuildTestButtonCommands {

    private DebugBuildTestButtonCommands() {}

    static void register(MainController mc) {
        mc.registry.register(Command.of("debug.watchEvaluated", () -> watchEvaluated(mc)));
        mc.registry.register(Command.of("debug.closeEndedSession", () -> closeEndedSession(mc)));
        mc.registry.register(Command.of("debug.memoryPreviousPage", () -> memoryPage(mc, -1)));
        mc.registry.register(Command.of("debug.memoryNextPage", () -> memoryPage(mc, 1)));
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool().taskLoadLabel().isPresent()) { // Gradle, sbt and Mill: the tools whose tree has the action
                mc.registry.register(Command.of(c.tool().id() + ".loadAllTasks", () -> loadAllTasks(mc, c)));
            }
        }
        mc.registry.register(Command.of("test.toggleFollow", () -> testPanel(mc).toggleFollow()));
        mc.registry.register(Command.of("test.togglePassed", () -> testPanel(mc).toggleBucket(Bucket.PASSED)));
        mc.registry.register(Command.of("test.toggleFailed", () -> testPanel(mc).toggleBucket(Bucket.FAILED)));
        mc.registry.register(
                Command.of("test.toggleSkipped", () -> testPanel(mc).toggleBucket(Bucket.SKIPPED)));
    }

    /** The focused session's evaluate field, in the Debug window, which opens so the new watch is seen. */
    private static void watchEvaluated(MainController mc) {
        DebugCoordinator debug = mc.debugCoordinator;
        debug.ifDebug(() -> {
            mc.toolWindows.open(mc.debugToolWindow, false);
            debug.panel().watchEvaluated();
        });
    }

    /** The session the Debug window shows, when it has ended and is not the window's only one. */
    private static void closeEndedSession(MainController mc) {
        DebugCoordinator debug = mc.debugCoordinator;
        debug.ifDebug(() -> {
            // One session has no strip and so no ×: asked first, so that saying so builds no view.
            if (debug.sessions().size() < 2 || !debug.view().closeShownEnded()) {
                mc.setStatus(tr("status.debug.noEndedSession"));
            }
        });
    }

    /** The Memory view's page before ({@code -1}) or after ({@code +1}), where one is showing. */
    private static void memoryPage(MainController mc, int direction) {
        DebugCoordinator debug = mc.debugCoordinator;
        debug.ifDebug(() -> {
            DebugMemoryView memory = shownMemoryView(mc);
            if (memory == null) {
                mc.setStatus(tr("status.debug.memoryNotShown"));
            } else {
                memory.page(direction);
            }
        });
    }

    /** The focused session's Memory view when the Debug window is open on it, else null. */
    private static @Nullable DebugMemoryView shownMemoryView(MainController mc) {
        DebugPanel panel = mc.debugCoordinator.sessions().focused().panel;
        if (panel == null
                || !mc.toolWindows.isOpen(mc.debugToolWindow)
                || !DebugPanel.AREA_MEMORY.equals(panel.shownArea())) {
            return null;
        }
        return panel.memoryView();
    }

    /** The tool's task tree's "Load all tasks…", for the project it has detected. */
    private static void loadAllTasks(MainController mc, BuildCoordinator c) {
        if (!c.isDetected() || !c.tasksPanel().runSecondaryAction()) {
            mc.setStatus(tr("status.build.notDetected", c.tool().displayName()));
        }
    }

    /** The Test Results panel, its window opened as the other {@code test.*} filter commands open it. */
    private static TestRunnerPanel testPanel(MainController mc) {
        if (mc.testResultsToolWindow != null) {
            mc.toolWindows.open(mc.testResultsToolWindow, false);
        }
        return mc.testRunCoordinator.panel();
    }
}
