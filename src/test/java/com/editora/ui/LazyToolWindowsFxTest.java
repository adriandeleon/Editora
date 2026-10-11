package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool window's panel is built on its first open, not when the window is registered: a fresh window with
 * every tool window closed has built none of their panels before first paint, and each one still works —
 * builds, attaches, and shows the state the window gathered while it was closed — when opened later.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LazyToolWindowsFxTest {

    /**
     * Built with the window on purpose: the shared Output console is an empty TabPane (its per-tool tabs are
     * made when a tool first runs) that every build coordinator and the Git transcript write to from the
     * first command on, closed or not.
     */
    private static final java.util.Set<String> EAGER = java.util.Set.of("buildOutput");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Uncaught FX-thread failures while a test drives the panels — a deferred {@code runLater} that NPEs. */
    private static List<Throwable> watchFxThread() throws Exception {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> Thread.currentThread().setUncaughtExceptionHandler((t, e) -> failures.add(e)));
        return failures;
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            FxTestSupport.runOnFx(() -> {}); // let queued runLater work (focusFirstItem and friends) run
        }
    }

    @Test
    void noClosedToolWindowBuildsItsPanelBeforeFirstPaint() throws Exception {
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            ToolWindowManager manager = FxTestSupport.field(fx.controller, "toolWindows");
            List<String> builtWhileClosed = new ArrayList<>();
            for (ToolWindow tw : manager.getRegisteredToolWindows()) {
                if (EAGER.contains(tw.getId())) {
                    continue;
                }
                if (!manager.isOpen(tw) && tw.contentIfBuilt() != null) {
                    builtWhileClosed.add(tw.getId());
                }
            }
            assertTrue(builtWhileClosed.isEmpty(), "panels built although their window is closed: " + builtWhileClosed);
            // The window registers the whole set; a lazily built one must not have been lost on the way.
            assertTrue(
                    manager.getRegisteredToolWindows().size() >= 20,
                    "registered: " + manager.getRegisteredToolWindows().size());
        }
    }

    @Test
    void everyToolWindowBuildsAndShowsItsPanelWhenOpened() throws Exception {
        List<Throwable> failures = watchFxThread();
        Path dir = Files.createTempDirectory("editora-lazy-tw");
        Path file = dir.resolve("Note.md");
        Files.writeString(file, "# Title\n\nSome prose with a TODO: later.\n");
        try (FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, -1, -1)), true, c -> {})) {
            ToolWindowManager manager = FxTestSupport.field(fx.controller, "toolWindows");
            for (ToolWindow tw : List.copyOf(manager.getRegisteredToolWindows())) {
                if (manager.isOpen(tw)) {
                    continue;
                }
                boolean available = FxTestSupport.callOnFx(() -> {
                    manager.setAvailable(tw, true); // a context-gated window (Run, Debug, Git Log) opens too
                    manager.open(tw, false);
                    return manager.isOpen(tw);
                });
                settle();
                assertTrue(available, tw.getId() + " did not open");
                Region content = tw.contentIfBuilt();
                assertNotNull(content, tw.getId() + " opened without building its panel");
                assertSame(content, tw.getContent(), tw.getId() + " must build its panel once");
                assertNotNull(content.getScene(), tw.getId() + "'s panel is not in the scene");
                FxTestSupport.runOnFx(() -> manager.close(tw));
                settle();
                assertSame(content, tw.contentIfBuilt(), tw.getId() + " must keep its panel across a close");
            }
            assertTrue(failures.isEmpty(), "FX-thread failures: " + failures);
            // The buffer-attached panels picked up the file that was open before they existed.
            FileInformationPanel info = FxTestSupport.field(fx.controller, "fileInfoPanel");
            StructurePanel structure = FxTestSupport.field(fx.controller, "structurePanel");
            UndoHistoryPanel undo = FxTestSupport.field(fx.controller, "undoHistoryPanel");
            assertNotNull(info);
            assertNotNull(structure);
            assertNotNull(undo);
        }
    }

    @Test
    void stateGatheredWhileClosedReachesThePanelWhenItIsBuilt() throws Exception {
        List<Throwable> failures = watchFxThread();
        Path root = Files.createTempDirectory("editora-lazy-root");
        Files.writeString(root.resolve("a.txt"), "a\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            ToolWindowManager manager = FxTestSupport.field(c, "toolWindows");
            ToolWindow project = FxTestSupport.field(c, "projectToolWindow");
            assertNull(project.contentIfBuilt(), "the Project panel is built on its window's first open");

            // The root is set long before the window opens (setWindowContext does this for a project window).
            FxTestSupport.runOnFx(() -> FxTestSupport.call(c, "setProjectRoot", new Class<?>[] {Path.class}, root));
            assertNull(project.contentIfBuilt(), "setting the root must not build the panel");
            FxTestSupport.runOnFx(() -> manager.open(project, false));
            settle();
            ProjectPanel panel = (ProjectPanel) project.contentIfBuilt();
            assertNotNull(panel);
            assertEquals(root, panel.getRoot(), "the root set while closed is what the built panel shows");

            // A later root change reaches the built panel directly.
            Path other = Files.createTempDirectory("editora-lazy-root-2");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(c, "setProjectRoot", new Class<?>[] {Path.class}, other));
            assertEquals(other, panel.getRoot());

            // The Commit panel shows the Git state the window saw while it was closed.
            ToolWindow commit = FxTestSupport.field(c, "commitToolWindow");
            assertNull(commit.contentIfBuilt());
            // What the controller recorded while the window was closed (the AI coordinator's host pushes it).
            java.lang.reflect.Field recorded = MainController.class.getDeclaredField("gitAiAvailable");
            recorded.setAccessible(true);
            recorded.setBoolean(c, true);
            FxTestSupport.runOnFx(() -> manager.open(commit, false));
            settle();
            GitPanel git = (GitPanel) commit.contentIfBuilt();
            assertNotNull(git);
            javafx.scene.Node aiButton = FxTestSupport.field(git, "aiCommitButton");
            assertTrue(aiButton.isVisible(), "the availability recorded while closed is applied at build");
            assertTrue(failures.isEmpty(), "FX-thread failures: " + failures);
        }
    }
}
