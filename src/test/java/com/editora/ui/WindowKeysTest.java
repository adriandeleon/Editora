package com.editora.ui;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowKeysTest {

    @Test
    void classifiesKeys() {
        assertTrue(WindowKeys.isGlobal(""));
        assertFalse(WindowKeys.isUntitled(""));
        assertFalse(WindowKeys.isProject(""));

        assertTrue(WindowKeys.isUntitled("untitled:ab12cd34"));
        assertFalse(WindowKeys.isProject("untitled:ab12cd34"));
        assertFalse(WindowKeys.isGlobal("untitled:ab12cd34"));

        assertTrue(WindowKeys.isProject("my-project-id"));
        assertFalse(WindowKeys.isUntitled("my-project-id"));
    }

    @Test
    void untitledSessionFileName() {
        assertEquals("ab12cd34.json", WindowKeys.untitledSessionFileName("untitled:ab12cd34"));
    }

    @Test
    void restoreKeysKeepsOrderAndAddsCli() {
        var keys = WindowKeys.restoreKeys(List.of("", "proj-a", "untitled:x"), true, "proj-b");
        assertEquals(List.of("", "proj-a", "untitled:x", "proj-b"), List.copyOf(keys));
    }

    @Test
    void restoreKeysDropsProjectsWhenProjectsOff() {
        // Projects disabled: project keys are dropped, the global + untitled windows survive.
        var keys = WindowKeys.restoreKeys(List.of("", "proj-a", "untitled:x"), false, null);
        assertEquals(List.of("", "untitled:x"), List.copyOf(keys));
    }

    @Test
    void restoreKeysFallsBackToGlobalWhenEmpty() {
        assertEquals(List.of(""), List.copyOf(WindowKeys.restoreKeys(List.of(), false, null)));
        // Projects off + only project keys saved ⇒ all dropped ⇒ fall back to the global window.
        assertEquals(List.of(""), List.copyOf(WindowKeys.restoreKeys(List.of("proj-a"), false, null)));
    }

    @Test
    void primaryKeyPrefersCliThenGlobalThenActiveThenFirst() {
        assertEquals("cli", WindowKeys.primaryKey("cli", List.of("", "cli"), "proj-a"));
        assertEquals("", WindowKeys.primaryKey(null, List.of("", "proj-a"), "proj-a"));
        assertEquals("proj-a", WindowKeys.primaryKey(null, List.of("proj-b", "proj-a"), "proj-a"));
        // active not in the set ⇒ first restored window wins.
        assertEquals("proj-b", WindowKeys.primaryKey(null, List.of("proj-b", "proj-c"), "proj-a"));
        // no active ⇒ first.
        assertEquals("proj-b", WindowKeys.primaryKey(null, List.of("proj-b", "proj-c"), null));
    }

    @Test
    void orphanSessionFilesKeepsOnlyOpenUntitledSessions() {
        var open = List.of("", "untitled:keep", "proj-a");
        var existing = List.of("keep.json", "stale.json", "notjson.txt");
        Set<String> orphans = WindowKeys.orphanSessionFiles(open, existing);
        assertEquals(Set.of("stale.json"), orphans); // keep.json backs an open window; non-json ignored
    }

    @Test
    void orphanSessionFilesDeletesAllWhenNoUntitledOpen() {
        Set<String> orphans = WindowKeys.orphanSessionFiles(List.of("", "proj-a"), List.of("a.json", "b.json"));
        assertEquals(Set.of("a.json", "b.json"), orphans);
    }
}
