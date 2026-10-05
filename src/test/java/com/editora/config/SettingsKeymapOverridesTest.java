package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Key-binding overrides belong to the keymap they were made in; switching keymap must not carry them over. */
class SettingsKeymapOverridesTest {

    private static Settings cuaWithOverrides() {
        Settings s = new Settings();
        s.setKeymap("cua");
        s.setKeybindings(new LinkedHashMap<>(Map.of("C-f", "", "<f7>", "find.show")));
        s.setKeybindingsMac(new LinkedHashMap<>(Map.of("Cmd-f", "")));
        return s;
    }

    @Test
    void switchingKeymapParksTheOverridesAndSwitchingBackRestoresThem() {
        Settings s = cuaWithOverrides();

        s.switchKeymap("emacs");

        assertEquals("emacs", s.getKeymap());
        assertTrue(s.keybindingsFor(false).isEmpty(), "CUA's overrides do not apply to Emacs");
        assertTrue(s.keybindingsFor(true).isEmpty());
        assertEquals(
                Map.of("C-f", "", "<f7>", "find.show"), s.getKeymapKeybindings().get("cua"));
        assertEquals(Map.of("Cmd-f", ""), s.getKeymapKeybindingsMac().get("cua"));

        s.keybindingsFor(false).put("C-t", "file.save"); // an override made while in Emacs
        s.switchKeymap("cua");

        assertEquals(Map.of("C-f", "", "<f7>", "find.show"), s.keybindingsFor(false));
        assertEquals(Map.of("Cmd-f", ""), s.keybindingsFor(true));
        assertFalse(s.getKeymapKeybindings().containsKey("cua"), "the active keymap's overrides are not kept twice");
        assertEquals(Map.of("C-t", "file.save"), s.getKeymapKeybindings().get("emacs"));
        assertFalse(s.getKeymapKeybindingsMac().containsKey("emacs"), "nothing to park is not parked");
    }

    @Test
    void choosingTheKeymapAlreadyInUseMovesNothing() {
        Settings s = cuaWithOverrides();
        s.switchKeymap("cua");
        assertEquals(2, s.keybindingsFor(false).size());
        assertTrue(s.getKeymapKeybindings().isEmpty());
    }

    @Test
    void parkedOverridesSurviveASaveAndLoad() throws Exception {
        Settings s = cuaWithOverrides();
        s.switchKeymap("vscode");
        ObjectMapper mapper = new ObjectMapper();

        Settings loaded = mapper.readValue(mapper.writeValueAsBytes(s), Settings.class);

        assertEquals("vscode", loaded.getKeymap());
        assertTrue(loaded.keybindingsFor(false).isEmpty(), "loading a file switches nothing");
        loaded.switchKeymap("cua");
        assertEquals(Map.of("C-f", "", "<f7>", "find.show"), loaded.keybindingsFor(false));
    }

    /**
     * What the v105→106 step relies on: a file written before overrides were per-keymap has only
     * {@code keybindings}, and those stay in force under the keymap the file names.
     */
    @Test
    void anOlderFilesOverridesStayWithTheKeymapItNames() throws Exception {
        Settings loaded = new ObjectMapper()
                .readValue(
                        "{\"schemaVersion\":105,\"keymap\":\"cua\",\"keybindings\":{\"<f7>\":\"find.show\"}}",
                        Settings.class);
        assertEquals(Map.of("<f7>", "find.show"), loaded.keybindingsFor(false));
        loaded.switchKeymap("emacs");
        assertTrue(loaded.keybindingsFor(false).isEmpty());
        assertEquals(Map.of("<f7>", "find.show"), loaded.getKeymapKeybindings().get("cua"));
    }

    @Test
    void resetToDefaultsParksTheOverridesUnderTheKeymapItLeaves() {
        Settings s = cuaWithOverrides();
        String defaultKeymap = new Settings().getKeymap();

        Settings.resetToDefaults(s);

        assertEquals(defaultKeymap, s.getKeymap());
        assertTrue(s.keybindingsFor(false).isEmpty(), "CUA's overrides are not applied to the default keymap");
        s.switchKeymap("cua");
        assertEquals(Map.of("C-f", "", "<f7>", "find.show"), s.keybindingsFor(false), "and they are not lost");
    }
}
