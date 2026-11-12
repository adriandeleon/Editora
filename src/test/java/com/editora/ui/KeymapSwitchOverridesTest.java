package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.KeybindingEdits;
import com.editora.command.KeymapManager;
import com.editora.command.UserKeyBindings;
import com.editora.command.UserKeyBindings.Store;
import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A switch of keymap leaves an ordinary command's binding with the keymap it was made in (rebinding Find in CUA
 * must not unbind forward-char, which is what CUA's Find chord means in Emacs), and takes along the binding of a
 * command no keymap binds.
 */
class KeymapSwitchOverridesTest {

    private static KeymapManager rebuild(Settings settings) {
        KeymapManager keymap = new KeymapManager();
        KeymapLayers.rebuild(keymap, settings, List.of());
        return keymap;
    }

    /** Makes {@code shared} the bindings of {@code keymap}: command, chord, command, … */
    private static void bind(Settings settings, String keymap, String... pairs) {
        Store store = UserKeys.store(settings, keymap);
        for (int i = 0; i < pairs.length; i += 2) {
            store.shared().put(pairs[i], List.of(pairs[i + 1]));
        }
        UserKeys.put(settings, keymap, store);
    }

    /** A stored chord as this system types it in the active keymap: Cmd for Ctrl on a Mac, where it has a variant. */
    private static String here(Settings settings, String chord) {
        return KeymapManager.isMac()
                ? UserKeyBindings.toMac(chord, KeymapManager.hasMacVariant(UserKeys.keymap(settings)))
                : chord;
    }

    private static Map<String, List<String>> shared(Settings settings, String keymap) {
        return UserKeys.store(settings, keymap).shared();
    }

    @Test
    void aRebindMadeInOneKeymapStaysWithItAndComesBackWithIt() {
        Settings settings = new Settings();
        settings.setKeymap("cua");
        KeymapManager base = new KeymapManager();
        base.loadNamed("cua");
        String findChord =
                KeybindingEdits.defaultChords(base.bindings(), "find.show").get(0);
        UserKeys.edit(
                settings,
                base.bindings(),
                KeybindingEdits.rebind(base.bindings(), UserKeys.overrides(settings), "find.show", "f8"));
        assertEquals(Map.of("find.show", List.of("f8")), shared(settings, "cua"), "stored under CUA, by command");
        assertNull(rebuild(settings).bindings().get(findChord), "precondition: the default is unbound");
        assertEquals("find.show", rebuild(settings).bindings().get("f8"));

        KeymapLayers.switchKeymap(settings, "emacs");

        assertEquals("emacs", settings.getKeymap());
        KeymapManager emacsDefaults = new KeymapManager();
        emacsDefaults.loadNamed("emacs");
        assertEquals(emacsDefaults.bindings(), rebuild(settings).bindings(), "Emacs is the stock Emacs keymap");
        assertTrue(UserKeys.store(settings, "emacs").isEmpty(), "nothing of CUA's went to Emacs");
        assertEquals(Map.of("find.show", List.of("f8")), shared(settings, "cua"), "it waits with CUA");

        KeymapLayers.switchKeymap(settings, "cua");
        assertEquals("find.show", rebuild(settings).bindings().get("f8"), "the CUA rebind comes back with CUA");
        assertNull(rebuild(settings).bindings().get(findChord));
    }

    @Test
    void choosingTheKeymapAlreadyInUseMovesNothing() {
        Settings settings = new Settings();
        settings.setKeymap("cua");
        bind(settings, "cua", "find.show", "f8", "macro.run.m1", "C-M-S-f12");
        bind(settings, "emacs", "macro.run.m2", "C-M-S-f11");
        Map<String, Map<String, List<String>>> before = new LinkedHashMap<>(settings.getKeyBindings());

        KeymapLayers.switchKeymap(settings, "cua");

        assertEquals("cua", settings.getKeymap());
        assertEquals(before, settings.getKeyBindings());
    }

    @Test
    void anUnknownConfiguredNameKeepsItsBindingsUnderTheKeymapThatWasRunning() {
        Settings settings = new Settings();
        settings.setKeymap("vim"); // not bundled: the default keymap is what actually runs
        KeymapManager base = new KeymapManager();
        base.loadNamed(settings.getKeymap());
        UserKeys.edit(
                settings,
                base.bindings(),
                KeybindingEdits.rebind(base.bindings(), UserKeys.overrides(settings), "find.show", "f8"));
        assertEquals(List.of("f8"), shared(settings, KeymapManager.DEFAULT).get("find.show"));
        assertFalse(settings.getKeyBindings().containsKey("vim"));

        KeymapLayers.switchKeymap(settings, "cua");

        assertEquals(List.of("f8"), shared(settings, KeymapManager.DEFAULT).get("find.show"));
        assertTrue(UserKeys.store(settings, "cua").isEmpty());
        KeymapLayers.switchKeymap(settings, KeymapManager.DEFAULT);
        assertEquals("find.show", rebuild(settings).bindings().get("f8"));
    }

