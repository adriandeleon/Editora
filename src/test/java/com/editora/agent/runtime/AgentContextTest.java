package com.editora.agent.runtime;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextTest {
    @Test
    void compactionKeepsUserGoalsAndCompleteRecentExchanges() {
        AgentContext context = new AgentContext();
        context.add(List.of(AgentModel.Message.text("user", "preserve this instruction")));
        for (int i = 0; i < 100; i++) {
            var call = new AgentModel.Call("id" + i, "read", "{}");
            context.add(List.of(
                    new AgentModel.Message("assistant", "", List.of(call), null, false),
                    AgentModel.Message.observation(call.id(), "x".repeat(300), false)));
        }
        var request = context.request("system", List.of(), 3000);
        assertTrue(request.system().contains("removed"));
        assertTrue(request.messages().stream()
                .anyMatch(m -> m.role().equals("user") && m.text().equals("preserve this instruction")));
        var protocol = request.messages().stream()
                .filter(m -> m.role().equals("assistant") || m.role().equals("tool"))
                .toList();
        for (int i = 0; i < protocol.size(); i += 2) {
            assertEquals(
                    protocol.get(i).calls().getFirst().id(), protocol.get(i + 1).callId());
        }
        assertEquals("id99", request.messages().getLast().callId());
    }

    @Test
    void compactionMemoryIsHistoricalDataAndTokenizerProvenanceIsExplicit() {
        var context = new AgentContext();
        context.add(List.of(AgentModel.Message.text("user", "goal")));
        for (int i = 0; i < 12; i++)
            context.add(List.of(AgentModel.Message.text("assistant", "unverified discovery " + "x".repeat(500))));
        var request = context.request("system", List.of(), 2400);
        assertFalse(request.system().contains("unverified discovery"));
        assertTrue(request.messages().stream()
                .anyMatch(m -> m.role().equals("observation") && m.text().contains("Historical memory")));
        assertEquals(
                AgentTokens.Provenance.HEURISTIC,
                AgentTokens.CONSERVATIVE.count("😀").provenance());
        var capable = new AgentModel.Capabilities(
                true, true, 32000, 4000, java.util.Map.of(AgentModel.Feature.USAGE, AgentModel.Support.SUPPORTED));
        assertEquals(AgentModel.Support.UNKNOWN, capable.support(AgentModel.Feature.REASONING));
        assertEquals(AgentModel.Support.SUPPORTED, capable.support(AgentModel.Feature.USAGE));
        AgentTokens.Counter tokenizer =
                t -> new AgentTokens.Count(t.length() / 4, AgentTokens.Provenance.TOKENIZER_ESTIMATED);
        var large = new AgentContext();
        large.add(List.of(AgentModel.Message.text("user", "a".repeat(5000))));
        assertDoesNotThrow(() -> large.request("system", List.of(), 2500, tokenizer));
        assertThrows(IllegalStateException.class, () -> large.request("system", List.of(), 2500));
    }

    @Test
    void budgetUsesUtf8AndDoesNotSplitSurrogates() {
        assertEquals(4, AgentContext.cost("😀"));
        String bounded = AgentContext.bounded("a".repeat(31) + "😀".repeat(100), 84);
        assertTrue(bounded.length() <= 84);
        assertFalse(bounded.contains("\uD83D\n"));
    }

    @Test
    void rejectsOrphanedResultsAndBudgetsWithoutResponseRoom() {
        AgentContext context = new AgentContext();
        assertThrows(
                IllegalArgumentException.class,
                () -> context.add(List.of(AgentModel.Message.observation("missing", "result", false))));
        var call = new AgentModel.Call("a", "read", "{}");
        assertThrows(
                IllegalArgumentException.class,
                () -> context.add(List.of(new AgentModel.Message("assistant", "", List.of(call), null, false))));
        context.add(List.of(AgentModel.Message.text("user", "goal")));
        assertThrows(IllegalStateException.class, () -> context.request("system", List.of(), 0));
    }
}
