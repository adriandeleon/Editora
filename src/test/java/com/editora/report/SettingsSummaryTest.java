package com.editora.report;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings line of a problem report: what is chosen and what is on, and nothing that names the user. */
class SettingsSummaryTest {

    @Test
    void itSaysTheChoicesAndTheSwitchesThatChangeWhatTheEditorDoes() {
        Settings s = new Settings();
        s.setKeymap("vscode");
        s.setUiLanguage("de");
        s.setLspSupport(false);
        s.setSimpleMode(true);
        String line = SettingsSummary.of(s);

        assertTrue(line.startsWith("Settings: theme=" + s.getTheme() + ", "), line);
        assertTrue(line.contains(", keymap=vscode, "), line);
        assertTrue(line.contains(", language=de, "), line);
        assertTrue(line.contains(", languageServers=off, "), line);
        assertTrue(line.contains(", simpleMode=on, "), line);
        assertTrue(line.contains(", uiZoom=" + s.getUiZoom() + ", "), line);
        assertFalse(line.contains("\n"), "one line, like the rest of the block");

        s.setUiLanguage("");
        assertTrue(SettingsSummary.of(s).contains(", language=system, "), "no language chosen follows the system");
    }

    @Test
    void itNamesTheAiProviderOnlyWhileAiIsOnAndNeverAKeyAnAddressOrAPath() {
        Settings s = new Settings();
        s.setAiApiKey("sk-not-a-real-key");
        s.setSyncRepoUrl("git@example.invalid:jdoe/settings.git");
        s.setTerminalShell("/home/jdoe/bin/fish");
        s.setAiProvider("anthropic");
        s.setAiSupport(false);
        String off = SettingsSummary.of(s);
        assertTrue(off.contains(", ai=off, "), off);

        s.setAiSupport(true);
        String on = SettingsSummary.of(s);
        assertTrue(on.contains(", ai=anthropic, "), on);
        for (String line : new String[] {off, on}) {
            assertFalse(line.contains("sk-not-a-real-key"), line);
            assertFalse(line.contains("example.invalid"), line);
            assertFalse(line.contains("jdoe"), line);
            assertFalse(line.contains("/"), "no path and no address: " + line);
        }
    }

    @Test
    void aReportMadeWhileTheEditorIsNotRunningReadsTheSettingsFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        assertEquals(
                SettingsSummary.of(new Settings()) + " (no settings file: the defaults)", SettingsSummary.ofFile(file));

        Settings saved = new Settings();
        saved.setKeymap("sublime");
        new ObjectMapper().writeValue(file.toFile(), saved);
        assertEquals(SettingsSummary.of(saved), SettingsSummary.ofFile(file));

        // A newer Editora's file, with a setting this one does not know, is still summarised.
        Files.writeString(file, "{\"keymap\": \"cua\", \"aSettingFromTheFuture\": 3}");
        assertTrue(SettingsSummary.ofFile(file).contains(", keymap=cua, "));

        // A file that cannot be read is what the report is for: it says so instead of failing.
        Files.writeString(file, "{ this is not json");
        assertTrue(
                SettingsSummary.ofFile(file).startsWith("Settings: the settings file could not be read ("),
                SettingsSummary.ofFile(file));
    }
}
