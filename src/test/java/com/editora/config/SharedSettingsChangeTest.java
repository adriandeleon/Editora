package com.editora.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A save that carries preferences no other window has applied is reported with the saving window, so the
 * windows that share the {@link Settings} object can re-apply. This is what makes a palette or key-binding
 * toggle reach every window without each command having to broadcast it.
 */
class SharedSettingsChangeTest {

    private static ConfigManager window(SharedConfig shared, String name) {
        return new ConfigManager(
                shared, shared.getConfigDir().resolve("projects").resolve(name + ".json"));
    }

    @Test
    void aChangedPreferenceIsReportedOnceWithTheWindowThatSavedIt(@TempDir Path dir) {
        ConfigManager a = new ConfigManager(dir);
        a.load();
        ConfigManager b = window(a.shared(), "b");
        List<ConfigManager> reported = new ArrayList<>();
        a.shared().setOnSettingsChanged(reported::add);
        a.shared().markSettingsApplied();

        a.saveAsync();
        assertTrue(reported.isEmpty(), "a save that changed no preference reports nothing");

        a.getSettings().setShowLineNumbers(!a.getSettings().isShowLineNumbers());
        b.saveAsync();
        assertEquals(List.of(b), reported, "reported with the window whose save carried it");

        a.saveAsync();
        b.save();
        assertEquals(1, reported.size(), "and only once");
    }

    @Test
    void nothingIsReportedBeforeThereIsASecondWindowToTell(@TempDir Path dir) {
        ConfigManager only = new ConfigManager(dir);
        only.load();
        List<ConfigManager> reported = new ArrayList<>();
        only.shared().setOnSettingsChanged(reported::add);

        only.getSettings().setTabSize(8);
        only.save();

        assertTrue(reported.isEmpty(), "detection starts at markSettingsApplied()");
    }

    @Test
    void bookkeepingFieldsAreNotAPreferenceChange(@TempDir Path dir) {
        ConfigManager a = new ConfigManager(dir);
        a.load();
        List<ConfigManager> reported = new ArrayList<>();
        a.shared().setOnSettingsChanged(reported::add);
        a.shared().markSettingsApplied();

        a.getSettings().setLastUpdateCheckEpoch(1_700_000_000L);
        a.getSettings().setDismissedUpdateVersion("9.9.9");
        a.save();

        assertTrue(reported.isEmpty(), "the daily update check must not re-apply every window");
    }

    @Test
    void aChangeAlreadyAppliedEverywhereIsNotReportedAgain(@TempDir Path dir) {
        ConfigManager a = new ConfigManager(dir);
        a.load();
        List<ConfigManager> reported = new ArrayList<>();
        a.shared().setOnSettingsChanged(reported::add);
        a.shared().markSettingsApplied();

        // The Settings window path: it broadcasts to every window itself, then the save follows.
        a.getSettings().setWordWrap(!a.getSettings().isWordWrap());
        a.shared().markSettingsApplied();
        a.save();

        assertTrue(reported.isEmpty());
    }
}
