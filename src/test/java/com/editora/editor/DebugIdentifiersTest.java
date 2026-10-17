package com.editora.editor;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DebugIdentifiersTest {

    @Test
    void wordAtFindsTheCoveringIdentifier() {
        String line = "int count = total + 1;";
        assertEquals("count", DebugIdentifiers.wordAt(line, 4)); // start
        assertEquals("count", DebugIdentifiers.wordAt(line, 8)); // end
        assertEquals("total", DebugIdentifiers.wordAt(line, 14));
        assertEquals("int", DebugIdentifiers.wordAt(line, 0));
    }

    @Test
    void wordAtNullOffIdentifiersAndOutOfRange() {
        String line = "a = b + 10;";
        assertNull(DebugIdentifiers.wordAt(line, 2)); // '='
        assertNull(DebugIdentifiers.wordAt(line, 8)); // '1' — a number literal, not a name
        assertNull(DebugIdentifiers.wordAt(line, -1));
        assertNull(DebugIdentifiers.wordAt(line, 99));
        assertNull(DebugIdentifiers.wordAt(null, 0));
    }

    @Test
    void wordAtHandlesUnderscoreAndDollar() {
        assertEquals("_private", DebugIdentifiers.wordAt("x = _private", 5));
        assertEquals("$el", DebugIdentifiers.wordAt("$el.focus()", 1));
    }

    @Test
    void matchesInFirstOccurrenceOrderDeduped() {
        List<String> m =
                DebugIdentifiers.matchesIn("sum = sum + value * count", Set.of("sum", "count", "value", "absent"));
        assertEquals(List.of("sum", "value", "count"), m);
    }

    @Test
    void matchesInWholeWordOnly() {
        // "counter" must not match the variable "count"; "1x" letters aren't a name.
        List<String> m = DebugIdentifiers.matchesIn("counter = count + 0x1F", Set.of("count", "x1F"));
        assertEquals(List.of("count"), m);
        assertEquals(List.of(), DebugIdentifiers.matchesIn(null, Set.of("a")));
        assertEquals(List.of(), DebugIdentifiers.matchesIn("a + b", Set.of()));
    }

    @Test
    void hoveringAnAttributeEvaluatesTheWholeChain() {
        String line = "        self.total = total + p.x";
        assertEquals("self.total", DebugIdentifiers.expressionAt(line, line.indexOf("total"))); // the attribute
        assertEquals("self", DebugIdentifiers.expressionAt(line, line.indexOf("self")));
        assertEquals("total", DebugIdentifiers.expressionAt(line, line.lastIndexOf("total") + 2)); // the variable
        assertEquals("p.x", DebugIdentifiers.expressionAt(line, line.length() - 1));
        assertEquals("a.b.c", DebugIdentifiers.expressionAt("print(a.b.c)", 10));
    }

    @Test
    void aChainStopsAtACallOrAnIndex() {
        assertEquals("x", DebugIdentifiers.expressionAt("f().x", 4)); // hovering must not call f
        assertEquals("x", DebugIdentifiers.expressionAt("a[0].x", 5));
        assertEquals(null, DebugIdentifiers.expressionAt("a + b", 2));
    }

    @Test
    void anAttributeIsNotTheVariableOfTheSameName() {
        // stopped where a local x exists, "p.x" must not be annotated with that x
        assertEquals(List.of("p"), DebugIdentifiers.matchesIn("print(p.x)", Set.of("p", "x")));
        assertEquals(List.of("x", "self"), DebugIdentifiers.matchesIn("x = self . x", Set.of("x", "self")));
    }
}
