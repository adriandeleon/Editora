package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v35: the Project Map's "Follow the Debugger". On for a v34 workspace; turning it off is kept. */
class WorkspaceStateV35Test {

    @Test
    void aV34WorkspaceFollowsTheDebuggerAndTurningItOffSurvivesARestart(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"), "{\"schemaVersion\":34,\"projectMapShowDependencies\":true}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getWorkspaceState().isProjectMapShowDependencies());
        assertFalse(config.getWorkspaceState().isProjectMapIgnoreDebugger(), "absent: the Map follows");
        assertEquals(35, ConfigSchema.WORKSPACE.currentVersion());

        config.getWorkspaceState().setProjectMapIgnoreDebugger(true);
        config.save();
        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertTrue(reopened.getWorkspaceState().isProjectMapIgnoreDebugger());
    }
}
