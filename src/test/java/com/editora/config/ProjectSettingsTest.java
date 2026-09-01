package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.i18n.Messages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSettingsTest {

    private static Path write(Path root, String json) throws Exception {
        Path file = ProjectSettings.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    @Test
    void aProjectOverridesTheServerCommand(@TempDir Path root) throws Exception {
        write(root, "{\"lspCommands\": {\"java\": \"/opt/jdk17/bin/jdtls\"}}");

        ProjectSettings ps = ProjectSettings.load(root);

        assertFalse(ps.isEmpty());
        assertEquals("/opt/jdk17/bin/jdtls", ps.commandFor("java", "jdtls"), "the project's toolchain wins");
        assertEquals("gopls", ps.commandFor("go", "gopls"), "an unmentioned server keeps the global value");
    }

    @Test
    void aProjectCanTurnAServerOffOrOn(@TempDir Path root) throws Exception {
        write(root, "{\"lspEnabled\": {\"rust\": false, \"python\": true}}");

        ProjectSettings ps = ProjectSettings.load(root);

        assertFalse(ps.enabledFor("rust", true), "explicitly off beats a global on");
        assertTrue(ps.enabledFor("python", false), "and explicitly on beats a global off");
        assertTrue(ps.enabledFor("java", true), "unmentioned falls through");
    }

    /**
     * A blank override must fall through. An empty command already means "use the registry default"
     * globally, so treating blank as an override would make that impossible to express — and would silently
     * disable a server for everyone who checked the repository out.
     */
    @Test
    void aBlankCommandFallsThroughRatherThanDisabling() throws Exception {
        ProjectSettings ps = new ProjectSettings();
        ps.setLspCommands(java.util.Map.of("java", "   "));

        assertEquals("jdtls", ps.commandFor("java", "jdtls"));
    }

    /** This file is committed and hand-edited by anyone on the team; a typo must not stop the project opening. */
    @Test
    void aMalformedOrAbsentFileYieldsNoOverrides(@TempDir Path root) throws Exception {
        assertTrue(ProjectSettings.load(root).isEmpty(), "absent");

        write(root, "{this is not JSON");
        ProjectSettings ps = ProjectSettings.load(root);
        assertTrue(ps.isEmpty(), "malformed");
        assertEquals("jdtls", ps.commandFor("java", "jdtls"), "and everything falls through to global");
    }

    @Test
    void noProjectMeansNoOverrides() {
        assertTrue(ProjectSettings.load(null).isEmpty());
    }

    @Test
    void legacyTomlRemainsReadableAndCanBeMigratedForEditing(@TempDir Path root) throws Exception {
        Path legacy = ProjectSettings.legacyFileFor(root);
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "[lspCommands]\njava = \"legacy-jdtls\"\n");

        assertEquals("legacy-jdtls", ProjectSettings.load(root).commandFor("java", "global-jdtls"));

        Path json = ProjectSettings.migrateLegacyForEditing(root);
        assertEquals(ProjectSettings.fileFor(root), json);
        assertTrue(Files.exists(json));
        assertFalse(Files.exists(legacy));
        assertEquals("legacy-jdtls", ProjectSettings.load(root).commandFor("java", "global-jdtls"));
    }

    @Test
    void jsonWinsWhenBothProjectFormatsExist(@TempDir Path root) throws Exception {
        write(root, "{\"lspCommands\": {\"java\": \"json-jdtls\"}}");
        Path legacy = ProjectSettings.legacyFileFor(root);
        Files.writeString(legacy, "[lspCommands]\njava = \"legacy-jdtls\"\n");

        assertEquals("json-jdtls", ProjectSettings.load(root).commandFor("java", "global-jdtls"));
    }

    @Test
    void malformedLegacyTomlIsNotDestroyedDuringExplicitMigration(@TempDir Path root) throws Exception {
        Path legacy = ProjectSettings.legacyFileFor(root);
        Files.createDirectories(legacy.getParent());
        String malformed = "[lspCommands\nthis is not toml";
        Files.writeString(legacy, malformed);

        assertThrows(java.io.IOException.class, () -> ProjectSettings.migrateLegacyForEditing(root));
        assertEquals(malformed, Files.readString(legacy));
        assertFalse(Files.exists(ProjectSettings.fileFor(root)));
    }

    @Test
    void everyLocalizedTemplateIsValidJsonWithoutActiveOverrides() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try {
            for (String language : Messages.available().keySet()) {
                Messages.init(language);
                ProjectSettings template =
                        mapper.readValue(Messages.tr("project.settings.template"), ProjectSettings.class);
                assertTrue(template.isEmpty(), language + " template examples must not activate overrides");
            }
        } finally {
            Messages.init("en");
        }
    }
}
