package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end config schema versioning: stamping on save, downgrade backup, recent-files migration. */
class ConfigVersioningTest {

    @Test
    void saveStampsSchemaVersionOnSettingsAndWorkspace(@TempDir Path dir) throws Exception {
        ConfigManager cfg = new ConfigManager(dir);
        cfg.load();
        cfg.save();
        assertTrue(
                Files.readString(cfg.getSettingsFile()).contains("schemaVersion = " + Settings.SCHEMA_VERSION),
                "settings.toml is stamped");
        assertTrue(
                Files.readString(cfg.getWorkspaceStateFile()).contains("\"schemaVersion\""),
                "workspace-state.json is stamped");
    }

    @Test
    void unversionedSettingsLoadCleanlyAndAreStampedOnSave(@TempDir Path dir) throws Exception {
        // A pre-versioning settings.toml (no schemaVersion key) reads normally.
        Files.writeString(dir.resolve("settings.toml"), "fontFamily = \"Iosevka\"\nfontSize = 17\n");
        ConfigManager cfg = new ConfigManager(dir);
        Settings s = cfg.load();
        assertEquals("Iosevka", s.getFontFamily());
        assertEquals(17, s.getFontSize());
        assertEquals(Settings.SCHEMA_VERSION, s.getSchemaVersion(), "stamped to current on load");
        cfg.save();
        assertTrue(Files.readString(cfg.getSettingsFile()).contains("schemaVersion = "));
    }

    @Test
    void tooNewSettingsAreBackedUpAndDefaultsLoaded(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.toml"), "schemaVersion = 99\nfontFamily = \"FromTheFuture\"\n");
        ConfigManager cfg = new ConfigManager(dir);
        Settings s = cfg.load();
        assertEquals(new Settings().getFontFamily(), s.getFontFamily(), "defaults used, not the future font");
        assertFalse(Files.exists(dir.resolve("settings.toml")), "too-new file moved aside");
        assertTrue(Files.exists(dir.resolve("settings.toml.v99.bak")), "backed up for safety");
    }

    @Test
    void recentFilesLegacyArrayMigratesToVersionedObject(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("recent-files.json");
        Files.writeString(file, "[ \"/tmp/a.txt\", \"/tmp/b.txt\" ]"); // legacy v0 bare array

        RecentFiles recents = new RecentFiles(dir);
        assertEquals(List.of(Path.of("/tmp/a.txt"), Path.of("/tmp/b.txt")), recents.getList(), "legacy array is read");

        recents.add(Path.of("/tmp/c.txt")); // triggers a save in the new format
        String json = Files.readString(file);
        assertTrue(json.contains("\"schemaVersion\""), "saved as a versioned object");
        assertTrue(json.contains("\"files\""), "files array present");
        assertFalse(json.trim().startsWith("["), "no longer a bare array");

        // Re-read the migrated file round-trips.
        RecentFiles reloaded = new RecentFiles(dir);
        assertEquals(Path.of("/tmp/c.txt"), reloaded.getList().get(0));
    }
}
