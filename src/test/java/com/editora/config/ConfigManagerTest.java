package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void savesAndReloads(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setTabSize(8);
        config.getSettings().setTheme("light");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals(8, reloaded.getTabSize());
        assertEquals("light", reloaded.getTheme());
        assertTrue(Files.exists(dir.resolve("config.json")));
    }

    @Test
    void partialJsonMergesOntoDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("config.json"), "{\"fontSize\": 20}");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(20, settings.getFontSize());
        assertEquals("emacs", settings.getKeymap()); // untouched default
    }

    @Test
    void malformedJsonFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("config.json"), "{ this is not json");
        Settings settings = new ConfigManager(dir).load();
        assertEquals(14, settings.getFontSize());
    }
}
