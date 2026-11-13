package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.ui.UserKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Key bindings belong to the keymap they were made in: they are stored under it and apply only while it runs. */
class SettingsKeymapOverridesTest {

    private static final Map<String, Map<String, List<String>>> CUA_BINDINGS =
            Map.of("cua", Map.of("find.show", List.of("f8")));

    private static Settings cuaWithBindings() {
        Settings s = new Settings();
        s.setKeymap("cua");
        s.setKeyBindings(new LinkedHashMap<>(CUA_BINDINGS));
        return s;
    }

    @Test
    void aKeymapsBindingsWaitWithItWhileAnotherKeymapIsActive() {
        Settings s = cuaWithBindings();
        assertEquals("find.show", UserKeys.overrides(s).get("f8"), "precondition: in force under CUA");

        s.setKeymap("emacs");

        assertTrue(UserKeys.overrides(s).isEmpty(), "CUA's bindings do not apply to Emacs");
        assertEquals(CUA_BINDINGS, s.getKeyBindings(), "they are still CUA's");

        s.getKeyBindings().put("emacs", Map.of("file.save", List.of("C-x s"))); // a binding made while in Emacs
        s.setKeymap("cua");

        assertEquals("find.show", UserKeys.overrides(s).get("f8"), "CUA's come back with CUA");
        assertTrue(UserKeys.overrides(s).values().stream().noneMatch("file.save"::equals), "and Emacs's stay behind");
        assertEquals(Map.of("file.save", List.of("C-x s")), s.getKeyBindings().get("emacs"));
    }

    @Test
    void everyKeymapsBindingsSurviveASaveAndLoad() throws Exception {
        Settings s = cuaWithBindings();
        s.setKeymap("vscode");
        ObjectMapper mapper = new ObjectMapper();

        Settings loaded = mapper.readValue(mapper.writeValueAsBytes(s), Settings.class);

        assertEquals("vscode", loaded.getKeymap());
        assertTrue(UserKeys.overrides(loaded).isEmpty(), "loading a file switches nothing");
        assertEquals(CUA_BINDINGS, loaded.getKeyBindings());
        loaded.setKeymap("cua");
        assertEquals("find.show", UserKeys.overrides(loaded).get("f8"));
    }

    @Test
    void resetToDefaultsLeavesTheBindingsWithTheKeymapItLeaves() {
        Settings s = cuaWithBindings();
        String defaultKeymap = new Settings().getKeymap();

        Settings.resetToDefaults(s);

        assertEquals(defaultKeymap, s.getKeymap());
        assertTrue(UserKeys.overrides(s).isEmpty(), "CUA's bindings are not applied to the default keymap");
        assertEquals(CUA_BINDINGS, s.getKeyBindings(), "and they are not lost");
        s.setKeymap("cua");
        assertEquals("find.show", UserKeys.overrides(s).get("f8"));
    }
}
