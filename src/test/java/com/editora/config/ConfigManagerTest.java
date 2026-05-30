package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {

    @Test
    void missingFileYieldsDefaults(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        assertEquals("emacs", settings.getKeymap());
        assertEquals(4, settings.getTabSize());
    }

    @Test
    void savesAndReloadsSettingsAsToml(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setTabSize(8);
        config.getSettings().setTheme("light");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals(8, reloaded.getTabSize());
        assertEquals("light", reloaded.getTheme());
        assertTrue(Files.exists(dir.resolve("settings.toml")));
    }

    @Test
    void partialTomlMergesOntoDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.toml"), "fontSize = 20\n");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(20, settings.getFontSize());
        assertEquals("emacs", settings.getKeymap()); // untouched default
    }

    @Test
    void malformedTomlFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.toml"), "this is = = not toml [[[");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(14, settings.getFontSize());
    }

    @Test
    void keybindingChordKeysRoundTripInToml(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().getKeybindings().put("C-x C-s", "file.save");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals("file.save", reloaded.getKeybindings().get("C-x C-s"));
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
        assertEquals(List.of(3, 9), reloaded.getWorkspaceState().getFoldedRegions().get("/tmp/a/Main.java"));
        assertEquals("project", reloaded.getWorkspaceState().getOpenLeftToolWindow());
    }
}
