package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v27: the Project Map window's maximized state and three of the Map's ⋯ options. A v26 file
 * gains the defaults (a normal window, single-click opens, compacted chains, no following) and keeps the rest.
 */
class WorkspaceStateV27Test {

    @Test
    void aV26FileGainsTheDefaultsAndKeepsWhatItHad(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":26,\"projectViewMode\":\"MAP\",\"projectMapWindowBounds\":[10.0,20.0,900.0,600.0],"
                        + "\"debugWatches\":[\"kept\"]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertFalse(state.isProjectMapWindowMaximized());
        assertTrue(state.isProjectMapSingleClickOpens());
        assertTrue(state.isProjectMapCompactChains());
        assertFalse(state.isProjectMapFollowActiveFile());
        assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
        assertEquals(java.util.List.of(10.0, 20.0, 900.0, 600.0), state.getProjectMapWindowBounds());
        assertEquals(java.util.List.of("kept"), state.getDebugWatches());
        assertEquals(27, state.getSchemaVersion());
    }

    @Test
    void theFourFieldsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        WorkspaceState written = first.getWorkspaceState();
        written.setProjectMapWindowMaximized(true);
        written.setProjectMapSingleClickOpens(false);
        written.setProjectMapCompactChains(false);
        written.setProjectMapFollowActiveFile(true);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        WorkspaceState state = reopened.getWorkspaceState();

        assertTrue(state.isProjectMapWindowMaximized());
        assertFalse(state.isProjectMapSingleClickOpens());
        assertFalse(state.isProjectMapCompactChains());
        assertTrue(state.isProjectMapFollowActiveFile());
        assertEquals(WorkspaceState.SCHEMA_VERSION, state.getSchemaVersion());
    }
}
