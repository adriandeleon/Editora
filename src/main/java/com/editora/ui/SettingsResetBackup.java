package com.editora.ui;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The copy of {@code settings.json} kept by "Reset to Defaults". A reset clears things that cannot be
 * re-derived — AI provider keys, the external-tool list, TODO patterns, the toolbar layout, tool paths — and
 * rewrites the file in place, so without this the only way back was a config export made beforehand.
 */
final class SettingsResetBackup {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SettingsResetBackup() {}

    /** {@code settings.json.before-reset-<yyyyMMdd-HHmmss>.bak}, beside the settings file. */
    static Path target(Path settingsFile, LocalDateTime now) {
        return settingsFile.resolveSibling(settingsFile.getFileName() + ".before-reset-" + STAMP.format(now) + ".bak");
    }

    /**
     * Copies {@code settingsFile} to a new, dated file beside it and returns that file. Never overwrites: a
     * second reset in the same second gets a numbered name, so an earlier backup — the one holding the
     * user's real settings — survives a reset of already-default settings. File permissions are copied
     * (the file holds credentials and is owner-only).
     *
     * @throws IOException when there is no settings file to copy or the copy cannot be written or verified;
     *     the caller must then not reset
     */
    static Path write(Path settingsFile, LocalDateTime now) throws IOException {
        Path base = target(settingsFile, now);
        Path backup = base;
        for (int n = 2; ; n++) {
            try {
                Files.copy(settingsFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
                break;
            } catch (FileAlreadyExistsException taken) {
                if (n > 100) {
                    throw taken;
                }
                String name = base.getFileName().toString();
                backup = base.resolveSibling(name.substring(0, name.length() - ".bak".length()) + "-" + n + ".bak");
            }
        }
        if (Files.mismatch(settingsFile, backup) != -1) {
            Files.deleteIfExists(backup);
            throw new IOException("The backup of " + settingsFile.getFileName() + " does not match it");
        }
        return backup;
    }
}
