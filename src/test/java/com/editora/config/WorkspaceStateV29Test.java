package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v29 (Map explore, views): the Project Map's working sets, named {@link ProjectMapState}s kept per
 * workspace. A v28 file gains none and keeps the rest; the sets survive a restart in order, with their cards.
 */
class WorkspaceStateV29Test {

    @Test
    void aV28FileGainsNoWorkingSetsAndKeepsWhatItHad(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":28,\"projectMapCompactRows\":true,"
                        + "\"projectMapState\":{\"root\":\"/work/p\",\"selected\":\"README.md\"}}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertTrue(state.getProjectMapWorkingSets().isEmpty(), "a Map with no named views");
        assertTrue(state.isProjectMapCompactRows());
        assertNotNull(state.getProjectMapState());
        assertEquals("README.md", state.getProjectMapState().getSelected());
        assertEquals(29, state.getSchemaVersion());
        assertEquals(29, ConfigSchema.WORKSPACE.currentVersion());
    }

    @Test
    void workingSetsSurviveARestartInOrderWithTheirCards(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        ProjectMapState api = new ProjectMapState();
        api.setRoot("/work/project");
        api.setExpanded(List.of("api", "api/v1"));
        api.setSelected("api/v1/types.go");
        api.setZoom(0.75);
        api.setOffsetX(-40);
        api.setOffsetY(12.5);
        api.setCards(List.of(new ProjectMapState.Card("api/v1/types.go/…sPod~0", 300, 80, 520, 360, 1.1, false)));
        ProjectMapState docs = new ProjectMapState();
        docs.setRoot("/work/project");
        docs.setExpanded(List.of("docs"));
        first.getWorkspaceState()
                .setProjectMapWorkingSets(
                        List.of(new ProjectMapWorkingSet("API types", api), new ProjectMapWorkingSet("Docs", docs)));
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
    }
}
