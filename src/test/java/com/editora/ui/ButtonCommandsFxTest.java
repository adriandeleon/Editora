package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands behind buttons that only the mouse reached ({@link MapButtonCommands},
 * {@link DiffGitButtonCommands}, {@link DebugBuildTestButtonCommands}, {@link PanelViewerButtonCommands}): each
 * is registered in a window with a title and a description, says why when it has nothing to act on, and the
 * content zoom zooms an image where a text zoom would do nothing anyone can see.
 */
@Tag("fx")
class ButtonCommandsFxTest {

    private static final List<String> IDS = List.of(
            "projectMap.back",
            "projectMap.forward",
            "projectMap.history",
            "projectMap.help",
            "projectMap.options",
            "projectMap.cardPreviousDeclaration",
            "projectMap.cardNextDeclaration",
            "projectMap.cardZoomOut",
            "projectMap.cardZoomIn",
            "projectMap.cardPin",
            "projectMap.cardStructure",
            "projectMap.cardNotes",
            "projectMap.cardClose",
            "projectMap.hierarchyFlip",
            "projectMap.trailNoteHide",
            "diff.undoApplied",
            "diff.saveResult",
            "diff.exportPatch",
            "diff.exitDiffMode",
            "git.log.showBranchLog",
            "git.log.endSearch",
            "git.log.refresh",
            "github.refreshList",
            "history.backToFile",
            "debug.watchEvaluated",
            "debug.closeEndedSession",
            "debug.memoryPreviousPage",
            "debug.memoryNextPage",
            "gradle.loadAllTasks",
            "sbt.loadAllTasks",
            "mill.loadAllTasks",
            "test.toggleFollow",
            "test.togglePassed",
            "test.toggleFailed",
            "test.toggleSkipped",
            "agent.toggleDetached",
            "structure.filterKinds",
            "bookmarks.toggleAllProjects",
            "notes.toggleAllProjects",
            "markdownLint.toggleBySeverity",
            "viewer.previousPage",
            "viewer.nextPage",
            "viewer.fit",
            "project.deleteChosen");

    private static FxWindowFixture fx;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static String echo() {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(statusBar, "echo").getText();
    }

    @Test
    void everyButtonCommandIsRegisteredWithATitleAndADescription() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (String id : IDS) {
                assertTrue(fx.controller.registry.get(id).isPresent(), id + " is registered");
                assertTrue(Messages.keys().contains("command." + id), id + " has a title");
                assertTrue(Messages.keys().contains("command." + id + ".desc"), id + " has a description");
            }
        });
    }

    @Test
    void aCommandWithNothingToActOnSaysWhy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // No Map on screen: every Map command says so, and none opens it behind the user's back.
            for (String id : List.of(
                    "projectMap.back", "projectMap.history", "projectMap.cardClose", "projectMap.trailNoteHide")) {
                fx.controller.setStatus("");
                fx.controller.registry.run(id);
                assertEquals(tr("status.projectMap.notShowing"), echo(), id);
            }
            // The active tab is not a PDF or an image.
            fx.controller.registry.run("viewer.nextPage");
            assertEquals(tr("status.viewer.notPdf"), echo());
            fx.controller.registry.run("viewer.fit");
            assertEquals(tr("status.viewer.notViewer"), echo());
            // Debugging is off in a fresh window: the debug commands say that, as every debug command does.
            fx.controller.registry.run("debug.memoryNextPage");
            assertEquals(tr("statusbar.tip.debugDisabled"), echo());
            // On, with no debug session: no ended one to close and no memory view to page.
            fx.shared.getSettings().setDebugSupport(true);
            fx.controller.registry.run("debug.closeEndedSession");
            assertEquals(tr("status.debug.noEndedSession"), echo());
            fx.controller.registry.run("debug.memoryNextPage");
            assertEquals(tr("status.debug.memoryNotShown"), echo());
            fx.shared.getSettings().setDebugSupport(false);
        });
    }

    @Test
    void theContentZoomZoomsAnImageAndLeavesTheTextZoomAlone(@TempDir Path dir) throws Exception {
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Path file = dir.resolve("dot.png");
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
        Tab tab = FxTestSupport.callOnFx(() -> fx.controller.fileWorkflows.openImageTab(file, true));
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> MainController.imagePaneOf(tab));
        assertNotNull(pane);
        pane.loadedForTest().get(30, TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {
            double textZoom = fx.shared.getSettings().getFontZoom();
            fx.controller.registry.run("view.textZoomReset"); // Actual Size: out of fit-to-window, at 100%
            assertEquals(1.0, FxTestSupport.<Double>field(pane, "zoom"), 1e-9);
            assertFalse(FxTestSupport.<Boolean>field(pane, "fitMode"));
            fx.controller.registry.run("view.textZoomIn");
            double in = FxTestSupport.<Double>field(pane, "zoom");
            assertTrue(in > 1.0, "the image zoomed in: " + in);
            fx.controller.registry.run("view.textZoomOut");
            fx.controller.registry.run("view.textZoomOut");
            assertTrue(FxTestSupport.<Double>field(pane, "zoom") < 1.0, "and out");
            fx.controller.registry.run("viewer.fit");
            assertTrue(FxTestSupport.<Boolean>field(pane, "fitMode"), "Fit goes back to fit-to-window");
            assertEquals(textZoom, fx.shared.getSettings().getFontZoom(), 1e-9, "the text zoom did not move");
            fx.controller.closeTab(tab);
        });
    }

    @Test
    void theMapsHistoryListHasACommandSoBackAndForwardAreNotTabStops() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            assertFalse(view.showHistoryMenu(), "a Map that has been nowhere has no list to show");
            assertFalse(FxTestSupport.<Button>field(view, "backButton").isFocusTraversable());
            assertFalse(FxTestSupport.<Button>field(view, "forwardButton").isFocusTraversable());
        });
    }
}
