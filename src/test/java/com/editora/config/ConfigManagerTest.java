package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {

    @Test
    void missingFileYieldsDefaults(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        assertEquals("emacs", settings.getKeymap());
        assertEquals(4, settings.getTabSize());
    }

    @Test
    void savesAndReloadsSettingsAsJson(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setTabSize(8);
        config.getSettings().setTheme("light");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals(8, reloaded.getTabSize());
        assertEquals("light", reloaded.getTheme());
        assertTrue(Files.exists(dir.resolve("settings.json")));
    }

    @Test
    void partialJsonMergesOntoDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{\"fontSize\": 20}");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(20, settings.getFontSize());
        assertEquals("emacs", settings.getKeymap()); // untouched default
    }

    @Test
    void malformedJsonFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{ this is not JSON");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(14, settings.getFontSize());
    }

    @Test
    void keybindingChordKeysRoundTripInJson(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().getKeybindings().put("C-x C-s", "file.save");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals("file.save", reloaded.getKeybindings().get("C-x C-s"));
    }

    @Test
    void legacyTomlIsMigratedAtomicallyToJson(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("settings.toml");
        Settings old = new Settings();
        old.setFontSize(20);
        old.setTheme("light");
        old.setKeybindings(java.util.Map.of("C-x C-s", "file.save"));
        new TomlMapper().writeValue(legacy.toFile(), old);

        Settings settings = new ConfigManager(dir).load();

        assertEquals(20, settings.getFontSize());
        assertEquals("light", settings.getTheme());
        assertEquals("file.save", settings.getKeybindings().get("C-x C-s"));
        assertTrue(Files.exists(dir.resolve("settings.json")), "JSON replacement written");
        assertFalse(Files.exists(legacy), "legacy file removed only after replacement");
        var json = new ObjectMapper().readTree(dir.resolve("settings.json").toFile());
        assertEquals(20, json.get("fontSize").asInt());
        assertEquals("light", json.get("theme").asText());
        assertEquals("file.save", json.get("keybindings").get("C-x C-s").asText());
    }

    @Test
    void existingJsonWinsOverStaleLegacyToml(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{\"fontSize\": 18}");
        Files.writeString(dir.resolve("settings.toml"), "fontSize = 30\n");

        Settings settings = new ConfigManager(dir).load();

        assertEquals(18, settings.getFontSize());
        assertTrue(Files.exists(dir.resolve("settings.toml")), "unused legacy file is not touched");
    }

    @Test
    void workspaceStateRoundTripsAsJson(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getWorkspaceState().getFoldedRegions().put("/tmp/a/Main.java", List.of(3, 9));
        config.getWorkspaceState().setOpenLeftToolWindow("project");
        config.save();

        assertTrue(Files.exists(dir.resolve("workspace-state.json")));
        ConfigManager reloaded = new ConfigManager(dir);
        reloaded.load();
        assertEquals(
                List.of(3, 9), reloaded.getWorkspaceState().getFoldedRegions().get("/tmp/a/Main.java"));
        assertEquals("project", reloaded.getWorkspaceState().getOpenLeftToolWindow());
    }

    @Test
    void sessionRoundTripsAsJson(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState ws = config.getWorkspaceState();
        ws.getOpenFiles().add(new WorkspaceState.OpenFile("/tmp/a/Main.java", 150, true));
        ws.getOpenFiles().add(new WorkspaceState.OpenFile("/tmp/b/Util.java", 0, false));
        ws.setActiveFile("/tmp/b/Util.java");
        config.save();

        ConfigManager rc = new ConfigManager(dir);
        rc.load();
        assertEquals(2, rc.getWorkspaceState().getOpenFiles().size());
        assertEquals(
                "/tmp/a/Main.java", rc.getWorkspaceState().getOpenFiles().get(0).getPath());
        assertEquals(150, rc.getWorkspaceState().getOpenFiles().get(0).getCaret());
        assertTrue(rc.getWorkspaceState().getOpenFiles().get(0).isPinned());
        assertFalse(rc.getWorkspaceState().getOpenFiles().get(1).isPinned());
        assertEquals("/tmp/b/Util.java", rc.getWorkspaceState().getActiveFile());
    }
}
