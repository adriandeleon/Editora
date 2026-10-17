package com.editora.ui;

import java.util.List;

import com.editora.command.KeybindingEdits;
import com.editora.command.KeymapManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rebinding Find in CUA stores a suppressor for CUA's {@code C-f}. Carried into Emacs, that suppressor used to
 * unbind forward-char, which is what {@code C-f} means there.
 */
class KeymapSwitchOverridesTest {

    private static KeymapManager rebuild(Settings settings) {
        KeymapManager keymap = new KeymapManager();
        KeymapLayers.rebuild(keymap, settings.getKeymap(), List.of(), settings.keybindingsFor(KeymapManager.isMac()));
        return keymap;
    }

    @Test
    void aRebindMadeInOneKeymapDoesNotUnbindAnothersDefaults() {
        boolean mac = KeymapManager.isMac();
        Settings settings = new Settings();
        settings.setKeymap("cua");
        KeymapManager base = new KeymapManager();
        base.loadNamed("cua");
        String findChord =
                KeybindingEdits.defaultChords(base.bindings(), "find.show").get(0);
        settings.setKeybindingsFor(
                mac, KeybindingEdits.rebind(base.bindings(), settings.keybindingsFor(mac), "find.show", "<f7>"));
        assertEquals("", settings.keybindingsFor(mac).get(findChord), "precondition: the default is suppressed");
        assertEquals("find.show", rebuild(settings).bindings().get("<f7>"));

        KeymapLayers.switchKeymap(settings, "emacs");

        KeymapManager emacsDefaults = new KeymapManager();
        emacsDefaults.loadNamed("emacs");
        assertEquals(emacsDefaults.bindings(), rebuild(settings).bindings(), "Emacs is the stock Emacs keymap");

        KeymapLayers.switchKeymap(settings, "cua");
        assertEquals("find.show", rebuild(settings).bindings().get("<f7>"), "the CUA rebind comes back with CUA");
    }

    @Test
    void anUnknownConfiguredNameParksItsOverridesUnderTheKeymapThatWasRunning() {
        Settings settings = new Settings();
        settings.setKeymap("vim"); // not bundled: the default keymap is what actually runs
        settings.keybindingsFor(false).put("<f7>", "find.show");
        settings.keybindingsFor(true).put("<f7>", "find.show");

        KeymapLayers.switchKeymap(settings, "cua");

        assertTrue(settings.getKeymapKeybindings().containsKey(KeymapManager.DEFAULT));
        KeymapLayers.switchKeymap(settings, KeymapManager.DEFAULT);
        assertEquals("find.show", settings.keybindingsFor(false).get("<f7>"));
    }

    /**
     * A macro's shortcut has nothing to do with a keymap, yet a switch parked it with the keymap it was made in:
     * the macro showed "Unbound" until the user switched back. It follows now, unless the new keymap's own
     * chords make it dead — then it stays with the old keymap and comes back with it.
     */
    @Test
    void aShortcutOfACommandNoKeymapBindsFollowsTheSwitch() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        settings.keybindingsFor(false).put("C-M-S-f12", "macro.run.m1");
        settings.keybindingsFor(false).put("C-c m", "macro.run.m2"); // under CUA, C-c is Copy: unreachable
        settings.keybindingsFor(false).put("C-x C-s", ""); // an Emacs-only suppressor stays with Emacs

        KeymapLayers.switchKeymap(settings, "cua");
        assertEquals("macro.run.m1", settings.keybindingsFor(false).get("C-M-S-f12"), "it follows");
        assertNull(settings.keybindingsFor(false).get("C-c m"), "a chord CUA makes dead stays behind");
        assertNull(settings.keybindingsFor(false).get("C-x C-s"));

        settings.keybindingsFor(false).remove("C-M-S-f12"); // rebound while in CUA
        settings.keybindingsFor(false).put("C-M-S-f11", "macro.run.m1");
        KeymapLayers.switchKeymap(settings, "emacs");
        assertEquals("macro.run.m1", settings.keybindingsFor(false).get("C-M-S-f11"));
        assertNull(settings.keybindingsFor(false).get("C-M-S-f12"), "the old chord does not come back");
        assertEquals("macro.run.m2", settings.keybindingsFor(false).get("C-c m"));
        assertEquals("", settings.keybindingsFor(false).get("C-x C-s"));
    }
}
