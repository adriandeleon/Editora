package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.KeybindingEdits;
import com.editora.command.KeymapManager;
import com.editora.command.UserKeyBindings;
import com.editora.command.UserKeyBindings.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings hold one set of key bindings for every operating system: by command, per keymap, with the chords
 * only a Mac can type beside them.
 */
class SettingsKeybindingsPlatformTest {

    /** What {@code keymap} binds on one system once the bindings {@code s} holds for it are applied. */
    private static Map<String, String> bindings(Settings s, String keymap, boolean mac) {
        Store store = new Store(
                s.getKeyBindings().getOrDefault(keymap, Map.of()),
                s.getKeyBindingsMacOnly().getOrDefault(keymap, Map.of()));
        Map<String, String> out = new LinkedHashMap<>(KeymapManager.defaults(keymap, mac));
        UserKeyBindings.derive(store, out, mac, KeymapManager.hasMacVariant(keymap))
                .overrides()
                .forEach((chord, command) -> {
                    if (command.isBlank()) {
                        out.remove(chord);
                    } else {
                        out.put(chord, command);
                    }
                });
        return out;
    }

    private static List<String> chordsOf(Map<String, String> bindings, String command) {
        return KeybindingEdits.defaultChords(bindings, command);
    }

    /** #439: a rebind in a config shared between systems must move the command on each, not bind it twice. */
    @Test
    void oneStoredRebindMovesTheCommandOnEverySystem() {
        Settings s = new Settings();
        s.setKeyBindings(Map.of("vscode", Map.of("file.save", List.of("C-M-S-f11"))));

        Map<String, String> linux = bindings(s, "vscode", false);
        assertEquals(List.of("C-M-S-f11"), chordsOf(linux, "file.save"));
        assertNull(linux.get("C-s"), "the default is gone, not left bound beside the new chord");

        Map<String, String> mac = bindings(s, "vscode", true);
        assertEquals(List.of("M-Cmd-S-f11"), chordsOf(mac, "file.save"), "the same binding, as a Mac types it");
        assertNull(mac.get("Cmd-s"));
    }

    @Test
    void aMacOnlyChordIsUsedOnAMacAndLeavesTheOtherSystemsAtTheirDefaults() {
        Settings s = new Settings();
        s.setKeyBindingsMacOnly(Map.of("vscode", Map.of("file.save", List.of("C-Cmd-s"))));

        assertEquals(List.of("C-Cmd-s"), chordsOf(bindings(s, "vscode", true), "file.save"));
        assertEquals(KeymapManager.defaults("vscode", false), bindings(s, "vscode", false));
    }

    @Test
    void theBindingsAreNeverNull() {
        Settings s = new Settings();
        s.setKeyBindings(null);
        s.setKeyBindingsMacOnly(null);
        assertNotNull(s.getKeyBindings(), "null degrades to an empty (mutable) map");
        assertNotNull(s.getKeyBindingsMacOnly());

        s.getKeyBindings().put("cua", Map.of("palette.show", List.of("C-p")));
        assertEquals(List.of("C-p"), s.getKeyBindings().get("cua").get("palette.show"), "and what is put in it stays");
    }

    @Test
    void theBindingsSurviveAJsonRoundTrip() throws Exception {
        Settings s = new Settings();
        s.setKeyBindings(Map.of(
                "emacs", Map.of("file.save", List.of("C-x s", "f5"), "view.toggleZen", List.of()),
                "cua", Map.of("find.show", List.of("f8"))));
        s.setKeyBindingsMacOnly(Map.of("vscode", Map.of("file.save", List.of("C-Cmd-s"))));
        ObjectMapper mapper = new ObjectMapper();

        Settings loaded = mapper.readValue(mapper.writeValueAsBytes(s), Settings.class);

        assertEquals(s.getKeyBindings(), loaded.getKeyBindings());
        assertEquals(s.getKeyBindingsMacOnly(), loaded.getKeyBindingsMacOnly());
        assertTrue(
                loaded.getKeyBindings().get("emacs").get("view.toggleZen").isEmpty(),
                "an unbound command stays unbound");
    }

    @Test
    void resetToDefaultsPreservesTheBindings() {
        Settings s = new Settings();
        s.setKeyBindings(Map.of("cua", Map.of("file.save", List.of("C-k"))));
        s.setKeyBindingsMacOnly(Map.of("cua", Map.of("file.open", List.of("C-Cmd-o"))));
        s.setFontSize(22); // a page-owned setting Reset should clear

        Settings.resetToDefaults(s);

        assertEquals(14, s.getFontSize(), "an ordinary setting is reset to its default");
        assertEquals(
                Map.of("cua", Map.of("file.save", List.of("C-k"))),
                s.getKeyBindings(),
                "the Keymaps page owns its own reset");
        assertEquals(Map.of("cua", Map.of("file.open", List.of("C-Cmd-o"))), s.getKeyBindingsMacOnly());
    }
}
