package com.editora.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.UserKeyBindings.Derived;
import com.editora.command.UserKeyBindings.Store;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The user's key bindings are kept once, by command, and each system's keymap is worked out from them: a
 * binding made on a Mac is the same binding on Linux and Windows, and the other way round.
 */
class UserKeyBindingsTest {

    private static Map<String, String> base(String keymap, boolean mac) {
        return KeymapManager.defaults(keymap, mac);
    }

    /** The bindings a keymap ends up with once {@code overrides} are laid over {@code base}. */
    private static Map<String, String> applied(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> out = new LinkedHashMap<>(base);
        overrides.forEach((chord, command) -> {
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

    @Test
    void aVariantKeymapPutsCmdWhereTheOtherHasCtrl() {
        assertEquals("Cmd-s", UserKeyBindings.toMac("C-s", true));
        assertEquals("M-Cmd-f", UserKeyBindings.toMac("C-M-f", true));
        assertEquals("Cmd-S-z", UserKeyBindings.toMac("C-S-z", true));
        assertEquals("Cmd-k Cmd-s", UserKeyBindings.toMac("C-k C-s", true));
        assertEquals("Cmd--", UserKeyBindings.toMac("C--", true), "Ctrl and the minus key");
        assertEquals("f3", UserKeyBindings.toMac("f3", true));
        assertEquals("M-S-h", UserKeyBindings.toMac("M-S-h", true));

        assertEquals("C-s", UserKeyBindings.toShared("Cmd-s", true));
        assertEquals("C-M-f", UserKeyBindings.toShared("M-Cmd-f", true));
        assertEquals("C-k C-s", UserKeyBindings.toShared("Cmd-k Cmd-s", true));
        assertEquals("f3", UserKeyBindings.toShared("f3", true));
    }

    @Test
    void theEmacsKeymapHasNoVariantSoItsChordsAreTheSameEverywhere() {
        assertEquals("C-x C-s", UserKeyBindings.toMac("C-x C-s", false));
        assertEquals("C-x C-s", UserKeyBindings.toShared("C-x C-s", false));
    }

    @Test
    void aChordOnlyAMacCanTypeHasNoSharedForm() {
        assertNull(UserKeyBindings.toShared("C-g", true), "the real Control key, in a keymap that swaps it for Cmd");
        assertNull(UserKeyBindings.toShared("C-Cmd-h", true));
        assertNull(UserKeyBindings.toShared("Cmd-k", false), "Command, in a keymap that keeps Ctrl");
    }

    /** What the split by system was for (#439): a rebind made on one system must not double-bind on the other. */
    @Test
    void aRebindMadeOnLinuxMovesTheCommandOnAMacTooAndLeavesNoOldChordBehind() {
        Map<String, String> linux = base("vscode", false);
        Map<String, String> mac = base("vscode", true);
        assertEquals(List.of("C-s"), chordsOf(linux, "file.save"), "precondition");
        assertEquals(List.of("Cmd-s"), chordsOf(mac, "file.save"), "precondition");

        Map<String, String> edited = KeybindingEdits.rebind(linux, Map.of(), "file.save", "C-M-s");
        Store store = UserKeyBindings.edit(Store.empty(), linux, false, true, edited);
        assertEquals(Map.of("file.save", List.of("C-M-s")), store.shared());
        assertTrue(store.macOnly().isEmpty());

        Map<String, String> onMac =
                applied(mac, UserKeyBindings.derive(store, mac, true, true).overrides());
        assertEquals(List.of("M-Cmd-s"), chordsOf(onMac, "file.save"), "moved, in the Mac's form");
        assertNull(onMac.get("Cmd-s"), "and the Mac's old default is gone, not left bound beside it");

        Map<String, String> onLinux =
                applied(linux, UserKeyBindings.derive(store, linux, false, true).overrides());
        assertEquals(List.of("C-M-s"), chordsOf(onLinux, "file.save"));
        assertNull(onLinux.get("C-s"));
    }

    @Test
    void aRebindMadeOnAMacMovesTheCommandOnLinuxToo() {
        Map<String, String> linux = base("cua", false);
        Map<String, String> mac = base("cua", true);
        Map<String, String> edited = KeybindingEdits.rebind(mac, Map.of(), "edit.redo", "Cmd-y");
        Store store = UserKeyBindings.edit(Store.empty(), mac, true, true, edited);
        assertEquals(Map.of("edit.redo", List.of("C-y")), store.shared(), "stored as Windows and Linux write it");

        Map<String, String> onLinux =
                applied(linux, UserKeyBindings.derive(store, linux, false, true).overrides());
        assertEquals(List.of("C-y"), chordsOf(onLinux, "edit.redo"));
        Map<String, String> onMac =
                applied(mac, UserKeyBindings.derive(store, mac, true, true).overrides());
        assertEquals(List.of("Cmd-y"), chordsOf(onMac, "edit.redo"));
        assertNull(onMac.get("Cmd-S-z"), "the Mac's own default for Redo is gone");
    }

    @Test
    void aChordOnlyAMacCanTypeStaysTheMacsOwnAndTheOthersKeepTheirs() {
        Map<String, String> linux = base("vscode", false);
        Map<String, String> mac = base("vscode", true);
        Map<String, String> edited = KeybindingEdits.rebind(mac, Map.of(), "file.save", "C-Cmd-s");
        Store store = UserKeyBindings.edit(Store.empty(), mac, true, true, edited);
        assertTrue(store.shared().isEmpty());
        assertEquals(Map.of("file.save", List.of("C-Cmd-s")), store.macOnly());

        Derived onLinux = UserKeyBindings.derive(store, linux, false, true);
        assertTrue(onLinux.overrides().isEmpty(), "Linux keeps its default");
        Map<String, String> onMac =
                applied(mac, UserKeyBindings.derive(store, mac, true, true).overrides());
        assertEquals(List.of("C-Cmd-s"), chordsOf(onMac, "file.save"));
    }

    @Test
    void unbindingACommandUnbindsItOnEverySystem() {
        Map<String, String> linux = base("cua", false);
        Map<String, String> mac = base("cua", true);
        Store store = UserKeyBindings.edit(
                Store.empty(), linux, false, true, KeybindingEdits.clear(linux, Map.of(), "find.show"));
        assertEquals(Map.of("find.show", List.of()), store.shared());
        assertTrue(chordsOf(
                        applied(
                                linux,
                                UserKeyBindings.derive(store, linux, false, true)
                                        .overrides()),
                        "find.show")
                .isEmpty());
        assertTrue(chordsOf(
                        applied(
                                mac,
                                UserKeyBindings.derive(store, mac, true, true).overrides()),
                        "find.show")
                .isEmpty());
    }

    @Test
    void resettingACommandTakesItOutOfTheStoreForEverySystem() {
        Map<String, String> mac = base("vscode", true);
        Store store = new Store(
                new LinkedHashMap<>(Map.of("file.save", List.of("C-M-s"))),
                new LinkedHashMap<>(Map.of("file.save", List.of("C-Cmd-s"))));
        Map<String, String> now = UserKeyBindings.derive(store, mac, true, true).overrides();
        Store after = UserKeyBindings.edit(store, mac, true, true, KeybindingEdits.reset(mac, now, "file.save"));
        assertTrue(after.isEmpty());
    }

    @Test
    void anEditOnOneSystemLeavesTheOthersOwnBindingsAlone() {
        Map<String, String> linux = base("vscode", false);
        Store store = new Store(new LinkedHashMap<>(), new LinkedHashMap<>(Map.of("nav.goToLine", List.of("C-l"))));
        Map<String, String> now =
                UserKeyBindings.derive(store, linux, false, true).overrides();
        Store after = UserKeyBindings.edit(
                store, linux, false, true, KeybindingEdits.rebind(linux, now, "file.save", "C-M-s"));
        assertEquals(Map.of("nav.goToLine", List.of("C-l")), after.macOnly(), "the Mac's own chord is still there");
        assertEquals(Map.of("file.save", List.of("C-M-s")), after.shared());
    }

    @Test
    void takingAChordOverFromAnotherCommandDoesNotRewriteThatCommand() {
        Map<String, String> linux = base("cua", false);
        String taken = chordsOf(linux, "find.show").getFirst();
        Map<String, String> edited = KeybindingEdits.rebind(linux, Map.of(), "file.save", taken);
        Map<String, List<String>> byCommand = UserKeyBindings.byCommand(edited, linux);
        assertEquals(List.of(taken), byCommand.get("file.save"));
        assertFalse(byCommand.containsKey("find.show"), "it only lost a chord to the rebind");
    }

    @Test
    void aChordMacOsKeepsForItselfIsLeftOutThereAndTheCommandKeepsItsDefault() {
        Map<String, String> mac = base("vscode", true);
        Store store = new Store(new LinkedHashMap<>(Map.of("file.save", List.of("C-space"))), new LinkedHashMap<>());
        Derived onMac = UserKeyBindings.derive(store, mac, true, true);
        assertEquals(List.of(new UserKeyBindings.Skipped("file.save", "Cmd-space")), onMac.skipped());
        assertTrue(onMac.overrides().isEmpty(), "Spotlight has Cmd-Space: Save keeps Cmd-S on the Mac");
        Derived onLinux = UserKeyBindings.derive(store, base("vscode", false), false, true);
        assertTrue(onLinux.skipped().isEmpty());
        assertEquals("file.save", onLinux.overrides().get("C-space"));
    }

    /** On the system where it was made, a binding behaves exactly as the override map it came from did. */
    @Test
    void onItsOwnSystemAStoredEditIsTheEditThatWasMade() {
        for (String keymap : KeymapManager.AVAILABLE.keySet()) {
            boolean variant = KeymapManager.hasMacVariant(keymap);
            for (boolean mac : new boolean[] {false, true}) {
                Map<String, String> base = base(keymap, mac);
                List<String> commands =
                        base.values().stream().distinct().limit(25).toList();
                Map<String, String> overrides = new LinkedHashMap<>();
                int n = 0;
                for (String command : commands) {
                    String chord = (mac && variant ? "M-Cmd-" : "C-M-") + "f" + (1 + n % 12);
                    overrides = switch (n++ % 3) {
                        case 0 -> KeybindingEdits.rebind(base, overrides, command, chord);
                        case 1 -> KeybindingEdits.clear(base, overrides, command);
                        default ->
                            KeybindingEdits.rebind(
                                    base,
                                    overrides,
                                    command,
                                    chordsOf(base, command).getFirst());
                    };
                }
                Store store = UserKeyBindings.edit(Store.empty(), base, mac, variant, overrides);
                Map<String, String> derived =
                        UserKeyBindings.derive(store, base, mac, variant).overrides();
                assertEquals(applied(base, overrides), applied(base, derived), keymap + (mac ? " on macOS" : ""));
            }
        }
    }

    @Test
    void theTwoMapsKeptBeforeBecomeOneStoreWithNothingLost() {
        Map<String, String> linux = base("vscode", false);
        Map<String, String> mac = base("vscode", true);
        // The same rebind on both, one made on Linux only, one on the Mac only, one that differs, one Mac-only chord.
        Map<String, String> onLinux = KeybindingEdits.rebind(linux, Map.of(), "file.save", "C-M-s");
        onLinux = KeybindingEdits.rebind(linux, onLinux, "edit.undo", "C-M-z");
        onLinux = KeybindingEdits.rebind(linux, onLinux, "edit.redo", "C-M-y");
        Map<String, String> onMac = KeybindingEdits.rebind(mac, Map.of(), "file.save", "M-Cmd-s");
        onMac = KeybindingEdits.rebind(mac, onMac, "edit.copy", "M-Cmd-c");
        onMac = KeybindingEdits.rebind(mac, onMac, "edit.redo", "M-Cmd-r");
        onMac = KeybindingEdits.rebind(mac, onMac, "edit.paste", "C-Cmd-v");

        Store store = UserKeyBindings.fromSplit(onLinux, onMac, linux, mac, true);
        assertEquals(List.of("C-M-s"), store.shared().get("file.save"), "the same on both: once");
        assertEquals(List.of("C-M-z"), store.shared().get("edit.undo"), "made on Linux: everyone's");
        assertEquals(List.of("C-M-c"), store.shared().get("edit.copy"), "made on the Mac: everyone's");
        assertEquals(List.of("C-M-y"), store.shared().get("edit.redo"), "they differ: Linux's is the shared one");
        assertEquals(List.of("M-Cmd-r"), store.macOnly().get("edit.redo"), "and the Mac keeps its own");
        assertEquals(List.of("C-Cmd-v"), store.macOnly().get("edit.paste"), "a chord only a Mac can type");
        assertFalse(store.shared().containsKey("edit.paste"));

        // Neither machine changes for the bindings it already had.
        Map<String, String> macAfter =
                applied(mac, UserKeyBindings.derive(store, mac, true, true).overrides());
        Map<String, String> macBefore = applied(mac, onMac);
        for (String command : List.of("file.save", "edit.copy", "edit.redo", "edit.paste")) {
            assertEquals(chordsOf(macBefore, command), chordsOf(macAfter, command), command + " on the Mac");
        }
        Map<String, String> linuxAfter =
                applied(linux, UserKeyBindings.derive(store, linux, false, true).overrides());
        Map<String, String> linuxBefore = applied(linux, onLinux);
        for (String command : List.of("file.save", "edit.undo", "edit.redo")) {
            assertEquals(chordsOf(linuxBefore, command), chordsOf(linuxAfter, command), command + " on Linux");
        }
    }

    @Test
    void aDeletedCommandLeavesTheStore() {
        Store store = new Store(
                new LinkedHashMap<>(Map.of("macro.one", List.of("C-M-1"), "file.save", List.of("C-M-s"))),
                new LinkedHashMap<>(Map.of("macro.one", List.of("C-Cmd-1"))));
        Store after = UserKeyBindings.withoutCommand(store, "macro.one");
        assertEquals(Map.of("file.save", List.of("C-M-s")), after.shared());
        assertTrue(after.macOnly().isEmpty());
    }
}
