package com.editora.agent.runtime;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalMetricsTest {
    @Test
    void metricsCountDecisionsWithoutRetainingArguments() {
        var metrics = new AgentApprovalMetrics();
        var spec = new AgentTool.Spec(
                "apply_edits",
                "edit",
                new ObjectMapper().createObjectNode(),
                null,
                AgentTool.Effect.WORKSPACE_WRITE,
                Duration.ofSeconds(1),
                true,
                "editora");
        metrics.record(spec, "PRIVATE-SOURCE", true, 12);
        metrics.record(spec, "PRIVATE-SOURCE", false, 34);
        metrics.record(spec, "OTHER-PRIVATE", null, 1);
        var value = metrics.snapshot();
        assertEquals(3, value.requested());
        assertEquals(1, value.allowed());
        assertEquals(1, value.denied());
        assertEquals(1, value.interrupted());
        assertEquals(1, value.repeatedEquivalent());
        assertEquals(47, value.waitMillis());
        assertFalse(value.toString().contains("PRIVATE"));
    }
}
