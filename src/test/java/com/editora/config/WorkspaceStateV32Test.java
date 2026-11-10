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
 * Workspace schema v32: a project's compounds, run configurations debugged together. A v31 workspace has none and
 * keeps its run configurations; compounds survive a restart, and a partial entry loads with what it has.
 */
class WorkspaceStateV32Test {

    @Test
    void aV31WorkspaceHasNoCompoundsAndKeepsItsConfigurations(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":31,\"runConfigurations\":[{\"name\":\"Server\",\"mainClass\":\"app.Server\"}]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.getWorkspaceState().getRunCompounds().isEmpty());
        assertEquals(
                "Server",
                config.getWorkspaceState().getRunConfigurations().getFirst().name());
        assertEquals(WorkspaceState.SCHEMA_VERSION, config.getWorkspaceState().getSchemaVersion());
        assertEquals(WorkspaceState.SCHEMA_VERSION, ConfigSchema.WORKSPACE.currentVersion());
    }

    @Test
    void compoundsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        first.getWorkspaceState().setRunCompounds(List.of(new RunCompound("Both", List.of("Server", "Client"), true)));
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        assertEquals(
                List.of(new RunCompound("Both", List.of("Server", "Client"), true)),
                reopened.getWorkspaceState().getRunCompounds());
    }

    @Test
    void aPartialCompoundLoadsWithWhatItHas(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":32,\"runCompounds\":[{\"name\":\"Pair\"},"
                        + "{\"configurations\":[\"A\",null,\"\",\"B\"],\"later\":1}]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertEquals(
                List.of(new RunCompound("Pair", List.of(), false), new RunCompound("", List.of("A", "B"), false)),
                config.getWorkspaceState().getRunCompounds());
    }
}
