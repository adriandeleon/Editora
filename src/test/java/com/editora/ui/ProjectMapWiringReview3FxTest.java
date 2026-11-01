package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.layout.Region;

import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 3: the workspace keeps the review's ⋯ options (Compact rows, Hide ignored and generated) and
 * whether the Overview card was seen, through {@link ProjectPanel#setRememberedMapState}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapWiringReview3FxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theOptionsComeBackFromTheWorkspaceAndAChangeIsWrittenBack(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapCompactRows(true);
            state.setProjectMapOverviewSeen(true);
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, saves::incrementAndGet));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            FxTestSupport.runOnFx(() -> {
                assertTrue(view.isCompactRows(), "Compact rows comes back on");
                assertFalse(view.isHideIgnored());
                assertTrue(view.isOverviewSeen());
            });
            int before = saves.get();
            FxTestSupport.runOnFx(() -> {
                view.setCompactRows(false);
                view.setHideIgnored(true);
            });
            r.await("both changes are saved", () -> saves.get() >= before + 2);
            assertFalse(state.isProjectMapCompactRows());
            assertTrue(state.isProjectMapHideIgnored());
        }
    }

    @Test
    void aWorkspaceThatNeverSawTheOverviewOpensItOnceAndRemembersThat(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project", () ->
                    (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, r.beta));
            r.await("the Overview card opens", () -> view.overviewCard() != null);
            assertTrue(state.isProjectMapOverviewSeen(), "the workspace remembers it was seen");
        }
    }

    @Test
    void aWorkspaceThatSawTheOverviewDoesNotOpenItAgain(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapOverviewSeen(true);
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project", () ->
                    (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, r.beta));
            FxTestSupport.runOnFx(() -> assertNull(view.overviewCard(), "no Overview card the second time"));
        }
    }
}
