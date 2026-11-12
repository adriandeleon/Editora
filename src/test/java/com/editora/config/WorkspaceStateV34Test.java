package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v34: the Project Map's "Show dependencies of the selection". Off for a v33 workspace; kept. */
class WorkspaceStateV34Test {

    @Test
    void aV33WorkspaceHasTheOverlayOffAndAChoiceSurvivesARestart(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"), "{\"schemaVersion\":33,\"projectMapShowRecentChanges\":true}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getWorkspaceState().isProjectMapShowRecentChanges());
        assertFalse(config.getWorkspaceState().isProjectMapShowDependencies());
        assertEquals(WorkspaceState.SCHEMA_VERSION, ConfigSchema.WORKSPACE.currentVersion());

        config.getWorkspaceState().setProjectMapShowDependencies(true);
        config.save();
        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertTrue(reopened.getWorkspaceState().isProjectMapShowDependencies());
    }
}
