package com.editora.agent.eval;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionCorpusTest {
    @Test
    void fixtureAndOracleDefinitionsStayFrozen() throws Exception {
        if (Boolean.getBoolean("agent.corpus.freeze")) {
            Files.createDirectories(AgentExecutionCorpus.MANIFEST.getParent());
            Files.writeString(
                    AgentExecutionCorpus.MANIFEST,
                    AgentExecutionCorpus.manifest(Path.of(".")).toPrettyString() + "\n");
        }
        assertFalse(AgentExecutionCorpus.verify(Path.of(".")).isBlank());
        assertEquals(7, AgentExecutionCorpus.TASKS.size());
    }
}
