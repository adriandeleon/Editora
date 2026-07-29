package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSettingsTest {

    private static final TomlMapper MAPPER = new TomlMapper();

    private static Path write(Path root, String toml) throws Exception {
        Path file = ProjectSettings.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, toml);
        return file;
    }

    @Test
    void aProjectOverridesTheServerCommand(@TempDir Path root) throws Exception {
        write(root, "[lspCommands]\njava = \"/opt/jdk17/bin/jdtls\"\n");

        ProjectSettings ps = ProjectSettings.load(MAPPER, root);

        assertFalse(ps.isEmpty());
        assertEquals("/opt/jdk17/bin/jdtls", ps.commandFor("java", "jdtls"), "the project's toolchain wins");
        assertEquals("gopls", ps.commandFor("go", "gopls"), "an unmentioned server keeps the global value");
    }

    @Test
    void aProjectCanTurnAServerOffOrOn(@TempDir Path root) throws Exception {
        write(root, "[lspEnabled]\nrust = false\npython = true\n");

        ProjectSettings ps = ProjectSettings.load(MAPPER, root);

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
        assertTrue(ProjectSettings.load(MAPPER, root).isEmpty(), "absent");

        write(root, "[lspCommands\nthis is not toml");
        ProjectSettings ps = ProjectSettings.load(MAPPER, root);
        assertTrue(ps.isEmpty(), "malformed");
        assertEquals("jdtls", ps.commandFor("java", "jdtls"), "and everything falls through to global");
    }

    @Test
    void noProjectMeansNoOverrides() {
        assertTrue(ProjectSettings.load(MAPPER, null).isEmpty());
    }
}
