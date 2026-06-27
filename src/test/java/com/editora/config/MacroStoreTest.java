package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** macros.json round-trips through SharedConfig; put/find/remove semantics. */
class MacroStoreTest {

    @Test
    void putReplacesByNameAndFindReturnsIt() {
        MacroStore store = new MacroStore();
        store.put(new Macro("a", List.of(MacroStep.text("x"))));
        store.put(new Macro("a", List.of(MacroStep.command("edit.cut")))); // replace
        assertEquals(1, store.macros.size());
        assertEquals(MacroStep.command("edit.cut"), store.find("a").steps().get(0));
        assertNull(store.find("nope"));
        assertTrue(store.remove("a"));
        assertFalse(store.remove("a"));
    }

    @Test
    void namelessMacroFromCorruptedJsonDoesNotNpeLookups() {
        // A corrupted/hand-edited macros.json entry with no "name" deserializes to a Macro with a null name
        // component; Macro defaults it to "" so find/put/remove (which call m.name().equals(...)) never NPE.
        Macro nameless = new Macro(null, List.of(MacroStep.text("x")));
        assertEquals("", nameless.name());
        MacroStore store = new MacroStore();
        store.macros.add(nameless);
        assertNull(store.find("anything")); // iterates + derefs m.name() — must not throw
        store.put(new Macro("real", List.of()));
        assertFalse(store.remove("missing"));
    }

    @Test
    void roundTripsThroughMacrosJson(@TempDir Path dir) throws Exception {
        SharedConfig cfg = new SharedConfig(dir, true);
        cfg.load();
        cfg.getMacroStore()
                .put(new Macro(
                        "wrap", List.of(MacroStep.text("("), MacroStep.command("nav.lineEnd"), MacroStep.text(")"))));
        cfg.saveMacros();
        assertTrue(Files.readString(cfg.getMacrosFile()).contains("\"schemaVersion\""));

        SharedConfig reopened = new SharedConfig(dir, true);
        reopened.load();
        Macro m = reopened.getMacroStore().find("wrap");
        assertNotNull(m);
        assertEquals(List.of(MacroStep.text("("), MacroStep.command("nav.lineEnd"), MacroStep.text(")")), m.steps());
    }
}
