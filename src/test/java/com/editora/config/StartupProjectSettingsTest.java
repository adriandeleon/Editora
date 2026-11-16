package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A window's project settings, read ahead with its session, are handed to it once and only for their root. */
class StartupProjectSettingsTest {

    @Test
    void handedOverOnceForTheirOwnRoot(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        Path root = dir.resolve("project");
        ProjectSettings read = new ProjectSettings();
        try {
            assertNull(config.takeStartupProjectSettings(root), "none attached");

            config.setStartupProjectSettings(root, read);
            assertNull(config.takeStartupProjectSettings(dir.resolve("other")), "another root's: not these");
            assertNull(config.takeStartupProjectSettings(root), "and asking detached them");

            config.setStartupProjectSettings(root, read);
            assertSame(read, config.takeStartupProjectSettings(root));
            assertNull(config.takeStartupProjectSettings(root), "once: a later read goes to the disk");
        } finally {
            config.shared().shutdown();
        }
    }
}
