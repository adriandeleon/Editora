package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The About dialog must show the <em>live</em> settings path (so {@code --dev}'s {@code ~/.editora-dev}
 * and {@code --config-dir} are reflected), with the home dir abbreviated to {@code ~}.
 */
class AboutSettingsPathTest {

    private static final String SEP = File.separator;

    @Test
    void abbreviatesHomeDirToTilde() {
        String home = System.getProperty("user.home");
        assertEquals(
                "~" + SEP + ".editora-dev" + SEP + "settings.toml",
                SettingsWindow.displaySettingsPath(Path.of(home, ".editora-dev", "settings.toml")));
        assertEquals(
                "~" + SEP + ".editora" + SEP + "settings.toml",
                SettingsWindow.displaySettingsPath(Path.of(home, ".editora", "settings.toml")));
    }

    @Test
    void leavesPathsOutsideHomeUntouched() {
        Path outside = Path.of(System.getProperty("user.home")).getRoot().resolve("etc-editora.toml");
        assertEquals(outside.toString(), SettingsWindow.displaySettingsPath(outside));
    }
}
