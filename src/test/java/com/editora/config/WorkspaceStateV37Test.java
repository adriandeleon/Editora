package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v37: the log viewer's state per file. None for a v36 workspace; entries are kept. */
class WorkspaceStateV37Test {

    @Test
    void aV36WorkspaceHasNoLogViewsAndOneSurvivesARestart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":36,\"debugBrowserUrls\":[\"x\"]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertEquals(1, config.getWorkspaceState().getDebugBrowserUrls().size());
        assertTrue(config.getWorkspaceState().getLogViews().isEmpty(), "absent: every file opens unfiltered");

        WorkspaceState.LogViewState state = new WorkspaceState.LogViewState(true, "WARN", "time.*out", "pool", true);
        config.getWorkspaceState().getLogViews().put("/var/log/app.out", state);
        config.save();
        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertEquals(state, reopened.getWorkspaceState().getLogViews().get("/var/log/app.out"));
    }
}
