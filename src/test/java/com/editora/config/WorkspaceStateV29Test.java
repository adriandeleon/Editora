package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v29 (Map explore): the Project Map's working sets (named {@link ProjectMapState}s kept per
 * workspace), its "Show recent changes" option and a kept card's {@code pinned}. A v28 file gains none, off and
 * unpinned, and keeps the rest; all three survive a restart.
 */
class WorkspaceStateV29Test {

    @Test
    void aV28FileGainsNoWorkingSetsAndKeepsWhatItHad(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":28,\"projectMapCompactRows\":true,"
                        + "\"projectMapState\":{\"root\":\"/work/p\",\"selected\":\"README.md\","
                        + "\"cards\":[{\"path\":\"a.go\",\"x\":10,\"structure\":true}]}}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertTrue(state.getProjectMapWorkingSets().isEmpty(), "a Map with no named views");
        assertTrue(state.isProjectMapCompactRows());
        assertNotNull(state.getProjectMapState());
        assertEquals("README.md", state.getProjectMapState().getSelected());
        assertEquals(1, state.getProjectMapState().getCards().size());
        assertTrue(state.getProjectMapState().getCards().getFirst().isStructure());
        assertFalse(state.getProjectMapState().getCards().getFirst().isPinned(), "a v28 card is not pinned");
        assertFalse(state.isProjectMapShowRecentChanges(), "no recent-changes tint");
        assertTrue(state.getDebugAttachTargets().isEmpty(), "v30: no attach address remembered yet");
        assertEquals(WorkspaceState.SCHEMA_VERSION, state.getSchemaVersion(), "upgraded to the current version");
        assertEquals(WorkspaceState.SCHEMA_VERSION, ConfigSchema.WORKSPACE.currentVersion());
    }

    @Test
    void workingSetsRecentChangesAndPinnedCardsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        ProjectMapState api = new ProjectMapState();
        api.setRoot("/work/project");
        api.setExpanded(List.of("api", "api/v1"));
        api.setSelected("api/v1/types.go");
        api.setZoom(0.75);
        api.setOffsetX(-40);
        api.setOffsetY(12.5);
        ProjectMapState.Card pod = new ProjectMapState.Card("api/v1/types.go/…sPod~0", 300, 80, 520, 360, 1.1, false);
        pod.setPinned(true);
        api.setCards(List.of(pod));
        ProjectMapState docs = new ProjectMapState();
        docs.setRoot("/work/project");
        docs.setExpanded(List.of("docs"));
        first.getWorkspaceState()
                .setProjectMapWorkingSets(
                        List.of(new ProjectMapWorkingSet("API types", api), new ProjectMapWorkingSet("Docs", docs)));
        first.getWorkspaceState().setProjectMapShowRecentChanges(true);
        ProjectMapState shown = new ProjectMapState();
        shown.setRoot("/work/project");
        ProjectMapState.Card pinned = new ProjectMapState.Card("README.md", 1, 2, 3, 4, 1.0, false);
        pinned.setPinned(true);
        shown.setCards(List.of(pinned, new ProjectMapState.Card("go.mod", 1, 2, 3, 4, 1.0, false)));
        first.getWorkspaceState().setProjectMapState(shown);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        List<ProjectMapWorkingSet> sets = reopened.getWorkspaceState().getProjectMapWorkingSets();

        assertEquals(
                List.of("API types", "Docs"),
                sets.stream().map(ProjectMapWorkingSet::getName).toList());
        ProjectMapState read = sets.getFirst().getState();
        assertEquals("/work/project", read.getRoot());
        assertEquals(List.of("api", "api/v1"), read.getExpanded());
        assertEquals("api/v1/types.go", read.getSelected());
        assertEquals(0.75, read.getZoom());
        assertEquals(12.5, read.getOffsetY());
        assertEquals(1, read.getCards().size());
        assertEquals("api/v1/types.go/…sPod~0", read.getCards().getFirst().getPath());
        assertEquals(1.1, read.getCards().getFirst().getTextZoom());
        assertEquals(List.of("docs"), sets.get(1).getState().getExpanded());
        assertTrue(read.getCards().getFirst().isPinned(), "a set's pinned card stays pinned");
        assertTrue(reopened.getWorkspaceState().isProjectMapShowRecentChanges());
        List<ProjectMapState.Card> kept =
                reopened.getWorkspaceState().getProjectMapState().getCards();
        assertTrue(kept.getFirst().isPinned());
        assertFalse(kept.get(1).isPinned());
    }
}
