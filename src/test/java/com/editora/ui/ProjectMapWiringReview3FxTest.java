package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.Button;
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
 * Project Map review 3: the workspace keeps the review's ⋯ options (Compact rows, Hide ignored and generated) through
 * {@link ProjectPanel#setRememberedMapState}, which also has the Overview card open on the session's first Map open.
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
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, saves::incrementAndGet));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            FxTestSupport.runOnFx(() -> {
                assertTrue(view.isCompactRows(), "Compact rows comes back on");
                assertFalse(view.isHideIgnored());
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

    /**
     * The Overview card opens on the session's first Map open, whatever the workspace once stored about it, and not
     * again when the window is closed and opened again in the same session.
     */
    @Test
    void theOverviewOpensOnTheSessionsFirstMapOpenAndNotWhenTheWindowOpensAgain(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapOverviewSeen(true); // what a workspace of 0.27 stored: no longer read
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project", () ->
                    (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, r.beta));
            r.await("the Overview card opens", () -> view.overviewCard() != null);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<Button>field(view.overviewCard(), "close").fire(); // the card's ×
                assertNull(view.overviewCard(), "dismissed");
                r.panel.mapWindow().closeByUser();
            });
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project again", () ->
                    (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, r.beta));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> assertNull(view.overviewCard(), "no Overview card the second time"));
        }
    }
}
