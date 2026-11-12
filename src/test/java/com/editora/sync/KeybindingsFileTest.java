package com.editora.sync;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The key bindings of {@code settings.json} as the sync repository's {@code keybindings.json}. */
class KeybindingsFileTest {

    /** Command, its chords joined by commas ("" for unbound), command, … */
    private static Map<String, List<String>> bindings(String... pairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1].isEmpty() ? List.of() : List.of(pairs[i + 1].split(",")));
        }
        return out;
    }

    private static Settings emacsWithBindings() {
        Settings s = new Settings();
        s.setKeymap("emacs");
        Map<String, Map<String, List<String>>> shared = new LinkedHashMap<>();
        shared.put("emacs", bindings("file.save", "C-x s,f5", "view.toggleZen", ""));
        shared.put("cua", bindings("app.quit", "C-M-q"));
        s.setKeyBindings(shared);
        s.setKeyBindingsMacOnly(new LinkedHashMap<>(Map.of("emacs", bindings("edit.killLine", "Cmd-k"))));
        return s;
    }

    @Test
    void noBindingsIsNoFile() {
        assertNull(KeybindingsFile.render(new Settings()));
    }

    @Test
    void everyKeymapIsRenderedSortedWithTheMacsOwnChordsApartAndAnUnboundCommandEmpty() {
        assertEquals("""
                {
                  "formatVersion" : 2,
                  "keymaps" : {
                    "cua" : {
                      "shared" : {
                        "app.quit" : [ "C-M-q" ]
                      }
                    },
                    "emacs" : {
                      "macOnly" : {
                        "edit.killLine" : [ "Cmd-k" ]
                      },
                      "shared" : {
                        "file.save" : [ "C-x s", "f5" ],
                        "view.toggleZen" : [ ]
                      }
                    }
                  }
                }
                """, KeybindingsFile.render(emacsWithBindings()));
    }

    @Test
    void theRenderedFileIsAlreadyInTheFormTheMergeWrites() throws IOException {
        String text = KeybindingsFile.render(emacsWithBindings());
        assertEquals(text, KeybindingsFile.text(KeybindingsFile.entries(text)));
        assertEquals(text, SyncMerge.normalize(SyncCategory.KEYBINDINGS, text));
    }

    @Test
    void anEntryIsOneCommandOfOneKeymapWithItsChords() throws IOException {
        assertEquals(
                Map.of(
                        "cua\tshared\tapp.quit", "C-M-q",
                        "emacs\tshared\tfile.save", "C-x s\nf5",
                        "emacs\tshared\tview.toggleZen", "",
                        "emacs\tmacOnly\tedit.killLine", "Cmd-k"),
                KeybindingsFile.entries(KeybindingsFile.render(emacsWithBindings())));
    }

    @Test
    void aComputerOnAnotherKeymapTakesEveryKeymapsBindingsAndKeepsItsKeymap() throws IOException {
        Settings source = emacsWithBindings();
        String text = KeybindingsFile.render(source);
        Settings cua = new Settings();
        cua.setKeymap("cua");
        // Replaced: the file is the whole truth after a merge.
        cua.setKeyBindings(new LinkedHashMap<>(Map.of("cua", bindings("file.open", "C-M-o"))));

        KeybindingsFile.apply(cua, text);

        assertEquals("cua", cua.getKeymap(), "which keymap is active stays this computer's choice");
        assertEquals(source.getKeyBindings(), cua.getKeyBindings());
        assertEquals(source.getKeyBindingsMacOnly(), cua.getKeyBindingsMacOnly());
        assertTrue(cua.getKeyBindings().get("emacs").get("view.toggleZen").isEmpty(), "unbound stays unbound");
        assertEquals(text, KeybindingsFile.render(cua), "and it renders back to the same file");
    }

    @Test
    void noFileClearsEveryBinding() throws IOException {
        Settings s = emacsWithBindings();
        KeybindingsFile.apply(s, null);
        assertNull(KeybindingsFile.render(s));
        assertTrue(s.getKeyBindings().isEmpty());
        assertTrue(s.getKeyBindingsMacOnly().isEmpty());
    }

    /**
     * Layout 1 kept overrides by chord, apart for macOS and for Windows and Linux. A binding made on one system
     * becomes everyone's, one both agree on is stored once, and where the two differ, or the chord is one only a
     * Mac can type, the Mac keeps its own under {@code macOnly}.
     */
    @Test
    void aLayout1FileIsReadAndConverted() throws IOException {
        String layout1 = """
                {
                  "formatVersion" : 1,
                  "keymaps" : {
                    "vscode" : {
                      "windowsLinux" : {
                        "C-s" : "", "C-M-S-f11" : "file.save",
                        "C-z" : "", "C-M-S-f10" : "edit.undo",
                        "C-y" : "", "C-M-S-f9" : "edit.redo"
                      },
                      "macOS" : {
                        "Cmd-s" : "", "M-Cmd-S-f11" : "file.save",
                        "Cmd-c" : "", "M-Cmd-S-f8" : "edit.copy",
                        "Cmd-S-z" : "", "M-Cmd-S-f7" : "edit.redo",
                        "Cmd-v" : "", "C-Cmd-v" : "edit.paste"
                      }
                    },
                    "emacs" : {
                      "windowsLinux" : { "C-x C-s" : "", "C-x s" : "file.save" }
                    }
                  }
                }
                """;
        Settings s = new Settings();

        KeybindingsFile.apply(s, layout1);

        assertEquals(
                Map.of(
                        "vscode",
                        Map.of(
                                "file.save", List.of("C-M-S-f11"), // the same on both: once
                                "edit.undo", List.of("C-M-S-f10"), // made on Windows or Linux
                                "edit.copy", List.of("C-M-S-f8"), // made on the Mac, as the others write it
                                "edit.redo", List.of("C-M-S-f9")), // they differ: this is the shared one
                        "emacs",
                        Map.of("file.save", List.of("C-x s"))),
                s.getKeyBindings());
        assertEquals(
                Map.of(
                        "vscode",
                        Map.of(
                                "edit.redo", List.of("M-Cmd-S-f7"), // the Mac's own, beside the shared one
                                "edit.paste", List.of("C-Cmd-v"))), // the real Control key: only a Mac has it
                s.getKeyBindingsMacOnly());
        String rendered = KeybindingsFile.render(s);
        assertTrue(rendered.contains("\"formatVersion\" : 2"), "it is written back in the current layout");
        assertEquals(KeybindingsFile.entries(layout1), KeybindingsFile.entries(rendered));
    }

    @Test
    void aFileWithoutAFormatVersionIsReadAsLayout1() throws IOException {
        assertEquals(
                Map.of("emacs\tshared\tfile.save", "C-x s"),
                KeybindingsFile.entries(
                        "{\"keymaps\":{\"emacs\":{\"windowsLinux\":{\"C-x C-s\":\"\",\"C-x s\":\"file.save\"}}}}"));
    }

    @Test
    void aNewerFormatOrAnotherShapeIsRefused() {
        assertThrows(
                KeybindingsFile.NewerFormatException.class,
                () -> KeybindingsFile.entries(
                        "{\"formatVersion\":" + (KeybindingsFile.FORMAT_VERSION + 1) + ",\"keymaps\":{}}"));
        assertThrows(IOException.class, () -> KeybindingsFile.entries("[]"));
        assertThrows(IOException.class, () -> KeybindingsFile.entries("{\"keymaps\":[]}"));
    }

    @Test
    void labelsNameTheKeymapTheCommandAndWhenTheChordsAreTheMacsOwn() throws IOException {
        Map<String, String> entries = KeybindingsFile.entries(KeybindingsFile.render(emacsWithBindings()));
        assertEquals(
                List.of(
                        "cua: app.quit",
                        "emacs: file.save",
                        "emacs: view.toggleZen",
                        "emacs (macOS only): edit.killLine"),
                entries.keySet().stream().map(KeybindingsFile::label).toList());
    }
}
