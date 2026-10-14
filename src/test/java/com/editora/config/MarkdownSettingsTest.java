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
        assertEquals(Settings.SCHEMA_VERSION, s.getSchemaVersion());
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
        assertEquals(Settings.SCHEMA_VERSION, saved.get("schemaVersion").asInt());
        assertFalse(saved.get("wordWrapProse").asBoolean());
        assertFalse(saved.has("markdownRemoteImages"), "a view of the policy, not a stored key");
        assertEquals("never", saved.get("markdownRemoteImagePolicy").asText());
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

    // --- the feature-gap pass's fields: what a hand-edited value reads as -----------------------------------

    @Test
    void choicesReadAsOneOfTheirValues() {
        Settings s = new Settings();
        assertEquals("editor", s.getMarkdownDefaultViewMode());
        assertEquals("ask", s.getMarkdownUpdateLinksOnRename());
        s.setMarkdownDefaultViewMode("split");
        s.setMarkdownUpdateLinksOnRename("never");
        assertEquals("split", s.getMarkdownDefaultViewMode());
        assertEquals("never", s.getMarkdownUpdateLinksOnRename());
        s.setMarkdownDefaultViewMode("sideways");
        s.setMarkdownUpdateLinksOnRename(null);
        assertEquals("editor", s.getMarkdownDefaultViewMode());
        assertEquals("ask", s.getMarkdownUpdateLinksOnRename(), "never silently 'always' on a bad value");
    }

    @Test
    void previewSizesAreZeroOrWithinTheirRange() {
        Settings s = new Settings();
        assertEquals(0, s.getMarkdownPreviewFontSize());
        assertEquals(0, s.getMarkdownPreviewWidth());
        s.setMarkdownPreviewFontSize(3);
        s.setMarkdownPreviewWidth(50);
        assertEquals(Settings.MIN_PREVIEW_FONT_SIZE, s.getMarkdownPreviewFontSize());
        assertEquals(Settings.MIN_PREVIEW_WIDTH, s.getMarkdownPreviewWidth());
        s.setMarkdownPreviewFontSize(900);
        s.setMarkdownPreviewWidth(90_000);
        assertEquals(Settings.MAX_PREVIEW_FONT_SIZE, s.getMarkdownPreviewFontSize());
        assertEquals(Settings.MAX_PREVIEW_WIDTH, s.getMarkdownPreviewWidth());
        s.setMarkdownPreviewFontSize(-4);
        assertEquals(0, s.getMarkdownPreviewFontSize(), "anything below one is 'the built-in size'");
    }

    @Test
    void thePastedImageNameIsAPlainFileName() {
        assertEquals("shot", Settings.normalizeImageName(" shot "));
        assertEquals("my image-01", Settings.normalizeImageName("my image-01"));
        for (String bad :
                new String[] {null, "", " ", "a/b", "..\\up", ".hidden", "name.", "what?", "a:b", "x".repeat(81)}) {
            assertEquals("pasted-image", Settings.normalizeImageName(bad), String.valueOf(bad));
        }
    }

    @Test
    void aLintRuleIsOnByItsDefaultAndTheListThatGovernsIt() {
        Settings s = new Settings();
        assertTrue(s.isMarkdownLintRuleOn("MD009"));
        assertFalse(s.isMarkdownLintRuleOn("MD013"), "line length is off until turned on");
        assertFalse(s.isMarkdownLintRuleOn("md033"));

        s.setMarkdownLintRuleOn("md013", true);
        s.setMarkdownLintRuleOn("MD009", false);
        assertTrue(s.isMarkdownLintRuleOn("MD013"));
        assertFalse(s.isMarkdownLintRuleOn("MD009"));
        assertEquals(java.util.List.of("MD013"), s.getMarkdownLintEnabledRules());
        assertEquals(java.util.List.of("MD009"), s.getMarkdownLintDisabledRules());

        s.setMarkdownLintRuleOn("MD013", false);
        s.setMarkdownLintRuleOn("MD009", true);
        assertEquals(java.util.List.of(), s.getMarkdownLintEnabledRules(), "back to the default leaves no entry");
        assertEquals(java.util.List.of(), s.getMarkdownLintDisabledRules());

        // hand-edited lists: case, spaces, nulls
        s.setMarkdownLintEnabledRules(new java.util.ArrayList<>(java.util.Arrays.asList(" md033 ", null)));
        assertTrue(s.isMarkdownLintRuleOn("MD033"));
        s.setMarkdownLintEnabledRules(null);
        assertFalse(s.isMarkdownLintRuleOn("MD033"));
    }

    @Test
    void aLintRulesSeverityIsWarningUnlessSaidOtherwise() {
        Settings s = new Settings();
        assertEquals("warning", s.markdownLintSeverity("MD009"));
        s.setMarkdownLintSeverity("md009", "ERROR");
        s.setMarkdownLintSeverity("MD040", "hint");
        assertEquals("error", s.markdownLintSeverity("MD009"));
        assertEquals("hint", s.markdownLintSeverity("md040"));
        assertEquals(java.util.Map.of("MD009", "error", "MD040", "hint"), s.getMarkdownLintSeverities());
        s.setMarkdownLintSeverity("MD009", "warning");
        assertFalse(s.getMarkdownLintSeverities().containsKey("MD009"), "the default is stored as no entry");
        s.setMarkdownLintSeverities(new java.util.LinkedHashMap<>(java.util.Map.of("MD001", "fatal")));
        assertEquals("warning", s.markdownLintSeverity("MD001"), "an unknown stored value");
        s.setMarkdownLintSeverities(null);
        assertEquals("warning", s.markdownLintSeverity("MD001"));
        assertEquals("warning", s.markdownLintSeverity(null));
    }

    @Test
    void settingsAndTheLinterAgreeOnWhichRulesAreOffByDefault() {
        assertEquals(com.editora.markdown.MarkdownLint.DEFAULT_OFF, Settings.MARKDOWN_LINT_DEFAULT_OFF);
        assertEquals(
                com.editora.markdown.MarkdownLintOptions.SEVERITIES, java.util.Set.copyOf(Settings.LINT_SEVERITIES));
    }

    @Test
    void theRemoteImagePolicyIsNormalisedAndTheOldSwitchIsAViewOfIt() {
        Settings s = new Settings();
        assertEquals("always", s.getMarkdownRemoteImagePolicy());
        assertTrue(s.isMarkdownRemoteImages());
        for (String bad : new String[] {null, "", "sometimes", "  "}) {
            s.setMarkdownRemoteImagePolicy(bad);
            assertEquals("always", s.getMarkdownRemoteImagePolicy(), "a hand-edited value reads as the default");
        }
        s.setMarkdownRemoteImagePolicy(" ASK ");
        assertEquals("ask", s.getMarkdownRemoteImagePolicy());
        s.setMarkdownRemoteImages(true);
        assertEquals("ask", s.getMarkdownRemoteImagePolicy(), "switching on what is already on keeps 'ask'");
        s.setMarkdownRemoteImages(false);
        assertEquals("never", s.getMarkdownRemoteImagePolicy());
        assertFalse(s.isMarkdownRemoteImages());
        s.setMarkdownRemoteImages(true);
        assertEquals("always", s.getMarkdownRemoteImagePolicy());
    }
}
