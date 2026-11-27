package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link AgentChangeSets}: what each turn keeps, and where an MCP agent's turn ends. */
class AgentChangeSetsTest {

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private final AtomicLong now = new AtomicLong(1_000);
    private final AgentChangeSets sets = new AgentChangeSets(now::get);
    private final Path a = Path.of("/r/A.java");
    private final Path b = Path.of("/r/B.java");

    @Test
    void aTurnKeepsEachFileAsItWasBeforeItsFirstWrite() {
        sets.beginTurn("Claude Code");
        sets.beforeAgentWrite(a, "one", false);
        sets.beforeAgentWrite(a, "two", false); // a second write in the turn: still "one" before it
        sets.beforeAgentWrite(b, null, false); // created by the turn
        AgentChangeSets.Turn t = sets.endTurn();
        assertEquals("Claude Code", t.agent());
        assertEquals(2, t.fileCount());
        assertEquals("one", t.before().get(a));
        assertNull(t.before().get(b));
        assertEquals(t.number(), sets.latest().number());
    }

    @Test
    void aTurnThatWroteNothingIsNotAChangeSet() {
        sets.beginTurn("Claude Code");
        assertNull(sets.endTurn());
        assertNull(sets.latest());
    }

    @Test
    void mcpEditsAreOneTurnUntilTheAgentIsQuiet() {
        sets.beforeMcpEdit("MCP agent", a, "a0");
        now.addAndGet(AgentChangeSets.MCP_QUIET_MILLIS - 1);
        sets.beforeMcpEdit("MCP agent", b, "b0");
        assertEquals(2, sets.latest().fileCount(), "one turn");
        now.addAndGet(AgentChangeSets.MCP_QUIET_MILLIS + 1);
        sets.beforeMcpEdit("MCP agent", a, "a1");
        assertEquals("a1", sets.latest().before().get(a), "a new turn after the quiet time");
        assertEquals(2, sets.turns().size());
    }

    @Test
    void theFirstWriteOfEachFileInATurnIsHeardOnce() {
        java.util.List<String> heard = new java.util.ArrayList<>();
        sets.setOnFirstWrite((turn, file, text) -> heard.add(AgentChangeSets.name(turn) + " " + file + " " + text));
        sets.beginTurn("Claude Code");
        sets.beforeAgentWrite(a, "one", true);
        sets.beforeAgentWrite(a, "two", true); // not the first write of a
        sets.beforeAgentWrite(b, "closed", false); // a closed file keeps its own Local History row
        sets.endTurn();
        sets.beforeMcpEdit("MCP agent", b, "b0");
        assertEquals(2, heard.size(), heard.toString());
        assertEquals("Turn 1 · Claude Code " + a + " one", heard.get(0)); // the path as this OS writes it
        assertEquals("Turn 2 · MCP agent " + b + " b0", heard.get(1));
    }

    @Test
    void onlyTheNewestTurnsAreKept() {
        for (int i = 0; i < AgentChangeSets.KEPT + 5; i++) {
            sets.beginTurn("A");
            sets.beforeAgentWrite(a, "v" + i, false);
            sets.endTurn();
        }
        assertEquals(AgentChangeSets.KEPT, sets.turns().size());
        assertEquals("v" + (AgentChangeSets.KEPT + 4), sets.latest().before().get(a));
    }
}
