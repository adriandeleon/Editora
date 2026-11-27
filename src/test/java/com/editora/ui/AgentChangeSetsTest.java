package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link AgentChangeSets}: what each turn keeps, and where an MCP agent's turn ends. */
class AgentChangeSetsTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AgentChangeSets sets = new AgentChangeSets(now::get);
    private final Path a = Path.of("/r/A.java");
    private final Path b = Path.of("/r/B.java");

    @Test
    void aTurnKeepsEachFileAsItWasBeforeItsFirstWrite() {
        sets.beginTurn("Claude Code");
        sets.beforeAgentWrite(a, "one");
        sets.beforeAgentWrite(a, "two"); // a second write in the turn: still "one" before it
        sets.beforeAgentWrite(b, null); // created by the turn
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
    void onlyTheNewestTurnsAreKept() {
        for (int i = 0; i < AgentChangeSets.KEPT + 5; i++) {
            sets.beginTurn("A");
            sets.beforeAgentWrite(a, "v" + i);
            sets.endTurn();
        }
        assertEquals(AgentChangeSets.KEPT, sets.turns().size());
        assertEquals("v" + (AgentChangeSets.KEPT + 4), sets.latest().before().get(a));
    }
}
