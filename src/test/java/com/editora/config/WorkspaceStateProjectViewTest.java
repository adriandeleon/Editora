package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v13: the Project panel's Tree/Map choice and the Map's navigation options. */
class WorkspaceStateProjectViewTest {

    @Test
    void aV12FileKeepsItsStoredFlowAndGainsTheOldBehaviourAsDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":12,\"projectMapFlow\":\"RIGHT_TO_LEFT\",\"debugWatches\":[\"kept\"]}");

        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertEquals("RIGHT_TO_LEFT", state.getProjectMapFlow(), "a stored flow is the user's and is kept");
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
        assertTrue(state.isProjectMapKeepZoom());
        assertTrue(state.isProjectMapFocusNewColumn());
        assertEquals(java.util.List.of("kept"), state.getDebugWatches());
        assertEquals(WorkspaceState.SCHEMA_VERSION, state.getSchemaVersion());
    }

    @Test
    void aFileFromBeforeTheMapHadAFlowGetsTheNewDefault(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":10}");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        assertEquals("LEFT_TO_RIGHT", config.getWorkspaceState().getProjectMapFlow());
    }

    @Test
    void theViewModeAndNavigationOptionsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        first.getWorkspaceState().setProjectViewMode(WorkspaceState.PROJECT_VIEW_MAP);
        first.getWorkspaceState().setProjectMapKeepZoom(false);
        first.getWorkspaceState().setProjectMapFocusNewColumn(false);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        WorkspaceState state = reopened.getWorkspaceState();

        assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
        assertFalse(state.isProjectMapKeepZoom());
        assertFalse(state.isProjectMapFocusNewColumn());
    }

    @Test
    void anUnknownViewModeFallsBackToTheTree() {
        WorkspaceState state = new WorkspaceState();
        state.setProjectViewMode("GRID");
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
        state.setProjectViewMode(null);
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
    }
}
