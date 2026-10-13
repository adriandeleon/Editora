package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings schema 118 adds: word wrap per kind of file, and the Markdown preview/paste/table-of-contents
 * choices. What an upgraded file keeps, what a new one defaults to, and what a hand-edited value reads as.
 */
class MarkdownSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Settings load(Path dir, String json) throws Exception {
        Files.writeString(dir.resolve("settings.json"), json);
        return new ConfigManager(dir).load();
    }

    @Test
    void aNewInstallWrapsProseAndNotCode() {
        Settings s = new Settings();
        assertFalse(s.isWordWrap());
        assertTrue(s.isWordWrapProse());
        assertTrue(s.wrapsLanguage("markdown"));
        assertTrue(s.wrapsLanguage("plaintext"));
        assertTrue(s.wrapsLanguage(null), "an unsaved buffer with no language is plain text");
        assertFalse(s.wrapsLanguage("java"));
        assertFalse(s.wrapsLanguage("html"));
        assertTrue(s.isMarkdownRemoteImages());
        assertEquals("assets", s.getMarkdownAssetsFolder());
        assertEquals(1, s.getMarkdownTocMinLevel());
        assertEquals(6, s.getMarkdownTocMaxLevel());
        assertEquals("", s.getMarkdownPreviewTheme());
    }

    /** The one global switch became "in code". Whoever had turned it on still wraps everything. */
    @Test
    void aUserWhoHadTurnedWrappingOnStillWrapsEverywhere(@TempDir Path dir) throws Exception {
        Settings s = load(dir, "{\"schemaVersion\":117,\"wordWrap\":true}");
        assertEquals(118, s.getSchemaVersion());
        assertTrue(s.isWordWrap(), "code still wraps");
        assertTrue(s.isWordWrapProse());
        assertTrue(s.wrapsLanguage("java") && s.wrapsLanguage("markdown"));
    }

    @Test
    void aUserWhoNeverTouchedItGetsWrappedProseAndUnwrappedCode(@TempDir Path dir) throws Exception {
        Settings s = load(dir, "{\"schemaVersion\":117,\"wordWrap\":false,\"markdownPreviewTheme\":\"dark\"}");
        assertFalse(s.isWordWrap());
        assertTrue(s.isWordWrapProse());
        assertEquals("dark", s.getMarkdownPreviewTheme(), "the preview theme chosen before is kept");
        assertTrue(s.isMarkdownRemoteImages(), "remote images keep loading, as they did");
        assertEquals("assets", s.getMarkdownAssetsFolder());
    }

    @Test
    void theChoicesAreSavedAndReadBack(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        Settings s = config.load();
        s.setWordWrapProse(false);
        s.setWordWrap(true);
        s.setMarkdownRemoteImages(false);
        s.setMarkdownAssetsFolder("docs/img");
        s.setMarkdownTocMinLevel(2);
        s.setMarkdownTocMaxLevel(4);
        s.setMarkdownPreviewTheme("light");
        config.save();

        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals(118, saved.get("schemaVersion").asInt());
        assertFalse(saved.get("wordWrapProse").asBoolean());
        assertFalse(saved.get("markdownRemoteImages").asBoolean());
        assertEquals("docs/img", saved.get("markdownAssetsFolder").asText());

        Settings again = new ConfigManager(dir).load();
        assertFalse(again.isWordWrapProse());
        assertTrue(again.isWordWrap());
        assertFalse(again.isMarkdownRemoteImages());
        assertEquals("docs/img", again.getMarkdownAssetsFolder());
        assertEquals(2, again.getMarkdownTocMinLevel());
        assertEquals(4, again.getMarkdownTocMaxLevel());
        assertEquals("light", again.getMarkdownPreviewTheme());
    }

    @Test
    void theAssetsFolderNeverLeavesTheDocumentsFolder() {
        assertEquals("img", Settings.normalizeAssetsFolder(" ./img/ "));
        assertEquals("docs/img", Settings.normalizeAssetsFolder("docs\\img"));
        assertEquals(".attachments", Settings.normalizeAssetsFolder(".attachments"));
        assertEquals("my images", Settings.normalizeAssetsFolder("my images"));
        for (String bad : new String[] {
            null,
            "",
            "  ",
            ".",
            "..",
            "../up",
            "a/../../b",
            "/abs",
            "\\\\host\\share",
            "C:\\img",
            "c:img",
            "~/img",
            "a//b",
            "a/./b",
            "what?",
            "a|b",
            "tab\tname"
        }) {
            assertEquals("assets", Settings.normalizeAssetsFolder(bad), String.valueOf(bad));
        }
    }

    @Test
    void handEditedValuesReadAsSomethingUsable(@TempDir Path dir) throws Exception {
        Settings s = load(
                dir,
                "{\"schemaVersion\":118,\"markdownAssetsFolder\":\"../../etc\",\"markdownTocMinLevel\":5,"
                        + "\"markdownTocMaxLevel\":2,\"markdownPreviewTheme\":\"sepia\"}");
        assertEquals("assets", s.getMarkdownAssetsFolder());
        assertEquals(2, s.getMarkdownTocMinLevel(), "the range is never empty");
        assertEquals(2, s.getMarkdownTocMaxLevel());
        assertEquals("", s.getMarkdownPreviewTheme());

        Settings t = load(
                dir,
                "{\"schemaVersion\":118,\"markdownTocMinLevel\":0,\"markdownTocMaxLevel\":99,"
                        + "\"markdownAssetsFolder\":null,\"markdownPreviewTheme\":null}");
        assertEquals(1, t.getMarkdownTocMinLevel());
        assertEquals(6, t.getMarkdownTocMaxLevel());
        assertEquals("assets", t.getMarkdownAssetsFolder());
        assertEquals("", t.getMarkdownPreviewTheme());
    }
}
