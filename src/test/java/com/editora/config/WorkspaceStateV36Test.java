package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workspace schema v36: the pages Debug in Browser opened. None for a v35 workspace; the list is kept. */
class WorkspaceStateV36Test {

    @Test
    void aV35WorkspaceHasNoPagesAndTheListSurvivesARestart(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"), "{\"schemaVersion\":35,\"projectMapIgnoreDebugger\":true}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getWorkspaceState().isProjectMapIgnoreDebugger());
        assertTrue(config.getWorkspaceState().getDebugBrowserUrls().isEmpty(), "absent: none debugged before");

        config.getWorkspaceState().setDebugBrowserUrls(List.of("http://localhost:5173", "http://localhost:3000"));
        config.save();
        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertEquals(
                List.of("http://localhost:5173", "http://localhost:3000"),
                reopened.getWorkspaceState().getDebugBrowserUrls());
    }
}
