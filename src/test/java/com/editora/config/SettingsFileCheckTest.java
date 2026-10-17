package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hand edit of settings.json: what is applied when it is saved, and what is reported as not used. */
class SettingsFileCheckTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reportsUnknownKeysWrongTypesAndChangedValuesButNotOmittedNestedDefaults() throws Exception {
        var written = json.readTree("""
                {"schemaVersion": 1, "tabSize": 4, "fontSize": -5, "noSuchSetting": true, "showMinimap": "yes",
                 "rulers": [80], "nested": {"a": 1}}""");
        var used = json.readTree("""
                {"schemaVersion": 2, "tabSize": 4.0, "fontSize": 6, "showMinimap": true,
                 "rulers": [80], "nested": {"a": 1, "b": 2}}""");
        List<SettingsFileCheck.Issue> issues = SettingsFileCheck.issues(written, used, List.of("showMinimap"));
        assertEquals(
                List.of(
                        new SettingsFileCheck.Issue("fontSize", SettingsFileCheck.Kind.CHANGED, "-5", "6"),
                        new SettingsFileCheck.Issue("noSuchSetting", SettingsFileCheck.Kind.UNKNOWN, "true", null),
                        new SettingsFileCheck.Issue(
                                "showMinimap", SettingsFileCheck.Kind.WRONG_TYPE, "\"yes\"", "true")),
                issues);
    }

    @Test
    void savingTheFileAppliesItToTheLiveSettingsAndSaysWhatWasNotUsed(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        Settings live = config.load();
        config.shared().saveSettings();
        Path file = config.getSettingsFile();
        String text = Files.readString(file);
        Files.writeString(
                file,
                text.replaceFirst("\"tabSize\"\\s*:\\s*\\d+", "\"tabSize\" : 8")
                        .replaceFirst("\"fontSize\"\\s*:\\s*\\d+", "\"fontSize\" : -5")
                        .replaceFirst("\\{", "{ \"tabsize\" : 2,"));
        List<SettingsFileCheck.Issue> issues = config.shared().reloadSettings();
        assertEquals(8, live.getTabSize(), "the same Settings object every window holds now has the edit");
        assertEquals(Settings.MIN_FONT_SIZE, live.getFontSize());
        assertTrue(
                issues.contains(new SettingsFileCheck.Issue("tabsize", SettingsFileCheck.Kind.UNKNOWN, "2", null)),
                issues.toString());
        assertTrue(issues.stream()
                .anyMatch(i -> i.key().equals("fontSize") && i.kind() == SettingsFileCheck.Kind.CHANGED));
        assertEquals(issues, config.shared().settingsFileIssues());

        // Half-typed JSON applies nothing and keeps what is in force.
        Files.writeString(file, "{ \"tabSize\" : 3, ");
        assertNull(config.shared().reloadSettings());
        assertEquals(8, live.getTabSize());
    }
}
