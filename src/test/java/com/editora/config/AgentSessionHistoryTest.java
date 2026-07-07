package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionHistoryTest {

    @Test
    void rememberInsertsNewEntryWithCandidateLabel(@TempDir Path dir) {
        AgentSessionHistory h = new AgentSessionHistory(dir);
        h.remember("s1", "/proj", "First prompt", 100L, "gemini");
        assertEquals(1, h.getList().size());
        AgentSessionHistory.Entry e = h.getList().get(0);
        assertEquals("s1", e.sessionId());
        assertEquals("First prompt", e.label());
        assertEquals(100L, e.updatedAt());
        assertEquals("gemini", e.agentId());
    }

    @Test
    void reRememberKeepsOriginalLabelButBumpsOrderAndTimestamp(@TempDir Path dir) {
        AgentSessionHistory h = new AgentSessionHistory(dir);
        h.remember("s1", "/proj", "Title A", 100L, "claude");
        h.remember("s2", "/proj", "Title B", 200L, "claude"); // s2 now on top
        h.remember("s1", "/proj", "IGNORED LABEL", 300L, "claude"); // s1 back to top, label unchanged
        assertEquals("s1", h.getList().get(0).sessionId());
        assertEquals("Title A", h.getList().get(0).label());
        assertEquals(300L, h.getList().get(0).updatedAt());
        assertEquals("s2", h.getList().get(1).sessionId());
        assertEquals(2, h.getList().size());
    }

    @Test
    void reRememberKeepsOriginalAgentId(@TempDir Path dir) {
        AgentSessionHistory h = new AgentSessionHistory(dir);
        h.remember("s1", "/proj", "Title A", 100L, "claude");
        h.remember("s1", "/proj", "IGNORED LABEL", 300L, "gemini"); // re-remember with a different agentId
        assertEquals("claude", h.getList().get(0).agentId()); // fixed at creation, like label
    }

    @Test
    void blankSessionIdIsNoOp(@TempDir Path dir) {
        AgentSessionHistory h = new AgentSessionHistory(dir);
        h.remember(null, "/p", "x", 1L, "claude");
        h.remember("", "/p", "x", 1L, "claude");
        h.remember("  ", "/p", "x", 1L, "claude");
        assertTrue(h.getList().isEmpty());
    }

    @Test
    void cappedAtMaxEntries(@TempDir Path dir) {
        AgentSessionHistory h = new AgentSessionHistory(dir);
        for (int i = 0; i < AgentSessionHistory.MAX_ENTRIES + 5; i++) {
            h.remember("s" + i, "/p", "label " + i, i, "claude");
        }
        assertEquals(AgentSessionHistory.MAX_ENTRIES, h.getList().size());
        assertEquals(
                "s" + (AgentSessionHistory.MAX_ENTRIES + 4), h.getList().get(0).sessionId());
    }

    @Test
    void persistsAndReloads(@TempDir Path dir) {
        AgentSessionHistory a = new AgentSessionHistory(dir);
        a.remember("s1", "/proj", "Alpha", 100L, "claude");
        a.remember("s2", "/proj", "Beta", 200L, "claude");
        AgentSessionHistory b = new AgentSessionHistory(dir); // fresh instance reads the same file
        assertEquals(2, b.getList().size());
        assertEquals("s2", b.getList().get(0).sessionId());
        assertEquals("Beta", b.getList().get(0).label());
        assertEquals("s1", b.getList().get(1).sessionId());
    }

    @Test
    void persistsAndReloadsAgentId(@TempDir Path dir) {
        AgentSessionHistory a = new AgentSessionHistory(dir);
        a.remember("s1", "/proj", "Alpha", 100L, "opencode");
        AgentSessionHistory b = new AgentSessionHistory(dir);
        assertEquals("opencode", b.getList().get(0).agentId());
    }

    @Test
    void clearEmptiesAndPersists(@TempDir Path dir) {
        AgentSessionHistory a = new AgentSessionHistory(dir);
        a.remember("s1", "/p", "x", 1L, "claude");
        a.clear();
        assertTrue(a.getList().isEmpty());
        assertTrue(new AgentSessionHistory(dir).getList().isEmpty());
    }
}
