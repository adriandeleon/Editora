package com.editora.sync;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key-binding overrides of {@code settings.json} as the sync repository's {@code keybindings.json}. */
class KeybindingsFileTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static Settings emacsWithOverrides() {
        Settings s = new Settings();
        s.setKeymap("emacs");
        s.setKeybindings(map("C-x s", "file.save", "C-x C-s", ""));
        s.setKeybindingsMac(map("Cmd-k", "editor.killLine"));
        s.setKeymapKeybindings(new LinkedHashMap<>(Map.of("cua", map("C-q", "app.quit"))));
        return s;
    }

    @Test
    void noOverridesIsNoFile() {
        assertNull(KeybindingsFile.render(new Settings()));
    }

    @Test
    void everyKeymapAndBothPlatformsAreRenderedSortedAndUnbindsStayBlank() {
        assertEquals("""
                {
                  "formatVersion" : 1,
                  "keymaps" : {
                    "cua" : {
                      "windowsLinux" : {
                        "C-q" : "app.quit"
                      }
                    },
                    "emacs" : {
                      "macOS" : {
                        "Cmd-k" : "editor.killLine"
                      },
                      "windowsLinux" : {
                        "C-x C-s" : "",
                        "C-x s" : "file.save"
                      }
                    }
                  }
                }
                """, KeybindingsFile.render(emacsWithOverrides()));
    }

    @Test
    void theRenderedFileIsAlreadyInTheFormTheMergeWrites() throws IOException {
        String text = KeybindingsFile.render(emacsWithOverrides());
        assertEquals(text, KeybindingsFile.text(KeybindingsFile.entries(text)));
        assertEquals(text, SyncMerge.normalize(SyncCategory.KEYBINDINGS, text));
    }

    @Test
    void aComputerOnAnotherKeymapGetsThatKeymapsOverridesActiveAndParksTheRest() throws IOException {
        String text = KeybindingsFile.render(emacsWithOverrides());
        Settings cua = new Settings();
        cua.setKeymap("cua");
        cua.setKeybindings(map("C-o", "file.open")); // replaced: the file is the whole truth after a merge

        KeybindingsFile.apply(cua, text);

        assertEquals(map("C-q", "app.quit"), cua.getKeybindings());
        assertTrue(cua.getKeybindingsMac().isEmpty());
        assertEquals(
                map("C-x C-s", "", "C-x s", "file.save"),
                cua.getKeymapKeybindings().get("emacs"));
        assertEquals(
                map("Cmd-k", "editor.killLine"), cua.getKeymapKeybindingsMac().get("emacs"));
        assertNull(cua.getKeymapKeybindings().get("cua"), "the active keymap is never parked");
        assertEquals(text, KeybindingsFile.render(cua), "and it renders back to the same file");
    }

    @Test
    void noFileClearsEveryOverride() throws IOException {
        Settings s = emacsWithOverrides();
        KeybindingsFile.apply(s, null);
        assertNull(KeybindingsFile.render(s));
    }

    @Test
    void aNewerFormatOrAnotherShapeIsRefused() {
        assertThrows(
                KeybindingsFile.NewerFormatException.class,
                () -> KeybindingsFile.entries("{\"formatVersion\":2,\"keymaps\":{}}"));
        assertThrows(IOException.class, () -> KeybindingsFile.entries("[]"));
        assertThrows(IOException.class, () -> KeybindingsFile.entries("{\"keymaps\":[]}"));
    }

    @Test
    void labelsNameTheKeymapThePlatformWhenMacAndTheChord() throws IOException {
        Map<String, String> entries = KeybindingsFile.entries(KeybindingsFile.render(emacsWithOverrides()));
        assertEquals(
                java.util.List.of("cua: C-q", "emacs: C-x C-s", "emacs: C-x s", "emacs (macOS): Cmd-k"),
                entries.keySet().stream().map(KeybindingsFile::label).toList());
    }
}
