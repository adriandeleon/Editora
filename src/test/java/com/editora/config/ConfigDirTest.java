package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Verifies the config-directory resolver honors EDITORA_CONFIG_DIR and falls back to ~/.editora. */
class ConfigDirTest {

    @Test
    void editoraHomeWhenSetIsUsedVerbatim() {
        assertEquals(Path.of("/opt/editora-config"),
                ConfigManager.resolveConfigDir("/opt/editora-config", "/Users/jane"));
    }

    @Test
    void editoraHomeIsTrimmed() {
        assertEquals(Path.of("/srv/cfg"),
                ConfigManager.resolveConfigDir("  /srv/cfg  ", "/Users/jane"));
    }

    @Test
    void fallsBackToDotEditoraUnderUserHome() {
        assertEquals(Path.of("/Users/jane", ".editora"),
                ConfigManager.resolveConfigDir(null, "/Users/jane"));
        assertEquals(Path.of("/Users/jane", ".editora"),
                ConfigManager.resolveConfigDir("   ", "/Users/jane")); // blank ⇒ ignored
    }

    @Test
    void devModeUsesDotEditoraDev() {
        assertEquals(Path.of("/Users/jane", ".editora-dev"),
                ConfigManager.resolveConfigDir(null, "/Users/jane", true));
        assertEquals(Path.of("/Users/jane", ".editora"),
                ConfigManager.resolveConfigDir(null, "/Users/jane", false));
    }

    @Test
    void envVarStillWinsOverDevMode() {
        assertEquals(Path.of("/opt/cfg"),
                ConfigManager.resolveConfigDir("/opt/cfg", "/Users/jane", true));
    }
}
