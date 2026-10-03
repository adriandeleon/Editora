package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.KeymapManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The user's own key bindings are the last layer: a plugin keymap cannot take back a chord the user bound. */
class KeymapLayersTest {

    private static final String CHORD = "C-c C-j";

    @Test
    void aUserBindingBeatsAPluginBindingOnTheSameChord() {
        KeymapManager keymap = new KeymapManager();
        Map<String, String> user = Map.of(CHORD, "file.save");
        Map<String, String> plugin = Map.of(CHORD, "plugin.demo.run", "C-c C-y", "plugin.demo.other");

        KeymapLayers.rebuild(keymap, "emacs", List.of(plugin), user);

        assertEquals("file.save", keymap.commandFor(CHORD), "the user's rebind wins");
        assertEquals("plugin.demo.other", keymap.commandFor("C-c C-y"), "the plugin's other chords still bind");
    }

    @Test
    void aPluginMayStillUseAChordTheUserOnlyFreed() {
        KeymapManager base = new KeymapManager();
        base.loadNamed("emacs");
        String defaultChord = base.bindings().keySet().iterator().next();
        Map<String, String> user = new LinkedHashMap<>();
        user.put(defaultChord, ""); // an unbind: the user moved that command elsewhere

        KeymapManager keymap = new KeymapManager();
        KeymapLayers.rebuild(keymap, "emacs", List.of(), user);
        assertNull(keymap.commandFor(defaultChord), "the default is suppressed");

        KeymapLayers.rebuild(keymap, "emacs", List.of(Map.of(defaultChord, "plugin.demo.run")), user);
        assertEquals("plugin.demo.run", keymap.commandFor(defaultChord), "a freed chord is not kept from plugins");
    }

    @Test
    void userBindingsLeaveOutTheUnbindEntries() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("C-a", "nav.lineStart");
        user.put("C-b", "");
        user.put("C-d", null);
        assertEquals(Map.of("C-a", "nav.lineStart"), KeymapLayers.userBindings(user));
        assertEquals(Map.of(), KeymapLayers.userBindings(null));
    }
}
