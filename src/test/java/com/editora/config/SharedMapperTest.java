package com.editora.config;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Boot builds no Jackson machinery it does not need: every window's session shares the config's one JSON
 * mapper (and with it the (de)serializers already built), and the TOML mapper for the one-time legacy
 * migration exists only when there is a legacy file to migrate.
 */
class SharedMapperTest {

    @Test
    void everyWindowsSessionUsesTheSharedMapper(@TempDir Path dir) throws Exception {
        ConfigManager first = new ConfigManager(dir);
        try {
            first.load();
            ConfigManager second =
                    new ConfigManager(first.shared(), dir.resolve("windows").resolve("w.json"));
            assertSame(first.shared().json(), field(first, "json"));
            assertSame(first.shared().json(), field(second, "json"));
        } finally {
            first.shared().shutdown();
        }
    }

    @Test
    void theTomlMapperIsBuiltOnlyForALegacySettingsFile(@TempDir Path plain, @TempDir Path legacy) throws Exception {
        ConfigManager none = new ConfigManager(plain);
        try {
            none.load();
            assertNull(field(none.shared(), "legacyToml"), "no settings.toml, no TOML mapper");
        } finally {
            none.shared().shutdown();
        }

        Files.writeString(legacy.resolve(ConfigManager.LEGACY_SETTINGS_FILE_NAME), "tabSize = 3\n");
        ConfigManager migrating = new ConfigManager(legacy);
        try {
            Settings settings = migrating.load();
            assertNotNull(field(migrating.shared(), "legacyToml"));
            assertEquals(3, settings.getTabSize(), "the legacy file is still migrated");
        } finally {
            migrating.shared().shutdown();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
