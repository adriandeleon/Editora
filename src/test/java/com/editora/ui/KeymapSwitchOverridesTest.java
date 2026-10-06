package com.editora.ui;

import java.util.List;

import com.editora.command.KeybindingEdits;
import com.editora.command.KeymapManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
