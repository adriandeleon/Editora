package com.editora.agent.runtime;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextRankerTest {
    @Test
    void explicitSelectionWinsAndSignalsMergeDeterministically() {
        var result = AgentContextRanker.rank(
                List.of(
                        new AgentContextRanker.Candidate("z", Set.of(AgentContextRanker.Signal.LEXICAL)),
                        new AgentContextRanker.Candidate("selected", Set.of(AgentContextRanker.Signal.USER_SELECTED)),
                        new AgentContextRanker.Candidate("a", Set.of(AgentContextRanker.Signal.LEXICAL)),
                        new AgentContextRanker.Candidate("a", Set.of(AgentContextRanker.Signal.DEFINITION))),
                3);
        assertEquals(
                List.of("selected", "a", "z"),
                result.stream().map(AgentContextRanker.Candidate::path).toList());
        assertEquals(75, result.get(1).score());
        assertEquals(2, result.get(1).signals().size());
    }
}