    /**
     * A macro's shortcut has nothing to do with a keymap, so it follows a switch, unless the new keymap's own
     * chords make it dead: then it stays with the old keymap and comes back with it.
     */
    @Test
    void aShortcutOfACommandNoKeymapBindsFollowsTheSwitch() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        bind(
                settings,
                "emacs",
                "macro.run.m1",
                "C-M-S-f12",
                "macro.run.m2",
                "C-c m", // under CUA, C-c is Copy: unreachable
                "file.save",
                "C-x s"); // an ordinary command's binding stays with Emacs

        KeymapLayers.switchKeymap(settings, "cua");
        assertEquals(Map.of("macro.run.m1", List.of("C-M-S-f12")), shared(settings, "cua"), "it follows");
        assertEquals(
                Map.of("macro.run.m2", List.of("C-c m"), "file.save", List.of("C-x s")),
                shared(settings, "emacs"),
                "a chord CUA makes dead stays behind, as an ordinary command's binding does");
        assertEquals("macro.run.m1", rebuild(settings).bindings().get(here(settings, "C-M-S-f12")));

        bind(settings, "cua", "macro.run.m1", "C-M-S-f11"); // rebound while in CUA
        KeymapLayers.switchKeymap(settings, "emacs");
        assertEquals(
                Map.of(
                        "macro.run.m1",
                        List.of("C-M-S-f11"),
                        "macro.run.m2",
                        List.of("C-c m"),
                        "file.save",
                        List.of("C-x s")),
                shared(settings, "emacs"),
                "the macro arrives with its new chord; the old one does not come back");
        assertFalse(settings.getKeyBindings().containsKey("cua"), "a keymap left with no bindings has no entry");
        KeymapManager emacs = rebuild(settings);
        assertEquals("macro.run.m1", emacs.bindings().get("C-M-S-f11"));
        assertNull(emacs.bindings().get("C-M-S-f12"));
        assertEquals("macro.run.m2", emacs.bindings().get("C-c m"));
        assertEquals("file.save", emacs.bindings().get("C-x s"));
    }

    @Test
    void aMacroAlreadyBoundInTheKeymapEnteredKeepsThatBinding() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        bind(settings, "emacs", "macro.run.m1", "C-M-S-f12");
        bind(settings, "cua", "macro.run.m1", "C-M-S-f11");

        KeymapLayers.switchKeymap(settings, "cua");

        assertEquals(Map.of("macro.run.m1", List.of("C-M-S-f11")), shared(settings, "cua"));
        assertEquals(Map.of("macro.run.m1", List.of("C-M-S-f12")), shared(settings, "emacs"), "each keeps its own");
    }

    @Test
    void aMacOnlyShortcutOfAMacroFollowsTheSwitchToo() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        Store emacs = UserKeys.store(settings, "emacs");
        emacs.macOnly().put("macro.run.m1", List.of("Cmd-f11"));
        UserKeys.put(settings, "emacs", emacs);

        KeymapLayers.switchKeymap(settings, "cua");

        assertEquals(
                Map.of("macro.run.m1", List.of("Cmd-f11")),
                UserKeys.store(settings, "cua").macOnly());
        assertFalse(settings.getKeyBindingsMacOnly().containsKey("emacs"), "moved, not copied");
    }

    /**
     * Emacs has no macOS form and CUA has one, so the same stored chord is Ctrl on a Mac in one and Cmd in the
     * other. A macro's binding that moves between them keeps the keys the user presses, on a Mac as elsewhere.
     */
    @Test
    void aMacrosChordKeepsTheKeysPressedOnEverySystemWhenTheKeymapsDifferInTheirMacForm() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        bind(settings, "emacs", "macro.run.m1", "C-M-S-f12");

        KeymapLayers.switchKeymap(settings, "cua");

        Store cua = UserKeys.store(settings, "cua");
        assertEquals(
                Map.of("macro.run.m1", List.of("C-M-S-f12")), cua.shared(), "Ctrl on Windows and Linux, as before");
        assertEquals(
                Map.of("macro.run.m1", List.of("C-M-S-f12")),
                cua.macOnly(),
                "and still Ctrl on a Mac, where CUA would read the shared chord with Cmd");

        KeymapLayers.switchKeymap(settings, "emacs");

        Store emacs = UserKeys.store(settings, "emacs");
        assertEquals(Map.of("macro.run.m1", List.of("C-M-S-f12")), emacs.shared());
        assertTrue(emacs.macOnly().isEmpty(), "Emacs reads the shared chord with Ctrl on a Mac: nothing to keep apart");
    }

    @Test
    void aMacOnlyChordIsNotCarriedOntoOneASharedBindingAlreadyUsesOnAMac() {
        Settings settings = new Settings();
        settings.setKeymap("emacs");
        Store emacs = UserKeys.store(settings, "emacs");
        emacs.macOnly().put("macro.run.m1", List.of("Cmd-f11"));
        UserKeys.put(settings, "emacs", emacs);
        bind(settings, "cua", "find.show", "C-f11"); // Cmd-f11 on a Mac, in CUA

        KeymapLayers.switchKeymap(settings, "cua");

        assertTrue(UserKeys.store(settings, "cua").macOnly().isEmpty(), "the chord is taken there on a Mac");
        assertEquals(
                Map.of("macro.run.m1", List.of("Cmd-f11")),
                UserKeys.store(settings, "emacs").macOnly(),
                "so the binding stays with the keymap it was made in");
    }
}
