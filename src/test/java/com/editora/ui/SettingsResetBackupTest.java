package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** "Reset to Defaults" keeps a dated copy of settings.json, and never writes over an earlier one. */
class SettingsResetBackupTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 6, 21, 5, 9);

    @Test
    void theBackupIsADatedCopyBesideTheSettingsFile(@TempDir Path dir) throws IOException {
        Path settings = Files.writeString(dir.resolve("settings.json"), "{\"aiApiKey\":\"sk-user\"}");

        Path backup = SettingsResetBackup.write(settings, AT);

        assertEquals(dir.resolve("settings.json.before-reset-20261006-210509.bak"), backup);
        assertEquals("{\"aiApiKey\":\"sk-user\"}", Files.readString(backup));
        assertEquals("{\"aiApiKey\":\"sk-user\"}", Files.readString(settings), "the original is only copied");
    }

    @Test
    void aSecondResetDoesNotOverwriteTheFirstBackup(@TempDir Path dir) throws IOException {
        Path settings = Files.writeString(dir.resolve("settings.json"), "the user's settings");
        Path first = SettingsResetBackup.write(settings, AT);
        Files.writeString(settings, "defaults");

        Path second = SettingsResetBackup.write(settings, AT);

        assertNotEquals(first, second);
        assertEquals("the user's settings", Files.readString(first), "the copy worth having is still there");
        assertEquals("defaults", Files.readString(second));
    }

    @Test
    void withNothingToCopyThereIsNoBackupAndTheCallerMustNotReset(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> SettingsResetBackup.write(dir.resolve("settings.json"), AT));
    }
}
