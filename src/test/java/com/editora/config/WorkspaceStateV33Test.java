package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v33: the debugger's paused watches and its evaluate history. A v32 workspace has neither and keeps
 * its watches; both survive a restart.
 */
class WorkspaceStateV33Test {

    @Test
    void aV32WorkspaceKeepsItsWatchesWithNonePausedAndNoHistory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":32,\"debugWatches\":[\"a\",\"b\"]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertEquals(List.of("a", "b"), config.getWorkspaceState().getDebugWatches());
        assertTrue(config.getWorkspaceState().getDebugPausedWatches().isEmpty());
        assertTrue(config.getWorkspaceState().getDebugEvaluateHistory().isEmpty());
        assertEquals(WorkspaceState.SCHEMA_VERSION, config.getWorkspaceState().getSchemaVersion());
        assertEquals(35, ConfigSchema.WORKSPACE.currentVersion());
    }

    @Test
    void pausedWatchesAndTheEvaluateHistorySurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        first.getWorkspaceState().setDebugWatches(List.of("a", "costly()"));
        first.getWorkspaceState().setDebugPausedWatches(List.of("costly()"));
        first.getWorkspaceState().setDebugEvaluateHistory(List.of("list.size()", "if (x) {\n  return 1;\n}"));
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertEquals(List.of("costly()"), reopened.getWorkspaceState().getDebugPausedWatches());
        assertEquals(
                List.of("list.size()", "if (x) {\n  return 1;\n}"),
                reopened.getWorkspaceState().getDebugEvaluateHistory());
    }
}
