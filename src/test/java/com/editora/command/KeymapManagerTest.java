package com.editora.command;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapManagerTest {

    private KeymapManager keymap;

    @BeforeEach
    void setUp() {
        keymap = new KeymapManager();
        keymap.loadNamed("emacs");
    }

    @Test
    void resolvesMultiKeyChord() {
        assertEquals("file.save", keymap.commandFor("C-x C-s"));
        assertEquals("file.find", keymap.commandFor("C-x C-f"));
    }

    @Test
    void resolvesSingleChord() {
        assertEquals("palette.show", keymap.commandFor("M-x"));
    }

    @Test
    void recognizesPrefixButNotFullBinding() {
        assertTrue(keymap.isPrefix("C-x"));
        assertNull(keymap.commandFor("C-x"));
    }

    @Test
    void fullBindingIsNotAPrefix() {
        assertFalse(keymap.isPrefix("M-x"));
    }

    @Test
    void userOverridesTakePrecedence() {
        keymap.applyOverrides(Map.of("C-x C-s", "custom.save"));
        assertEquals("custom.save", keymap.commandFor("C-x C-s"));
    }

    // --- an unknown Settings.keymap falls back instead of aborting startup ---

    @Test
    void resolveNameKeepsBundledNamesAndFallsBackForEverythingElse() {
        for (String id : KeymapManager.AVAILABLE.keySet()) {
            assertEquals(id, KeymapManager.resolveName(id));
        }
        assertEquals(KeymapManager.DEFAULT, KeymapManager.resolveName("vim"));
        assertEquals(KeymapManager.DEFAULT, KeymapManager.resolveName("Emacs")); // names are case-sensitive ids
        assertEquals(KeymapManager.DEFAULT, KeymapManager.resolveName(""));
        assertEquals(KeymapManager.DEFAULT, KeymapManager.resolveName(null));
    }

    @Test
    void loadingAnUnknownNameLoadsTheDefaultAndReportsItOnce() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("vim", false); // used to throw IllegalArgumentException and abort the launch
        assertEquals(KeymapManager.DEFAULT, km.activeName());
        assertEquals("file.save", km.commandFor("C-x C-s"), "the default keymap's bindings are live");
        assertEquals("vim", km.takeUnknownName());
        assertNull(km.takeUnknownName(), "reported once");
        km.loadNamed("vim", false); // a reload with the same bad setting must not nag again
        assertNull(km.takeUnknownName());
        km.loadNamed("cua", false);
        assertNull(km.takeUnknownName(), "a valid name is not a problem");
        km.loadNamed("vim", false);
        assertEquals("vim", km.takeUnknownName(), "…but going back to a bad name is reported afresh");
    }

    @Test
    void aNullNameAlsoFallsBack() {
        KeymapManager km = new KeymapManager();
        km.loadNamed(null, false);
        assertEquals(KeymapManager.DEFAULT, km.activeName());
        assertFalse(km.bindings().isEmpty());
        assertEquals("null", km.takeUnknownName());
    }

    @Test
    void availableKeymapsKeepTheirDisplayOrder() {
        // Map.copyOf scrambled this; the Settings combo and the keymap picker list it as declared.
        assertEquals(
                List.of("emacs", "cua", "sublime", "vscode", "intellij"),
                List.copyOf(KeymapManager.AVAILABLE.keySet()));
    }

    // --- the chord shown for a command is stable and typable ---

    @Test
    void bindingsIterateInKeymapFileOrder() {
        List<String> first = List.copyOf(keymap.bindings().keySet()).subList(0, 3);
        assertEquals(List.of("C-x C-f", "C-x C-s", "C-x C-w"), first);
        keymap.applyOverrides(Map.of("C-c C-c C-c", "file.save"));
        List<String> all = List.copyOf(keymap.bindings().keySet());
        assertEquals("C-c C-c C-c", all.get(all.size() - 1), "an override is appended after the base bindings");
    }

    @Test
    void theDisplayedChordPrefersOneTypableOnThisPlatform() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false);
        // search.everywhere is bound to both M-S-x and Cmd-S-e; a Windows/Linux keyboard has no Command key.
        km.applyOverrides(Map.of("M-S-x", KeymapManager.UNBIND));
        km.applyOverrides(orderedOf("Cmd-S-j", "search.everywhere", "C-S-j", "search.everywhere"));
        assertEquals("C-S-j", km.chordFor("search.everywhere"), "the Cmd chord is listed first but is not typable");
        assertEquals("C-S-j", km.displayChord("search.everywhere"));

        KeymapManager mac = new KeymapManager();
        mac.loadNamed("emacs", true);
        mac.applyOverrides(Map.of("M-S-x", KeymapManager.UNBIND));
        assertEquals("Cmd-S-e", mac.chordFor("search.everywhere"), "on macOS the Cmd chord is typable");
    }

    @Test
    void aCommandOnlyBoundToAnUntypableChordStillShowsIt() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", false);
        km.applyOverrides(Map.of("Cmd-S-9", "file.save", "C-x C-s", KeymapManager.UNBIND));
        assertEquals("Cmd-S-9", km.chordFor("file.save"));
    }

    @Test
    void firstBindingWinsAndUnboundCommandsHaveNoChord() {
        assertEquals("C-/", keymap.chordFor("edit.undo"), "the first of several bindings, every launch");
        assertNull(keymap.chordFor("no.such.command"));
        assertNull(keymap.displayChord("no.such.command"));
    }

    @Test
    void displayChordsUseTheActiveKeymapsNotation() {
        assertEquals("C-x C-s", keymap.displayChord("file.save")); // Emacs keymap: Emacs notation

        KeymapManager cua = new KeymapManager();
        cua.loadNamed("cua", false);
        assertEquals("Ctrl+Shift+P", cua.displayChord("palette.show"));
        assertEquals("Ctrl+K Ctrl+W", cua.display("C-k C-w"));

        KeymapManager mac = new KeymapManager();
        mac.loadNamed("cua", true);
        assertEquals("⇧⌘P", mac.displayChord("palette.show"));
    }

    @Test
    void displayChordsFollowOverridesAndReloads() {
        KeymapManager km = new KeymapManager();
        km.loadNamed("cua", false);
        assertEquals("Ctrl+S", km.displayChords().get("file.save"));
        km.applyOverrides(Map.of("C-s", KeymapManager.UNBIND, "f9", "file.save"));
        assertEquals("F9", km.displayChords().get("file.save"), "the cache is dropped when bindings change");
        km.loadNamed("emacs", false);
        assertEquals("C-x C-s", km.displayChords().get("file.save"));
    }

    private static Map<String, String> orderedOf(String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
