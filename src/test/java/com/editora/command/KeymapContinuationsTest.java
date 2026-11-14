package com.editora.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The keys the prefix hint lists after a pending prefix (KBD-14 in Pauta 6.0.1). */
class KeymapContinuationsTest {

    private KeymapManager keymap;

    @BeforeEach
    void setUp() {
        keymap = new KeymapManager();
        keymap.loadNamed("emacs");
    }

    private static KeymapContinuations.Continuation find(List<KeymapContinuations.Continuation> keys, String token) {
        return keys.stream().filter(k -> k.token().equals(token)).findFirst().orElse(null);
    }

    @Test
    void theEmacsCxPrefixListsItsCommandsAndItsLongerPrefixes() {
        List<KeymapContinuations.Continuation> keys = keymap.continuations("C-x", id -> true);
        assertTrue(keys.size() > 20, "C-x has dozens of continuations, was " + keys.size());
        assertEquals("file.save", find(keys, "C-s").commandId());
        KeymapContinuations.Continuation vc = find(keys, "v");
        assertTrue(vc.isPrefix(), "C-x v leads further");
        assertNull(vc.commandId());
        assertTrue(vc.further() >= 10, "C-x v holds the version-control commands, was " + vc.further());
        assertEquals(
                keys.size(),
                keys.stream()
                        .map(KeymapContinuations.Continuation::token)
                        .distinct()
                        .count());
    }

    @Test
    void aPrefixOfSeveralKeysListsWhatFollowsIt() {
        List<KeymapContinuations.Continuation> keys = keymap.continuations("C-x v", id -> true);
        assertEquals("git.fileHistory", find(keys, "l").commandId());
        KeymapContinuations.Continuation branch = find(keys, "b");
        assertTrue(branch.isPrefix());
        assertEquals(2, branch.further(), "C-x v b s and C-x v b c");
        assertEquals(
                "git.switchBranch",
                find(keymap.continuations("C-x v b", id -> true), "s").commandId());
    }

    @Test
    void theUsersOwnBindingsAreListedAndAnUnboundOneIsNot() {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("C-x C-9", "palette.show");
        overrides.put("C-x C-s", KeymapManager.UNBIND);
        keymap.applyOverrides(overrides);
        List<KeymapContinuations.Continuation> keys = keymap.continuations("C-x", id -> true);
        assertEquals("palette.show", find(keys, "C-9").commandId());
        assertNull(find(keys, "C-s"), "a chord the user unbound is not offered");
    }

    @Test
    void aCommandThatIsNotRegisteredIsLeftOut() {
        List<KeymapContinuations.Continuation> keys =
                keymap.continuations("C-x", id -> !id.equals("file.save") && !id.startsWith("git."));
        assertNull(find(keys, "C-s"));
        // Every command under C-x v b is a git one: the key leads nowhere, so it is not listed either.
        assertNull(find(keymap.continuations("C-x v", id -> !id.startsWith("git.")), "b"));
    }

    @Test
    void aKeyThatRunsACommandAndLeadsFurtherRunsTheCommandAsTheDispatcherDoes() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("C-c a b", "one");
        bindings.put("C-c a", "two");
        KeymapContinuations.Continuation a =
                KeymapContinuations.after(bindings, "C-c", id -> true).get(0);
        assertEquals("two", a.commandId());
        assertFalse(a.isPrefix());
        assertEquals(0, a.further());
    }

    @Test
    void keysSortByTheKeyItselfWithThePlainKeyFirst() {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String token : List.of("C-f", "b", "C-b", "f", "M-a", "S-f")) {
            bindings.put("C-x " + token, "cmd." + token);
        }
        List<String> order = KeymapContinuations.after(bindings, "C-x", id -> true).stream()
                .map(KeymapContinuations.Continuation::token)
                .toList();
        assertEquals(List.of("M-a", "b", "C-b", "f", "C-f", "S-f"), order);
    }

    @Test
    void theBaseKeyDropsEveryModifierButNeverTheKey() {
        assertEquals("x", KeymapContinuations.baseKey("C-M-S-x"));
        assertEquals("s", KeymapContinuations.baseKey("Cmd-s"));
        assertEquals("-", KeymapContinuations.baseKey("C--"));
        assertEquals("v", KeymapContinuations.baseKey("v"));
    }

    @Test
    void aBindingNotUnderThePrefixIsNotAContinuation() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("C-xx", "nope"); // shares the characters, not the key
        bindings.put("C-x", "alone");
        bindings.put("C-c C-x a", "elsewhere");
        assertTrue(KeymapContinuations.after(bindings, "C-x", id -> true).isEmpty());
    }
}
