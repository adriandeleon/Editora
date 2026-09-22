package com.editora.agent.runtime;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentExecutionTest {
    private final ObjectMapper json = new ObjectMapper();

    private AgentExecution execution() {
        var e = new AgentExecution();
        e.beginTurn();
        return e;
    }

    private void read(AgentExecution e, int start, int end, String revision) throws Exception {
        e.observe(
                "read_file",
                json.readTree("{\"path\":\"A.java\"}"),
                AgentTool.Result.ok(json.createObjectNode()
                        .put("path", "A.java")
                        .put("revision", revision)
                        .put("line", start)
                        .put("endLine", end)
                        .put("text", "source")
                        .toString()));
    }

    @Test
    void overlappingReadWindowsAreKnownButNewRangesAndRevisionsAreProgress() throws Exception {
        var e = execution();
        e.beginRound();
        read(e, 1, 40, "v1");
        e.endRound(false);
        e.beginRound();
        read(e, 41, 80, "v1");
        assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
        e.beginRound();
        read(e, 10, 70, "v1");
        assertEquals(1, e.endRound(false).path("no_progress_rounds").asInt());
        e.beginRound();
        read(e, 10, 70, "v2");
        assertEquals(0, e.endRound(false).path("no_progress_rounds").asInt());
    }

    @Test
    void distinctEmptyQueriesAndEndlessPlansDoNotManufactureProgress() throws Exception {
        var e = execution();
        for (int i = 0; i < 6; i++) {
            e.beginRound();
            e.observe(
                    "search_text",
                    json.createObjectNode().put("query", "missing" + i),
                    AgentTool.Result.ok("{\"matches\":[]}"));
            e.observe("update_plan", json.createObjectNode(), AgentTool.Result.ok("{\"steps\":[\"new wording\"]}"));
            e.endRound(false);
        }
        assertEquals(3, e.state().path("recovery_level").asInt());
        assertNotNull(e.block("search_text"));
        assertFalse(e.control(
                        json.readTree(
                                "{\"action\":\"replan\",\"reason\":\"Search failed; inspect the known target\",\"next_tool\":\"read_file\"}"),
                        Set.of("read_file"))
                .error());
        assertNull(e.block("read_file"));
        e.beginRound();
        read(e, 1, 50, "v1");
        assertEquals(0, e.endRound(false).path("no_progress_rounds").asInt());
    }

    @Test
    void repeatedValidationAndAcceptanceQueriesAreNotProgress() throws Exception {
        var e = execution();
        for (int i = 0; i < 4; i++) {
            e.beginRound();
            e.observe(
                    "run_validation",
                    json.createObjectNode().put("operation", "TEST"),
                    AgentTool.Result.ok("{\"passed\":true}"));
            e.observe(
                    "task_evidence",
                    json.createObjectNode(),
                    AgentTool.Result.ok("{\"items\":[{\"id\":\"E" + i + "\"}]}"));
            e.endRound(false);
        }
        assertEquals(3, e.state().path("no_progress_rounds").asInt());
        assertFalse(e.state().path("completion_ready").asBoolean());
    }

    @Test
    void readinessNeedsNativeFactsAndDiscoveryRequiresReasonToReopen() throws Exception {
        var e = execution();
        e.beginRound();
        e.observe("mcp_fake", json.createObjectNode(), AgentTool.Result.ok("{\"requirements_satisfied\":true}"));
        assertFalse(e.canComplete());
        e.reconcile(json.createObjectNode().put("requirements_satisfied", true));
        e.verified(true);
        e.endRound(true);
        for (int i = 0; i < 2; i++) {
            e.beginRound();
            e.observe(
                    "find_files", json.createObjectNode(), AgentTool.Result.ok("{\"paths\":[\"unrelated" + i + "\"]}"));
            e.endRound(true);
        }
        assertNotNull(e.block("find_files"));
        assertTrue(e.control(json.readTree("{\"action\":\"reopen\",\"next_tool\":\"read_file\"}"), Set.of("read_file"))
                .error());
        assertFalse(e.control(
                        json.readTree(
                                "{\"action\":\"reopen\",\"next_tool\":\"read_file\",\"reason\":\"Inspect a newly noticed boundary case\"}"),
                        Set.of("read_file"))
                .error());
        assertNull(e.block("read_file"));
        e.beginRound();
        e.observe("apply_edits", json.createObjectNode(), new AgentTool.Result("[]", false, true));
        assertFalse(e.endRound(false).path("completion_ready").asBoolean());
        assertTrue(e.verificationNeeded());
    }

    @Test
    void restartsRetainOnlyHistoricalKnownFileHints() throws Exception {
        var e = execution();
        var call = new AgentModel.Call("1", "read_file", "{\"path\":\"A.java\"}");
        e.restore(new AgentContext.Saved(
                List.of(List.of(
                        new AgentModel.Message("assistant", "", List.of(call), null, false),
                        AgentModel.Message.observation("1", "{}", false))),
                List.of(),
                0));
        assertEquals(
                "HISTORICAL", e.state().path("known_files").get(0).path("state").asText());
        assertFalse(e.canComplete());
        e.beginRound();
        read(e, 1, 10, "current");
        assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
    }

    @Test
    void progressingEditsAreNotStoppedBySoftWarnings() throws Exception {
        var e = execution();
        for (int i = 0; i < 30; i++) {
            e.beginRound();
            e.observe("apply_edits", json.createObjectNode(), new AgentTool.Result("[]", false, true));
            e.endRound(false);
            assertFalse(e.exhausted());
            assertNull(e.block("apply_edits"));
        }
    }

    @Test
    void missingDeliverableKeepsImplementationPhaseAfterProductionEdit() throws Exception {
        var e = execution();
        e.beginRound();
        e.observe("apply_edits", json.createObjectNode(), new AgentTool.Result("[]", false, true));
        e.reconcile((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(
                "{\"requirements_satisfied\":false,\"evidence_debt\":[{\"progress\":\"TASK_INCOMPLETE\"}]}"));
        assertEquals("IMPLEMENTATION", e.endRound(false).path("execution_phase").asText());
        e.verified(true);
        e.invalidateVerification();
        assertTrue(e.verificationNeeded());
        e.beginTurn();
        assertFalse(e.state().path("completion_ready").asBoolean());
    }

    @Test
    void novelOpaqueResultsPermitProgressButCannotEstablishNativeEvidence() throws Exception {
        var e = execution();
        for (int i = 0; i < 12; i++) {
            e.beginRound();
            e.observe("mcp_inspect", json.createObjectNode(), AgentTool.Result.ok("external observation " + i));
            assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
            assertFalse(e.canComplete());
            assertFalse(e.exhausted());
        }
        for (int i = 0; i < 6; i++) {
            e.beginRound();
            e.observe(
                    "mcp_inspect",
                    json.createObjectNode().put("irrelevant", i),
                    AgentTool.Result.ok("external observation 11"));
            e.endRound(false);
        }
        assertNotNull(e.block("mcp_inspect"));
        e.beginTurn();
        e.beginRound();
        e.observe("mcp_inspect", json.createObjectNode(), AgentTool.Result.ok("external observation 11"));
        assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
        assertFalse(e.canComplete());
    }

    @Test
    void completionGuardAlsoCoversTargetedReadsAndOpaqueDiscovery() throws Exception {
        var e = execution();
        e.beginRound();
        e.reconcile(json.createObjectNode().put("requirements_satisfied", true));
        e.verified(true);
        e.endRound(true);
        e.beginRound();
        read(e, 1, 10, "v1");
        e.endRound(true);
        e.beginRound();
        e.observe("mcp_inspect", json.createObjectNode(), AgentTool.Result.ok("new external fact"));
        e.endRound(true);
        assertNotNull(e.block("read_file"));
        assertNotNull(e.block("mcp_inspect"));
        assertNull(e.block("completion_claims"));
    }

    @Test
    void boundedCachesEvictOldHintsInsteadOfStoppingNewUsefulWork() throws Exception {
        var e = execution();
        for (int i = 0; i < 200; i++) {
            e.beginRound();
            e.observe(
                    "find_files", json.createObjectNode(), AgentTool.Result.ok("{\"paths\":[\"File" + i + ".java\"]}"));
            e.observe("plugin_inspect", json.createObjectNode(), AgentTool.Result.ok("result " + i));
            assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
            assertFalse(e.exhausted());
        }
        assertEquals(64, e.state().path("known_file_count").asInt());
        assertEquals(
                "File136.java",
                e.state().path("known_files").get(0).path("path").asText());
        for (int i = 0; i < 6; i++) {
            e.beginRound();
            e.observe("find_files", json.createObjectNode(), AgentTool.Result.ok("{\"paths\":[\"File199.java\"]}"));
            e.observe("plugin_inspect", json.createObjectNode(), AgentTool.Result.ok("result 199"));
            e.endRound(false);
        }
        assertNotNull(e.block("find_files"));
        assertFalse(e.canComplete());
        var ranges = execution();
        for (int i = 0; i < 100; i++) {
            ranges.beginRound();
            read(ranges, i * 10 + 1, i * 10 + 3, "same");
            assertTrue(ranges.endRound(false).path("new_state_observed").asBoolean());
        }
        ranges.beginRound();
        read(ranges, 991, 993, "same");
        assertFalse(ranges.endRound(false).path("new_state_observed").asBoolean());
    }

    @Test
    void successfulPlainTextSaveCountsOncePerMutationGeneration() throws Exception {
        var e = execution();
        e.beginRound();
        e.observe("save_files", json.createObjectNode(), AgentTool.Result.ok("Nothing changed"));
        assertFalse(e.endRound(false).path("new_state_observed").asBoolean());
        for (int i = 0; i < 2; i++) {
            e.beginRound();
            e.observe("apply_edits", json.createObjectNode(), new AgentTool.Result("changed", false, true));
            e.endRound(false);
            e.beginRound();
            e.observe("save_files", json.createObjectNode(), AgentTool.Result.failure("Permission denied"));
            assertFalse(e.endRound(false).path("new_state_observed").asBoolean());
            e.beginRound();
            e.observe("save_files", json.createObjectNode(), AgentTool.Result.ok("Revisions saved"));
            assertTrue(e.endRound(false).path("new_state_observed").asBoolean());
            e.beginRound();
            e.observe("save_files", json.createObjectNode(), AgentTool.Result.ok("Revisions saved"));
            assertFalse(e.endRound(false).path("new_state_observed").asBoolean());
        }
        assertFalse(e.canComplete());
    }
}
