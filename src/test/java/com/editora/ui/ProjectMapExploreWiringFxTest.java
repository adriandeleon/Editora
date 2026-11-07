package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.layout.Region;

import com.editora.config.ProjectMapState;
import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map explore round: what the lead wired when the groups merged — the workspace keeps "Show recent changes" (activity)
 * and a card's pin (cards) through the state a restart or a working set restores (views, schema 29).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapExploreWiringFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void showRecentChangesComesBackFromTheWorkspaceAndAChangeIsWrittenBack(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapShowRecentChanges(true);
            AtomicInteger saves = new AtomicInteger();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, saves::incrementAndGet));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            FxTestSupport.runOnFx(() -> assertTrue(view.isShowRecentChanges(), "restored on"));
            int before = saves.get();
            FxTestSupport.runOnFx(() -> view.setShowRecentChanges(false));
            r.await("the change is saved", () -> saves.get() > before);
            assertFalse(state.isProjectMapShowRecentChanges());
        }
    }

    @Test
    void aPinnedCardIsKeptPinnedInTheMapsStateAndComesBackPinned(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project", () ->
                    (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, r.beta));
            MapCards cards = FxTestSupport.field(view, "cards");
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(r.beta));
            assertNotNull(card);
            FxTestSupport.runOnFx(() -> card.setPinned(true));

            ProjectMapState kept = FxTestSupport.callOnFx(view::bearingsState);
            assertNotNull(kept);
            ProjectMapState.Card keptCard = kept.getCards().stream()
                    .filter(c -> c.getPath().equals("beta.txt"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(keptCard.isPinned(), "the state keeps the pin");

            // Restored as a restart restores it: the card comes back pinned.
            FxTestSupport.runOnFx(() -> cards.closeAll());
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    view,
                    "bearingsOpenCard",
                    new Class<?>[] {Path.class, ProjectMapState.Card.class},
                    r.beta,
                    keptCard));
            r.await("the card is back", () -> cards.codeCard(r.beta) != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview back = cards.codeCard(r.beta);
                assertNotNull(back);
                assertTrue(cards.pinned(back), "and pinned");
                assertEquals(1, cards.openCards().size());
            });
        }
    }
}
