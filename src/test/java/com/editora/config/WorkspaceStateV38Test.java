package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v38: tool-window sizes in pixels. None for a v37 workspace, so fractions still apply. */
class WorkspaceStateV38Test {

    @Test
    void aV37WorkspaceKeepsItsFractionsAndAPixelSizeSurvivesARestart(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"), "{\"schemaVersion\":37,\"toolWindowSizes\":{\"project\":0.8}}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertEquals(WorkspaceState.SCHEMA_VERSION, ConfigSchema.WORKSPACE.currentVersion());
        assertEquals(0.8, config.getWorkspaceState().getToolWindowSizes().get("project"), 0.0001);
        assertTrue(config.getWorkspaceState().getToolWindowPixelSizes().isEmpty(), "absent: the fraction applies");

        config.getWorkspaceState().getToolWindowPixelSizes().put("project", 260.0);
        config.save();
        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertEquals(
                260.0, reopened.getWorkspaceState().getToolWindowPixelSizes().get("project"), 0.0001);
    }
}
