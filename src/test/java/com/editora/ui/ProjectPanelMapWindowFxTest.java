package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map in its own window: it never shows inside the Project panel, which keeps the tree; what the
 * workspace remembers of it; its own search field; the tree's row actions reaching the map's selection; and the
 * panel's entry points (refresh, print) reaching the map while its window is open.
 */
@Tag("fx")
class ProjectPanelMapWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static ProjectMapView map(ProjectPanelRig r) {
        return FxTestSupport.field(r.panel, "mapView");
    }

    private static Region surface(ProjectPanelRig r) {
        return FxTestSupport.field(map(r), "surface");
    }

    private static boolean shows(ProjectPanelRig r, Path path) {
        return (boolean) FxTestSupport.call(surface(r), "contains", new Class<?>[] {Path.class}, path);
    }

    @SuppressWarnings("unchecked")
    private static Path selection(ProjectPanelRig r) {
        return ((Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface(r), "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
    }

    private static void select(ProjectPanelRig r, Path path) {
        FxTestSupport.call(surface(r), "select", new Class<?>[] {Path.class}, path);
    }

    /** Opens the Map's window and waits until it lists the project. */
    private static void openMap(ProjectPanelRig r) throws Exception {
        FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
        r.await("the map to list the project", () -> shows(r, r.beta));
    }

    @Test
    void theMapButtonOpensTheMapInItsOwnWindowAndThePanelKeepsTheTree(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            javafx.scene.control.Button button = FxTestSupport.field(r.panel, "mapButton");
            FxTestSupport.runOnFx(button::fire);
            r.await("the map to list the project", () -> shows(r, r.beta));

            FxTestSupport.runOnFx(() -> {
                javafx.stage.Stage window = r.panel.mapWindow().stage();
                assertTrue(window != null && window.isShowing());
                assertSame(window.getScene(), map(r).getScene(), "the Map is in its own window's scene");
                assertFalse(window.getScene() == r.stage.getScene());
                assertFalse(r.panel.getChildren().contains(map(r)), "never inside the Project panel");
                assertTrue(r.panel.getChildren().contains(r.tree), "the panel goes on showing the tree");
                assertNull(window.getOwner(), "not owned, so the editor window can come in front of it");
                assertEquals(tr("project.map.windowTitle", dir.getFileName().toString()), window.getTitle());
                assertEquals(
                        r.stage.getScene().getStylesheets(),
                        window.getScene().getStylesheets(),
                        "styled as the editor window is");

                button.fire(); // already open: brought forward, not a second window
                assertSame(window, r.panel.mapWindow().stage());
                assertTrue(window.isShowing());
            });

            // The tree goes on following the disk while the Map is open, as the Map does.
            Path added = Files.writeString(dir.resolve("added.txt"), "new\n");
            FxTestSupport.runOnFx(r.panel::refreshTree);
            r.await("the new file on the map", () -> shows(r, added));
            r.await("the new file in the tree", () -> r.rows().contains("added.txt"));
        }
    }

    @Test
    void theMapWindowClosesWithTheEditorWindowAndWithTheProjectButStaysRemembered(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, saves::incrementAndGet));
            openMap(r);
            assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode(), "opening it is remembered");
            assertEquals(
                    4,
                    FxTestSupport.callOnFx(
                            () -> state.getProjectMapWindowBounds().size()),
                    "so is where");

            FxTestSupport.runOnFx(() -> r.panel.setRoot(null));
            assertFalse(FxTestSupport.callOnFx(r.panel::isMapShowing), "no project, nothing to map");
            assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode(), "not the user closing it");

            FxTestSupport.runOnFx(() -> r.panel.setRoot(r.root));
            openMap(r);
            FxTestSupport.runOnFx(() -> r.stage.hide());
            assertFalse(FxTestSupport.callOnFx(r.panel::isMapShowing), "the editor window took its Map with it");
            assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode(), "to reopen next time");

            FxTestSupport.runOnFx(() -> {
                r.stage.show();
                r.panel.openMapWindow(false);
                r.panel.mapWindow().closeByUser(); // what its close button does
                assertFalse(r.panel.isMapShowing());
                assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
            });
        }
    }

    @Test
    void theWorkspaceRemembersTheWindowTheFlowAndTheNavigationOptions(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectViewMode(WorkspaceState.PROJECT_VIEW_MAP);
            state.setProjectMapFlow("TOP_TO_BOTTOM");
            state.setProjectMapKeepZoom(true);
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                r.panel.setRememberedMapState(() -> state, saves::incrementAndGet);

                ComboBox<ProjectMapView.FlowDirection> flow = FxTestSupport.field(map(r), "flowFilter");
                CheckMenuItem keepZoom = FxTestSupport.field(map(r), "keepZoomOnOpen");
                assertEquals(ProjectMapView.FlowDirection.TOP_TO_BOTTOM, flow.getValue());
                assertTrue(keepZoom.isSelected());
                assertEquals(0, saves.get(), "restoring what was stored is not a change to store");

                // What picking an entry from the dropdown does: the value changes and the control fires its action.
                flow.getSelectionModel().select(ProjectMapView.FlowDirection.RIGHT_TO_LEFT);
                flow.fireEvent(new javafx.event.ActionEvent());
                assertEquals("RIGHT_TO_LEFT", state.getProjectMapFlow());
                assertEquals(1, saves.get());

                // Likewise for a tick in the options menu.
                keepZoom.setSelected(false);
                keepZoom.fire();
                assertFalse(state.isProjectMapKeepZoom());
                assertEquals(2, saves.get());
            });
            r.await("the Map window, open last time, to open again", r.panel::isMapShowing);
            FxTestSupport.runOnFx(() -> {
                assertEquals(2, saves.get(), "reopening what was open is not a change to store");
                r.panel.mapWindow().closeByUser();
                assertFalse(r.panel.isMapShowing());
                assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
                assertEquals(3, saves.get());
                r.panel.openMapWindow(true);
                assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
                assertEquals(4, saves.get());
            });
        }
    }

    @Test
    void aWorkspaceThatRemembersNothingOpensNoMapWindowAndHasTheDefaultFlow(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapFlow("a flow this build has never heard of");
            FxTestSupport.runOnFx(() -> {
                r.panel.setRememberedMapState(() -> state, () -> {});
                assertFalse(r.panel.isMapShowing());
                ComboBox<ProjectMapView.FlowDirection> flow = FxTestSupport.field(map(r), "flowFilter");
                assertEquals(ProjectMapView.DEFAULT_FLOW, flow.getValue());

                // With nobody to tell, a flow change is simply applied.
                r.panel.setRememberedMapFlow(null, null);
                flow.setValue(ProjectMapView.FlowDirection.BOTTOM_TO_TOP);
                assertEquals(ProjectMapView.FlowDirection.BOTTOM_TO_TOP, flow.getValue());
            });
        }
    }

    @Test
    void theNewerMapOptionsAreRestoredAndRememberedPerWorkspace(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapSingleClickOpens(false);
            state.setProjectMapCompactChains(false);
            state.setProjectMapFollowActiveFile(true);
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                r.panel.setRememberedMapState(() -> state, saves::incrementAndGet);
                ProjectMapView view = map(r);
                assertFalse(view.isSingleClickOpens());
                assertFalse(view.isCompactChains());
                assertTrue(view.isFollowActiveFile());
                assertEquals(0, saves.get(), "restoring what was stored is not a change to store");

                view.setSingleClickOpens(true);
                view.setCompactChains(true);
                view.setFollowActiveFile(false);
                assertTrue(state.isProjectMapSingleClickOpens());
                assertTrue(state.isProjectMapCompactChains());
                assertFalse(state.isProjectMapFollowActiveFile());
                assertEquals(3, saves.get());
            });
        }
    }

    @Test
    void theMapWindowsOwnFieldSearchesTheMapForMatchesAnywhereInTheProject(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            assertFalse(FxTestSupport.callOnFx(() -> shows(r, r.helper)), "two folders down: not on the map yet");
            assertSame(
                    r.panel.mapWindow().stage().getScene(),
                    FxTestSupport.callOnFx(() -> r.mapFilter.getScene()),
                    "the field is in the Map's window");

            FxTestSupport.runOnFx(() -> r.mapFilter.setText("helper"));
            r.await("the match on the map", () -> shows(r, r.helper));
            assertEquals("", FxTestSupport.callOnFx(() -> r.filter.getText()), "the tree's filter is its own");

            FxTestSupport.runOnFx(() -> r.mapFilter.clear());
            r.await("the whole project again", () -> shows(r, r.alpha) && shows(r, r.beta));
        }
    }

    @Test
    void f2AndDeleteOnTheMapsSelectionRunTheTreesRowActions(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            FxTestSupport.runOnFx(() -> {
                select(r, r.root);
                javafx.event.Event.fireEvent(surface(r), key(KeyCode.F2));
                assertTrue(r.prompts.isEmpty(), "the project folder is not renamed from the map either");

                select(r, r.alpha);
                javafx.event.Event.fireEvent(surface(r), key(KeyCode.F2));
                assertEquals(List.of(tr("project.renameTitle") + "|alpha.txt"), r.prompts);
            });

            FxTestSupport.runOnFx(() -> {
                select(r, r.beta);
                javafx.event.Event.fireEvent(surface(r), key(KeyCode.DELETE));
            });
            assertEquals(List.of(tr("project.deleteFileBody", "beta.txt")), List.copyOf(r.dialogs));
            assertFalse(Files.exists(r.beta));
            assertEquals(List.of(r.beta), r.deletedCallbacks);
            r.await("the deleted file to leave the map", () -> !shows(r, r.beta));

            // Renaming from the map: the map keeps showing the file under its new name.
            Path renamed = dir.resolve("gamma.txt");
            FxTestSupport.runOnFx(() -> r.promptAccept.accept("gamma.txt"));
            assertTrue(Files.exists(renamed));
            r.await("the renamed file on the map", () -> shows(r, renamed) && !shows(r, r.alpha));
        }
    }

    @Test
    void revealGoesToTheTreeAndRefreshReachesTheOpenMap(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);

            FxTestSupport.runOnFx(() -> r.panel.revealPath(r.helper));
            r.await("the revealed file to be selected in the tree", () -> r.helper.equals(r.selected()));
            assertNotEquals(r.helper, FxTestSupport.callOnFx(() -> selection(r)), "the Map's selection is its own");

            Path added = Files.writeString(dir.resolve("added.txt"), "new\n");
            int before = FxTestSupport.callOnFx(() -> r.panel.treeRefreshCountForTest);
            FxTestSupport.runOnFx(r.panel::refreshTree);
            r.await("the new file on the map", () -> shows(r, added));
            assertEquals(before + 1, (int) FxTestSupport.callOnFx(() -> r.panel.treeRefreshCountForTest));
        }
    }

    @Test
    void printAndPdfHandTheMapToTheWindowOnlyWhileTheMapIsOnScreen(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            List<ProjectMapOutput> printed = new ArrayList<>();
            List<ProjectMapOutput> exported = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.panel.setMapOutputActions(printed::add, exported::add); // recorded: nothing reaches a printer
                assertFalse(r.panel.isMapOutputAvailable(), "no Map window, nothing to print");
            });
            openMap(r);
            r.await("the map to have something to print", r.panel::isMapOutputAvailable);

            FxTestSupport.runOnFx(() -> {
                r.panel.printMap();
                r.panel.exportMapPdf();
            });

            assertEquals(1, printed.size());
            assertEquals(1, exported.size());
            FxTestSupport.runOnFx(() -> r.panel.mapWindow().closeByUser());
            assertFalse(FxTestSupport.callOnFx(r.panel::isMapOutputAvailable));
        }
    }

    @Test
    void hiddenFilesAppearInTheTreeOnlyWhenAskedFor(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.setShowHidden(false); // already off: nothing is rebuilt
                r.panel.setShowHidden(true);
            });
            r.awaitChildren(r.root, 5);
            assertEquals(
                    List.of(dir.getFileName().toString(), "docs", "src", ".hidden", "alpha.txt", "beta.txt"),
                    FxTestSupport.callOnFx(r::rows));

            FxTestSupport.runOnFx(() -> r.panel.setShowHidden(false));
            r.awaitChildren(r.root, 4);
        }
    }

    @Test
    void thePanelIsEnteredAtItsFilterAndAPanelWithNoProjectOpensNoMap(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.focusFirstItem();
                assertSame(r.filter, r.stage.getScene().getFocusOwner());

                r.panel.setRoot(null);
                r.panel.toggleMapView();
                assertFalse(r.panel.isMapShowing(), "no project, nothing to map");
                assertEquals(1, r.panel.getChildren().size(), "still just the 'no project' placeholder");
                r.panel.revealPath(r.alpha); // no project: nothing to reveal in
            });
        }
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}
