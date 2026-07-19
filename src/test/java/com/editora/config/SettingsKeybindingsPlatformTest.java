package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-platform keybinding override maps (#439). */
class SettingsKeybindingsPlatformTest {

    @Test
    void keybindingsForPicksTheRightSlot() {
        Settings s = new Settings();
        s.setKeybindings(Map.of("C-k", "file.save")); // Ctrl slot (Windows/Linux)
        s.setKeybindingsMac(Map.of("Cmd-k", "file.save")); // Cmd slot (macOS)

        assertEquals(Map.of("Cmd-k", "file.save"), s.keybindingsFor(true));
        assertEquals(Map.of("C-k", "file.save"), s.keybindingsFor(false));
    }

    @Test
    void aMacWrittenConfigAppliesNoOverridesOnWindowsLinux() {
        // The #439 scenario: a synced config edited on macOS carries Cmd-based overrides (including the
        // Cmd-S UNBIND suppressor) in the mac slot, with the base slot cleared by the migration. Read on a
        // non-mac machine, keybindingsFor(false) must be EMPTY — otherwise the stale Cmd-S UNBIND fails to
        // suppress the live Ctrl-S default and the command double-binds to both Ctrl-S and the new chord.
        Settings s = new Settings();
        Map<String, String> macOverrides = new LinkedHashMap<>();
        macOverrides.put("Cmd-s", ""); // UNBIND the old default
        macOverrides.put("F5", "file.save"); // a platform-neutral new chord that WOULD leak
        s.setKeybindingsMac(macOverrides);
        s.setKeybindings(new LinkedHashMap<>()); // base slot cleared

        assertTrue(s.keybindingsFor(false).isEmpty(), "no overrides leak onto Windows/Linux");
        assertEquals(macOverrides, s.keybindingsFor(true), "the mac machine still gets them");
    }

    @Test
    void keybindingsForNeverReturnsNullAndSetForRoundTrips() {
        Settings s = new Settings();
        s.setKeybindingsMac(null);
        assertNotNull(s.keybindingsFor(true), "a null slot degrades to an empty (mutable) map");

        s.keybindingsFor(true).put("Cmd-p", "palette.show");
        assertEquals(
                "palette.show", s.getKeybindingsMac().get("Cmd-p"), "the empty map is stored back, not thrown away");

        s.setKeybindingsFor(false, Map.of("C-p", "palette.show"));
        assertEquals(Map.of("C-p", "palette.show"), s.getKeybindings());
    }

    @Test
    void resetToDefaultsPreservesBothPlatformMaps() {
        Settings s = new Settings();
        s.setKeybindings(Map.of("C-k", "file.save"));
        s.setKeybindingsMac(Map.of("Cmd-k", "file.save"));
        s.setFontSize(22); // a page-owned setting Reset should clear

        Settings.resetToDefaults(s);

        assertEquals(14, s.getFontSize(), "an ordinary setting is reset to its default");
        assertEquals(Map.of("C-k", "file.save"), s.getKeybindings(), "the Keymaps page owns its own reset");
        assertEquals(Map.of("Cmd-k", "file.save"), s.getKeybindingsMac());
    }
}
