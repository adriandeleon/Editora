package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version-2 values of a project's {@code .editora/settings.json}: editor, build and debug, trust-gated. */
class ProjectSettingsScopeTest {

    private static Path write(Path root, String json) throws Exception {
        Path file = ProjectSettings.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    @Test
    void editorValuesApplyOnlyInATrustedFolder(@TempDir Path root) throws Exception {
        write(root, """
                {"version": 2, "editor": {"indentStyle": "space", "tabSize": 2, "rulers": [100, 0, 100],
                 "languageRulers": {"Python": [79]}, "wordWrap": true, "trimTrailingWhitespace": true,
                 "insertFinalNewline": false, "spellLanguage": "en_GB"}}
                """);
        ProjectSettings.Editor e = ProjectSettings.load(root).editor();

        assertEquals("space", e.indentStyle("detect", true));
        assertEquals("detect", e.indentStyle("detect", false), "untrusted: the user's own");
        assertEquals(2, e.tabSize(4, true));
        assertEquals(4, e.tabSize(4, false));
        assertEquals(List.of(100), e.rulers(List.of(80), true), "cleaned: no 0, no repeat");
        assertEquals(List.of(80), e.rulers(List.of(80), false));
        assertEquals(
                Map.of("java", List.of(120), "python", List.of(79)),
                e.languageRulers(Map.of("java", List.of(120)), true),
                "laid over the user's, names lower-cased");
        assertTrue(e.wordWrap(false, true));
        assertFalse(e.wordWrapProse(false, true), "not set: the user's");
        assertEquals(Boolean.TRUE, e.trimTrailingWhitespace(true));
        assertNull(e.trimTrailingWhitespace(false));
        assertEquals(Boolean.FALSE, e.insertFinalNewline(true));
        assertEquals("en_GB", e.spellLanguage("en_US", true, id -> true));
        assertEquals("en_US", e.spellLanguage("en_US", true, id -> false), "a dictionary this install lacks");
    }

    @Test
    void valuesOutOfRangeAreIgnoredNotClamped(@TempDir Path root) throws Exception {
        write(root, "{\"editor\": {\"tabSize\": 99, \"indentStyle\": \"spaces\"}}");
        ProjectSettings ps = ProjectSettings.load(root);
        assertEquals(4, ps.editor().tabSize(4, true));
        assertEquals("tab", ps.editor().indentStyle("tab", true));
        assertTrue(ps.isEmpty(), "nothing usable is set");
        assertEquals(Map.of(), ps.valueOverrides());
    }

    @Test
    void buildAndDebugCommandsNeedTrustAndAreListedForThePrompt(@TempDir Path root) throws Exception {
        write(root, """
                {"buildCommands": {"maven": "./mvnw -q", "npm": " "}, "debugTools": {"python": "/opt/py/bin/python"},
                 "editor": {"tabSize": 2}}
                """);
        ProjectSettings ps = ProjectSettings.load(root);
        assertEquals("./mvnw -q", ps.buildCommand("maven", "mvn", true));
        assertEquals("mvn", ps.buildCommand("maven", "mvn", false));
        assertEquals("npm", ps.buildCommand("npm", "npm", true), "blank falls through");
        assertEquals("/opt/py/bin/python", ps.debugTool("python", "", true));
        assertEquals("", ps.debugTool("python", "", false));
        assertEquals(
                List.of("editor.tabSize", "build.maven", "debug.python"),
                List.copyOf(ps.valueOverrides().keySet()));
        assertFalse(ps.isEmpty());
    }

    @Test
    void aVersionOneFileReadsAsBefore(@TempDir Path root) throws Exception {
        write(root, "{\"lspCommands\": {\"java\": \"/opt/jdtls\"}}");
        ProjectSettings ps = ProjectSettings.load(root);
        assertNull(ps.getVersion());
        assertEquals("/opt/jdtls", ps.commandFor("java", "jdtls"));
        assertTrue(ps.editor().isEmpty());
    }

    @Test
    void settingAValueKeepsEverythingElseInTheFile(@TempDir Path root) throws Exception {
        Path file = write(root, """
                {"_description": "team settings", "lspCommands": {"java": "/opt/jdtls"}, "futureKey": [1, 2]}
                """);
        ProjectSettingsFile.set(root, "editor", "tabSize", 2);
        ProjectSettingsFile.set(root, "editor", "rulers", List.of(100));
        ProjectSettingsFile.set(root, "buildCommands", "maven", "./mvnw");

        JsonNode tree = new ObjectMapper().readTree(file.toFile());
        assertEquals("team settings", tree.get("_description").asText());
        assertEquals("/opt/jdtls", tree.get("lspCommands").get("java").asText());
        assertEquals(2, tree.get("futureKey").size(), "a key this Editora does not know stays");
        assertEquals(ProjectSettings.VERSION, tree.get("version").asInt());
        assertEquals(2, ProjectSettings.load(root).getEditor().getTabSize());

        ProjectSettingsFile.set(root, "editor", "tabSize", null);
        ProjectSettingsFile.set(root, "editor", "rulers", null);
        ProjectSettingsFile.set(root, "buildCommands", "maven", null);
        tree = new ObjectMapper().readTree(file.toFile());
        assertNull(tree.get("editor"), "an emptied section goes");
        assertNull(tree.get("buildCommands"));
        assertEquals("/opt/jdtls", tree.get("lspCommands").get("java").asText());
    }

    @Test
    void aFileFromANewerEditoraOrOneThatIsNotJsonIsNotOverwritten(@TempDir Path root) throws Exception {
        Path file = write(root, "{\"version\": 9, \"editor\": {\"tabSize\": 3}}");
        assertThrows(
                ProjectSettingsFile.NewerFileException.class,
                () -> ProjectSettingsFile.set(root, "editor", "tabSize", 2));
        assertTrue(Files.readString(file).contains("\"version\": 9"));

        Files.writeString(file, "{ not json");
        assertThrows(java.io.IOException.class, () -> ProjectSettingsFile.set(root, "editor", "tabSize", 2));
        assertEquals("{ not json", Files.readString(file), "a hand-edited file with a typo is kept");
    }

    @Test
    void writingCreatesTheFileWhenThereIsNone(@TempDir Path root) throws Exception {
        ProjectSettingsFile.set(root, "editor", "insertFinalNewline", true);
        assertEquals(Boolean.TRUE, ProjectSettings.load(root).editor().insertFinalNewline(true));
    }
}
