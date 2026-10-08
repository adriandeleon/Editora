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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project panel with the Map as its view: what the workspace remembers of it, the shared filter field
 * searching the map, the tree's row actions reaching the map's selection, and the panel's own entry points
 * (reveal, refresh, print) going to the map instead of the hidden tree.
 */
@Tag("fx")
class ProjectPanelMapModeFxTest {

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

    /** Switches the panel to the Map and waits until it lists the project. */
    private static void openMap(ProjectPanelRig r) throws Exception {
        FxTestSupport.runOnFx(r.panel::toggleMapView);
        r.await("the map to list the project", () -> shows(r, r.beta));
    }

    @Test
    void theWorkspaceRemembersTheViewTheFlowAndTheNavigationOptions(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectViewMode(WorkspaceState.PROJECT_VIEW_MAP);
            state.setProjectMapFlow("TOP_TO_BOTTOM");
            state.setProjectMapKeepZoom(true);
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                r.panel.setRememberedMapState(() -> state, saves::incrementAndGet);

                assertTrue(r.panel.isMapMode(), "the Map was the view last time");
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

                r.panel.toggleMapView();
                assertFalse(r.panel.isMapMode());
                assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
                assertEquals(3, saves.get());
                r.panel.toggleMapView();
                assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
                assertEquals(4, saves.get());
            });
        }
    }

    @Test
    void aWorkspaceThatRemembersNothingStartsInTheTreeWithTheDefaultFlow(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapFlow("a flow this build has never heard of");
            FxTestSupport.runOnFx(() -> {
                r.panel.setRememberedMapState(() -> state, () -> {});
                assertFalse(r.panel.isMapMode());
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
    void theFilterFieldSearchesTheMapForMatchesAnywhereInTheProject(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            assertFalse(FxTestSupport.callOnFx(() -> shows(r, r.helper)), "two folders down: not on the map yet");

            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the match on the map", () -> shows(r, r.helper));

            FxTestSupport.runOnFx(() -> r.filter.clear());
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
    void revealAndRefreshGoToTheMapWhileItIsTheView(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);

            FxTestSupport.runOnFx(() -> r.panel.revealPath(r.helper));
            r.await("the revealed file to be selected on the map", () -> r.helper.equals(selection(r)));
            assertEquals(null, FxTestSupport.callOnFx(r::selected), "the hidden tree's selection is not what moved");

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
                assertFalse(r.panel.isMapOutputAvailable(), "the Tree has nothing to print");
            });
            openMap(r);
            r.await("the map to have something to print", r.panel::isMapOutputAvailable);

            FxTestSupport.runOnFx(() -> {
                r.panel.printMap();
                r.panel.exportMapPdf();
            });

            assertEquals(1, printed.size());
            assertEquals(1, exported.size());
            FxTestSupport.runOnFx(r.panel::toggleMapView);
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
    void thePanelIsEnteredAtItsFilterAndAPanelWithNoProjectCanStillSwitchViews(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.focusFirstItem();
                assertSame(r.filter, r.stage.getScene().getFocusOwner());

                r.panel.setRoot(null);
                r.panel.toggleMapView();
                assertTrue(r.panel.isMapMode(), "the choice is remembered for when a project opens");
                assertEquals(1, r.panel.getChildren().size(), "still just the 'no project' placeholder");
                r.panel.revealPath(r.alpha); // no project: nothing to reveal in
                r.panel.toggleMapView();
                assertFalse(r.panel.isMapMode());
            });
        }
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }
}
