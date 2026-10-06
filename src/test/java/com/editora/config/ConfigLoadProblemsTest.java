package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when a config file cannot be read as written: a bad value costs only that value, the original
 * content is kept, the problem is reported, and a file whose content could be neither read nor backed up is
 * not overwritten.
 */
class ConfigLoadProblemsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The number of backup names {@code ConfigMigrations} tries before giving up (its {@code MAX_BACKUPS}). */
    private static final int BACKUP_NAMES = 20;

    private static String settingsJson(String... properties) {
        return "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + ", " + String.join(", ", properties) + "}";
    }

    @Test
    void oneMistypedValueKeepsEverySettingAfterIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        String original = settingsJson(
                "\"fontSize\": 20",
                "\"showMinimap\": \"yes\"", // a hand edit: not a boolean
                "\"theme\": \"light\"",
                "\"keybindings\": {\"C-x C-s\": \"file.save\"}",
                "\"aiApiKey\": \"sk-kept\"");
        Files.writeString(file, original);

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals(20, settings.getFontSize(), "a value before the bad one");
        assertTrue(settings.isShowMinimap(), "the bad value keeps its default");
        assertEquals("light", settings.getTheme(), "a value after the bad one");
        assertEquals("file.save", settings.getKeybindings().get("C-x C-s"), "key bindings survive");
        assertEquals("sk-kept", settings.getAiApiKey(), "API keys survive");

        List<ConfigLoadProblem> problems = config.shared().takeLoadProblems();
        assertEquals(1, problems.size());
        ConfigLoadProblem problem = problems.get(0);
        assertEquals(ConfigLoadProblem.Kind.VALUES_SKIPPED, problem.kind());
        assertEquals(List.of("showMinimap"), problem.skipped());
        assertEquals(dir.resolve("settings.json.corrupt.bak"), problem.backup());
        assertEquals(original, Files.readString(problem.backup()), "the hand-edited file is kept as written");
        assertTrue(config.shared().takeLoadProblems().isEmpty(), "reported once");

        // The next save must not write the loss back: everything that was readable is still there.
        assertTrue(config.save());
        JsonNode saved = JSON.readTree(file.toFile());
        assertEquals("light", saved.get("theme").asText());
        assertEquals("file.save", saved.get("keybindings").get("C-x C-s").asText());
        assertEquals("sk-kept", saved.get("aiApiKey").asText());
    }

    @Test
    void severalMistypedValuesAreAllReportedByName(@TempDir Path dir) throws IOException {
        Files.writeString(
                dir.resolve("settings.json"),
                settingsJson("\"tabSize\": \"wide\"", "\"keymap\": \"cua\"", "\"keybindings\": \"none\""));
        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals(4, settings.getTabSize());
        assertEquals("cua", settings.getKeymap());
        assertTrue(settings.getKeybindings().isEmpty());
        assertEquals(
                List.of("tabSize", "keybindings"),
                config.shared().takeLoadProblems().get(0).skipped());
    }

    @Test
    void outOfRangeNumbersAreClampedOnLoad(@TempDir Path dir) throws IOException {
        Files.writeString(
                dir.resolve("settings.json"),
                settingsJson("\"tabSize\": 0", "\"fontSize\": -3", "\"fontZoom\": 250.0", "\"theme\": \"light\""));
        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals(Settings.MIN_TAB_SIZE, settings.getTabSize(), "a zero tab size divided by zero in Indenter");
        assertEquals(Settings.MIN_FONT_SIZE, settings.getFontSize());
        assertEquals(Settings.MAX_FONT_ZOOM, settings.getFontZoom());
        assertEquals("light", settings.getTheme());
        assertTrue(config.shared().takeLoadProblems().isEmpty(), "a clamped value is not a read failure");
    }

    @Test
    void numericSettersClampToTheRangesTheUiOffers() {
        Settings s = new Settings();
        s.setTabSize(99);
        assertEquals(Settings.MAX_TAB_SIZE, s.getTabSize());
        s.setTabSize(-1);
        assertEquals(Settings.MIN_TAB_SIZE, s.getTabSize());
        s.setFontSize(500);
        assertEquals(Settings.MAX_FONT_SIZE, s.getFontSize());
        s.setFontZoom(0.01);
        assertEquals(Settings.MIN_FONT_ZOOM, s.getFontZoom());
        s.setFontZoom(Double.NaN);
        assertEquals(1.0, s.getFontZoom());
        // In-range values — including the palette's wider font range — are stored unchanged.
        s.setTabSize(8);
        s.setFontSize(72);
        s.setFontZoom(1.3);
        assertEquals(8, s.getTabSize());
        assertEquals(72, s.getFontSize());
        assertEquals(1.3, s.getFontZoom());
    }

    @Test
    void anUnparseableFileIsReportedWithItsBackup(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{ this is not JSON");
        ConfigManager config = new ConfigManager(dir);
        config.load();

        ConfigLoadProblem problem = config.shared().takeLoadProblems().get(0);
        assertEquals(ConfigLoadProblem.Kind.UNREADABLE, problem.kind());
        assertNotNull(problem.backup());
        assertFalse(problem.mustNotOverwrite());
        assertFalse(config.shared().isWriteProtected(dir.resolve("settings.json")));
    }

    @Test
    void aNewerFileThatCannotBeBackedUpIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        String newer = "{\"schemaVersion\": 9999, \"fontSize\": 31, \"fromTheFuture\": true}";
        Files.writeString(file, newer);
        occupyEveryBackupName(file, ".v9999.bak");

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        assertEquals(14, settings.getFontSize(), "defaults are loaded for a file this build cannot read");

        ConfigLoadProblem problem = config.shared().takeLoadProblems().get(0);
        assertEquals(ConfigLoadProblem.Kind.NEWER_VERSION, problem.kind());
        assertNull(problem.backup(), "every backup name is taken, so the move was refused");
        assertTrue(problem.mustNotOverwrite());
        assertTrue(config.shared().isWriteProtected(file));

        settings.setFontSize(18);
        config.save();
        config.saveAsync();
        config.shared().flushWrites();
        assertEquals(newer, Files.readString(file), "the only copy of the newer settings is left untouched");
    }

    @Test
    void anUnparseableFileThatCannotBeCopiedIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bookmarks.json");
        String torn = "{\"schemaVersion\":1,\"byProject\":{\"\":{\"/a/b.txt\":[{\"line\":3,";
        Files.writeString(file, torn);
        occupyEveryBackupName(file, ".corrupt.bak");

        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.shared().isWriteProtected(file));

        config.saveBookmarks();
        assertEquals(torn, Files.readString(file), "the torn file is not replaced by an empty store");
    }

    @Test
    void aNewerFileIsStillMovedAsideWhenABackupNameIsFree(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"schemaVersion\": 9999}");
        List<ConfigLoadProblem> problems = new ArrayList<>();
        ConfigMigrations.readVersioned(file, JSON, new Settings(), ConfigSchema.SETTINGS, problems::add);

        assertEquals(dir.resolve("settings.json.v9999.bak"), problems.get(0).backup());
        assertFalse(problems.get(0).mustNotOverwrite());
        assertFalse(Files.exists(file));
    }

    private static void occupyEveryBackupName(Path file, String suffix) throws IOException {
        Files.writeString(file.resolveSibling(file.getFileName() + suffix), "older backup");
        for (int i = 2; i <= BACKUP_NAMES; i++) {
            Files.writeString(file.resolveSibling(file.getFileName() + suffix + "." + i), "older backup " + i);
        }
    }
}
