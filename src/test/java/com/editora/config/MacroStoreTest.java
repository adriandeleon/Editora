package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.macro.Macro;
import com.editora.macro.MacroStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** macros.json: id-keyed put/find/remove, what a hand-edited file may hold, and the v1 → v2 migration. */
class MacroStoreTest {

    @Test
    void macrosAreKeyedByIdAndANewOneGetsAnIdFromItsName() {
        MacroStore store = new MacroStore();
        store.put(new Macro("Build", List.of(MacroStep.text("x"))));
        store.put(new Macro("build", List.of(MacroStep.text("y")))); // a different macro, not a replacement
        assertEquals(2, store.macros.size(), "names differing only in case are two macros");
        assertEquals("build", store.macros.get(0).id());
        assertEquals("build-2", store.macros.get(1).id());

        store.put(new Macro("build", "Renamed", List.of(MacroStep.command("edit.cut")))); // same id: replaces
        assertEquals(2, store.macros.size());
        assertEquals("Renamed", store.findById("build").name());
        assertEquals(
                MacroStep.command("edit.cut"),
                store.findByName("Renamed").steps().get(0));
        assertNull(store.findByName("nope"));
        assertTrue(store.removeById("build"));
        assertFalse(store.removeById("build"));
    }

    @Test
    void theUnnamedSlotIsFoundByIdAndOnlyWhileItHasNoName() {
        MacroStore store = new MacroStore();
        assertNull(store.placeholder());
        store.put(new Macro("unnamed-macro", "", List.of(MacroStep.text("x"))));
        store.lastId = "unnamed-macro";
        assertNotNull(store.placeholder());
        assertTrue(store.isPlaceholder(store.findById("unnamed-macro")));

        store.put(new Macro("unnamed-macro", "Mine now", List.of(MacroStep.text("x")))); // the user named it
        assertNull(store.placeholder(), "a named macro is not overwritten by the next recording");
    }

    /** M12: {@code "macros": null}, a null entry, a null step and a missing id must all load. */
    @Test
    void sanitizeRepairsWhatAHandEditedFileCanHold() {
        MacroStore store = new MacroStore();
        store.macros = null;
        store.lastId = null;
        store.sanitize();
        assertNotNull(store.macros);
        assertEquals("", store.lastId);

        store.macros = new ArrayList<>(Arrays.asList(
                null,
                new Macro(null, null, Arrays.asList(null, new MacroStep(null, null, null))),
                new Macro("dup", "a", List.of()),
                new Macro("dup", "b", List.of())));
        store.sanitize();
        assertEquals(3, store.macros.size());
        assertEquals("", store.macros.get(0).name());
        assertEquals(List.of(MacroStep.text("")), store.macros.get(0).steps(), "a null step is dropped");
        assertFalse(store.macros.get(0).id().isBlank());
        assertNotEquals(store.macros.get(1).id(), store.macros.get(2).id(), "ids are unique after a repair");
        assertNull(store.findByName("anything")); // iterates + derefs name() — must not throw
    }

    @Test
    void aHandEditedFileWithANullListLoads(@TempDir Path dir) throws Exception {
        for (String json : List.of(
                "{\"schemaVersion\":2,\"macros\":null}",
                "{\"schemaVersion\":2,\"macros\":[null]}",
                "{\"schemaVersion\":1,\"macros\":null}",
                "{\"schemaVersion\":1,\"macros\":[null,{\"name\":\"a\",\"steps\":[null,{\"kind\":null}]}]}")) {
            Path sub = Files.createTempDirectory(dir, "cfg");
            Files.writeString(sub.resolve("macros.json"), json);
            SharedConfig cfg = new SharedConfig(sub, true);
            cfg.load();
            for (Macro m : cfg.getMacroStore().macros) {
                assertNotNull(m, json);
                assertFalse(m.id().isBlank(), json);
            }
        }
    }

    @Test
    void roundTripsThroughMacrosJson(@TempDir Path dir) throws Exception {
        SharedConfig cfg = new SharedConfig(dir, true);
        cfg.load();
        List<MacroStep> steps = List.of(
                MacroStep.text("("),
                MacroStep.command("nav.lineEnd"),
                MacroStep.key("S-TAB"),
                MacroStep.text("query", true),
                MacroStep.key("ENTER", true));
        cfg.getMacroStore().put(new Macro("wrap", steps));
        cfg.saveMacros();
        String json = Files.readString(cfg.getMacrosFile());
        assertTrue(json.contains("\"schemaVersion\""));
        assertTrue(json.contains("\"id\" : \"wrap\"") || json.contains("\"id\":\"wrap\""), json);

        SharedConfig reopened = new SharedConfig(dir, true);
        reopened.load();
        Macro m = reopened.getMacroStore().findByName("wrap");
        assertNotNull(m);
        assertEquals(steps, m.steps());
        assertTrue(m.steps().get(3).isPrompt(), "where a step was typed survives the file");
    }

    /**
     * v1 → v2: ids are the slugs key bindings already use, the translated "unnamed macro" entry becomes the
     * id-keyed unnamed slot, and Enter/Tab stored as text become keys.
     */
    @Test
    void aVersionOneFileKeepsItsCommandIdsAndGainsTheUnnamedSlot(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("macros.json"),
                "{\"schemaVersion\":1,\"macros\":["
                        + "{\"name\":\"My Macro\",\"steps\":[{\"kind\":\"text\",\"value\":\"a\\tb\\rc\"}]},"
                        + "{\"name\":\"my-macro\",\"steps\":[{\"kind\":\"key\",\"value\":\"DOWN\"}]},"
                        + "{\"name\":\"macro sin nombre\",\"steps\":[{\"kind\":\"text\",\"value\":\"x\"}]}]}");
        SharedConfig cfg = new SharedConfig(dir, true);
        cfg.load();
        MacroStore store = cfg.getMacroStore();
        assertEquals(3, store.macros.size());
        // Both names slugged to "my-macro"; the shared command ran the last one, so it keeps the id.
        assertEquals("my-macro", store.findByName("my-macro").id());
        assertEquals("my-macro-2", store.findByName("My Macro").id());
        assertEquals(
                List.of(
                        MacroStep.text("a"),
                        MacroStep.key("TAB"),
                        MacroStep.text("b"),
                        MacroStep.key("ENTER"),
                        MacroStep.text("c")),
                store.findByName("My Macro").steps());
        // The Spanish placeholder keeps the id its key binding points at, and loses the translated name.
        assertEquals("macro-sin-nombre", store.lastId);
        assertNotNull(store.placeholder());
        assertEquals("", store.placeholder().name());
        assertEquals(List.of(MacroStep.text("x")), store.placeholder().steps());
    }
}
