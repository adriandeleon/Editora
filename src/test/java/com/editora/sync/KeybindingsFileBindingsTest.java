package com.editora.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The FX thread hands the sync thread a copy of the bindings (memory only); the file is rendered and parsed there
 * (PERF-01, Pauta 6.0.1). The copy, its file and its parse agree with the settings' own.
 */
class KeybindingsFileBindingsTest {

    private static Settings withBinding(String keymap, String command, String chord) {
        Settings s = new Settings();
        Map<String, Map<String, List<String>>> shared = new LinkedHashMap<>();
        shared.put(keymap, new LinkedHashMap<>(Map.of(command, new ArrayList<>(List.of(chord)))));
        s.setKeyBindings(shared);
        return s;
    }

    @Test
    void aCopyRendersAsTheSettingsDoAndParsesBack() throws Exception {
        Settings s = withBinding("default", "file.save", "Ctrl+Alt+S");
        KeybindingsFile.Bindings copy = KeybindingsFile.Bindings.of(s);
        String text = KeybindingsFile.render(copy);
        assertEquals(KeybindingsFile.render(s), text);
        KeybindingsFile.Bindings parsed = KeybindingsFile.parse(text);
        assertEquals(copy, parsed);

        Settings into = new Settings();
        parsed.applyTo(into);
        assertEquals(text, KeybindingsFile.render(into));
    }

    @Test
    void theCopyDoesNotFollowLaterEditsAndComparesByContent() {
        Settings s = withBinding("default", "file.save", "Ctrl+Alt+S");
        KeybindingsFile.Bindings before = KeybindingsFile.Bindings.of(s);
        assertEquals(before, KeybindingsFile.Bindings.of(s), "the same bindings are equal");
        s.getKeyBindings().get("default").put("file.open", new ArrayList<>(List.of("Ctrl+Alt+O")));
        assertNotEquals(before, KeybindingsFile.Bindings.of(s), "an edit is a change");
        assertEquals(1, before.shared().get("default").size(), "the copy kept what it was");
    }

    @Test
    void noFileMeansNoBindings() throws Exception {
        KeybindingsFile.Bindings none = KeybindingsFile.parse(null);
        assertNull(KeybindingsFile.render(none));
    }
}
