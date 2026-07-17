package com.editora.command;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindingEditsTest {

    private static Map<String, String> base() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("C-x C-s", "file.save");
        m.put("C-s", "find.show");
        m.put("C-c", "edit.copy");
        m.put("C-M-i", "edit.completion");
        m.put("M-/", "edit.completion");
        return m;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Effective binding for a chord after applying overrides onto the base (mirrors KeymapManager). */
    private static String effective(Map<String, String> base, Map<String, String> overrides, String chord) {
        Map<String, String> eff = new LinkedHashMap<>(base);
        overrides.forEach((c, id) -> {
            if (id == null || id.isBlank()) {
                eff.remove(c);
            } else {
                eff.put(c, id);
            }
        });
        return eff.get(chord);
    }

    @Test
    void rebindSuppressesDefaultAndBindsNew() {
        Map<String, String> ov = KeybindingEdits.rebind(base(), map(), "file.save", "Cmd-S-s");
        assertEquals(map("C-x C-s", "", "Cmd-S-s", "file.save"), ov);
        // Effective: old default gone, new chord live.
        assertEquals(null, effective(base(), ov, "C-x C-s"));
        assertEquals("file.save", effective(base(), ov, "Cmd-S-s"));
    }

    @Test
    void rebindCommandWithNoDefaultJustBinds() {
        Map<String, String> ov = KeybindingEdits.rebind(base(), map(), "edit.duplicateLine", "C-S-d");
        assertEquals(map("C-S-d", "edit.duplicateLine"), ov);
    }

    @Test
    void rebindReplacesAPriorUserBinding() {
        Map<String, String> prior = KeybindingEdits.rebind(base(), map(), "file.save", "Cmd-s");
        Map<String, String> ov = KeybindingEdits.rebind(base(), prior, "file.save", "Cmd-S-s");
        // Only one user binding for the command remains (the latest); default still suppressed.
        assertEquals("file.save", effective(base(), ov, "Cmd-S-s"));
        assertEquals(null, effective(base(), ov, "Cmd-s"));
        assertEquals(null, effective(base(), ov, "C-x C-s"));
    }

    @Test
    void clearSuppressesAllDefaults() {
        Map<String, String> ov = KeybindingEdits.clear(base(), map(), "edit.completion");
        // Both default chords for completion are suppressed.
        assertEquals(null, effective(base(), ov, "C-M-i"));
        assertEquals(null, effective(base(), ov, "M-/"));
    }

    @Test
    void resetRestoresDefaultAndLeavesOthers() {
        Map<String, String> ov = KeybindingEdits.rebind(base(), map("M-x", "palette.show"), "file.save", "Cmd-s");
        Map<String, String> reset = KeybindingEdits.reset(base(), ov, "file.save");
        assertEquals(map("M-x", "palette.show"), reset); // file.save customization gone, unrelated override kept
        assertEquals("file.save", effective(base(), reset, "C-x C-s")); // default back
    }

    @Test
    void conflictStealLeavesOverrideForNewOwner() {
        // Rebinding file.save onto C-c (edit.copy's chord) makes C-c run file.save.
        Map<String, String> ov = KeybindingEdits.rebind(base(), map(), "file.save", "C-c");
        assertEquals("file.save", effective(base(), ov, "C-c"));
    }

    @Test
    void defaultChordsFindsAll() {
        assertTrue(KeybindingEdits.defaultChords(base(), "edit.completion")
                .containsAll(java.util.List.of("C-M-i", "M-/")));
        assertEquals(java.util.List.of("C-x C-s"), KeybindingEdits.defaultChords(base(), "file.save"));
    }

    // --- prefix-collision detection (#438) --------------------------------------------------------

    private static Map<String, String> active() {
        return map(
                "C-x C-s", "file.save",
                "C-x C-f", "file.find",
                "C-x C-c", "app.quit",
                "C-s", "find.show");
    }

    @Test
    void bindingAPrefixChordIsDetectedAsShadowingEveryChordUnderIt() {
        // The footgun: recording a lone C-x has NO exact match, so the old check waved it through — yet it
        // dead-ends every C-x … binding. conflicts() must surface all three as SHADOWS.
        var conflicts = KeybindingEdits.conflicts(active(), "C-x", "some.new.command");

        assertEquals(3, conflicts.size(), conflicts.toString());
        assertTrue(conflicts.stream().allMatch(c -> c.kind() == KeybindingEdits.ConflictKind.SHADOWS));
        assertTrue(conflicts.stream().anyMatch(c -> c.commandId().equals("file.save")));
        assertTrue(conflicts.stream().anyMatch(c -> c.commandId().equals("app.quit")));
    }

    @Test
    void theOldExactMatchCheckMissedThisEntirely() {
        // Documents the gap: an exact-chord lookup (what the editor used to do) finds nothing for C-x.
        assertNull(active().get("C-x"), "no exact binding on C-x — the old check saw no conflict");
        assertEquals(3, KeybindingEdits.conflicts(active(), "C-x", "x").size(), "…but three bindings live under it");
    }

    @Test
    void bindingUnderAnExistingChordIsDetectedAsUnreachable() {
        // The reverse: C-x C-s C-q can never fire because C-x C-s resolves first.
        var conflicts = KeybindingEdits.conflicts(active(), "C-x C-s C-q", "some.new.command");
        assertEquals(1, conflicts.size());
        assertEquals(KeybindingEdits.ConflictKind.UNREACHABLE, conflicts.get(0).kind());
        assertEquals("file.save", conflicts.get(0).commandId());
    }

    @Test
    void anExactMatchIsReportedAsExact() {
        var conflicts = KeybindingEdits.conflicts(active(), "C-s", "some.new.command");
        assertEquals(1, conflicts.size());
        assertEquals(KeybindingEdits.ConflictKind.EXACT, conflicts.get(0).kind());
        assertEquals("find.show", conflicts.get(0).commandId());
    }

    @Test
    void rebindingACommandOverItsOwnChordIsNotAConflict() {
        assertTrue(KeybindingEdits.conflicts(active(), "C-x C-s", "file.save").isEmpty());
        // and a lone C-x still conflicts with the OTHER C-x chords even when rebinding one of them.
        assertEquals(2, KeybindingEdits.conflicts(active(), "C-x", "file.save").size());
    }

    @Test
    void aSharedTokenPrefixThatIsNotAWholeChordPrefixIsNotAConflict() {
        // "C-x" must not be treated as a prefix of a "C-xy" chord — matching is whole-token.
        var m = map("C-xy C-s", "some.cmd");
        assertTrue(KeybindingEdits.conflicts(m, "C-x", "other").isEmpty());
    }
}
