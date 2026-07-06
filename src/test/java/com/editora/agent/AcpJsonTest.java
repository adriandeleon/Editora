package com.editora.agent;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the ACP JSON layer: envelopes, request params, and incoming-message parsing. */
class AcpJsonTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void requestEnvelopeCarriesIdMethodParams() throws Exception {
        ObjectNode n = AcpJson.request(m, 7, "session/prompt", AcpJson.promptParams(m, "s1", "hi"));
        assertEquals("2.0", n.get("jsonrpc").asText());
        assertEquals(7, n.get("id").asLong());
        assertEquals("session/prompt", n.get("method").asText());
        assertEquals("hi", n.get("params").get("prompt").get(0).get("text").asText());
        // newline-delimited transport: a serialized message must be a single line
        assertFalse(m.writeValueAsString(n).contains("\n"));
    }

    @Test
    void notificationHasNoId() {
        ObjectNode n = AcpJson.notification(m, "session/cancel", AcpJson.cancelParams(m, "s1"));
        assertFalse(n.has("id"));
        assertEquals("s1", n.get("params").get("sessionId").asText());
    }

    @Test
    void initializeDeclaresFsCapabilities() {
        ObjectNode p = AcpJson.initializeParams(m);
        assertEquals(AcpJson.PROTOCOL_VERSION, p.get("protocolVersion").asInt());
        assertTrue(p.get("clientCapabilities").get("fs").get("readTextFile").asBoolean());
        assertTrue(p.get("clientCapabilities").get("fs").get("writeTextFile").asBoolean());
    }

    @Test
    void newSessionCarriesCwdAndEmptyMcpServers() {
        ObjectNode p = AcpJson.newSessionParams(m, "/work/proj");
        assertEquals("/work/proj", p.get("cwd").asText());
        assertTrue(p.get("mcpServers").isArray());
        assertEquals(0, p.get("mcpServers").size());
    }

    @Test
    void errorResponseShape() {
        ObjectNode n = AcpJson.errorResponse(m, m.getNodeFactory().numberNode(3), AcpJson.METHOD_NOT_FOUND, "nope");
        assertEquals(AcpJson.METHOD_NOT_FOUND, n.get("error").get("code").asInt());
        assertEquals("nope", n.get("error").get("message").asText());
    }

    @Test
    void nullResultResponseIsJsonNull() {
        ObjectNode n = AcpJson.response(m, m.getNodeFactory().numberNode(4), null);
        assertTrue(n.get("result").isNull());
    }

    @Test
    void parsesAgentMessageChunk() throws Exception {
        JsonNode params = m.readTree("{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\","
                + "\"content\":{\"type\":\"text\",\"text\":\"Hello\"}}}");
        AcpJson.Update u = AcpJson.parseUpdate(params);
        assertEquals(AcpJson.UpdateKind.AGENT_MESSAGE, u.kind());
        assertEquals("s1", u.sessionId());
        assertEquals("Hello", u.text());
    }

    @Test
    void parsesToolCallTitleAndUpdateStatus() throws Exception {
        AcpJson.Update call = AcpJson.parseUpdate(m.readTree(
                "{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"tool_call\",\"title\":\"Read main.java\"}}"));
        assertEquals(AcpJson.UpdateKind.TOOL_CALL, call.kind());
        assertEquals("Read main.java", call.text());
        AcpJson.Update upd = AcpJson.parseUpdate(m.readTree(
                "{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"tool_call_update\",\"status\":\"failed\"}}"));
        assertEquals(AcpJson.UpdateKind.TOOL_CALL_UPDATE, upd.kind());
        assertEquals("failed", upd.text());
    }

    @Test
    void parsesPlanEntries() throws Exception {
        AcpJson.Update u = AcpJson.parseUpdate(
                m.readTree("{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"plan\",\"entries\":"
                        + "[{\"content\":\"step one\",\"status\":\"completed\"},"
                        + "{\"content\":\"step two\",\"status\":\"in_progress\"},"
                        + "{\"content\":\"step three\"}]}}"));
        assertEquals(AcpJson.UpdateKind.PLAN, u.kind());
        assertEquals("step one\nstep two\nstep three", u.text());
        assertEquals(3, u.planEntries().size());
        assertEquals(
                new AcpJson.PlanEntry("step one", "completed"), u.planEntries().get(0));
        assertEquals(
                new AcpJson.PlanEntry("step two", "in_progress"),
                u.planEntries().get(1));
        // a missing "status" field defaults to empty string (textOrEmpty semantics), never null
        assertEquals(new AcpJson.PlanEntry("step three", ""), u.planEntries().get(2));
    }

    @Test
    void nonPlanUpdatesHaveEmptyPlanEntries() throws Exception {
        AcpJson.Update u = AcpJson.parseUpdate(m.readTree("{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":"
                + "\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}}"));
        assertEquals(List.of(), u.planEntries());
    }

    @Test
    void parsesModeChangedUpdate() throws Exception {
        AcpJson.Update u = AcpJson.parseUpdate(
                m.readTree(
                        "{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"current_mode_update\",\"currentModeId\":\"plan\"}}"));
        assertEquals(AcpJson.UpdateKind.MODE_CHANGED, u.kind());
        assertEquals("plan", u.text());
    }

    @Test
    void parsesSessionInfoModelsAndModes() throws Exception {
        JsonNode result = m.readTree("{\"sessionId\":\"s1\","
                + "\"models\":{\"currentModelId\":\"opus\",\"availableModels\":["
                + "{\"modelId\":\"opus\",\"name\":\"Claude Opus\",\"description\":\"Most capable\"},"
                + "{\"modelId\":\"sonnet\",\"name\":\"Claude Sonnet\",\"description\":\"Balanced\"}]},"
                + "\"modes\":{\"currentModeId\":\"default\",\"availableModes\":["
                + "{\"id\":\"default\",\"name\":\"Default\",\"description\":\"Standard behavior\"},"
                + "{\"id\":\"plan\",\"name\":\"Plan Mode\",\"description\":\"Planning, no execution\"}]}}");
        AcpJson.SessionInfo info = AcpJson.parseSessionInfo(result);
        assertEquals("s1", info.sessionId());
        assertEquals("opus", info.currentModelId());
        assertEquals(2, info.models().size());
        assertEquals(
                new AcpJson.ModelInfo("opus", "Claude Opus", "Most capable"),
                info.models().get(0));
        assertEquals("default", info.currentModeId());
        assertEquals(2, info.modes().size());
        assertEquals(
                new AcpJson.ModeInfo("plan", "Plan Mode", "Planning, no execution"),
                info.modes().get(1));
    }

    @Test
    void parseSessionInfoIsNullSafe() {
        AcpJson.SessionInfo info = AcpJson.parseSessionInfo(null);
        assertNull(info.sessionId());
        assertEquals(List.of(), info.models());
        assertNull(info.currentModelId());
        assertEquals(List.of(), info.modes());
        assertNull(info.currentModeId());
    }

    @Test
    void setModelAndSetModeParamsShape() {
        ObjectNode modelParams = AcpJson.setModelParams(m, "s1", "opus");
        assertEquals("s1", modelParams.get("sessionId").asText());
        assertEquals("opus", modelParams.get("modelId").asText());
        ObjectNode modeParams = AcpJson.setModeParams(m, "s1", "plan");
        assertEquals("s1", modeParams.get("sessionId").asText());
        assertEquals("plan", modeParams.get("modeId").asText());
    }

    @Test
    void resumeSessionParamsShape() {
        ObjectNode p = AcpJson.resumeSessionParams(m, "s1", "/home/u/proj");
        assertEquals("s1", p.get("sessionId").asText());
        assertEquals("/home/u/proj", p.get("cwd").asText());
        assertTrue(p.get("mcpServers").isArray());
        assertEquals(0, p.get("mcpServers").size());
    }

    @Test
    void unknownUpdateKindIsOtherNeverThrows() throws Exception {
        AcpJson.Update u = AcpJson.parseUpdate(
                m.readTree("{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"something_new\"}}"));
        assertEquals(AcpJson.UpdateKind.OTHER, u.kind());
        assertEquals(AcpJson.UpdateKind.OTHER, AcpJson.parseUpdate(null).kind());
    }

    @Test
    void parsesPermissionOptionsAndTitle() throws Exception {
        JsonNode params = m.readTree("{\"sessionId\":\"s\",\"toolCall\":{\"title\":\"Run npm test\"},\"options\":["
                + "{\"optionId\":\"allow\",\"name\":\"Allow\",\"kind\":\"allow_once\"},"
                + "{\"optionId\":\"reject\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}");
        List<AcpJson.PermissionOption> options = AcpJson.parsePermissionOptions(params);
        assertEquals(2, options.size());
        assertEquals("allow", options.get(0).optionId());
        assertEquals("Allow", options.get(0).name());
        assertEquals("Run npm test", AcpJson.permissionTitle(params));
    }

    @Test
    void permissionOutcomeSelectedAndCancelled() {
        ObjectNode selected = AcpJson.permissionOutcome(m, "allow");
        assertEquals("selected", selected.get("outcome").get("outcome").asText());
        assertEquals("allow", selected.get("outcome").get("optionId").asText());
        ObjectNode cancelled = AcpJson.permissionOutcome(m, null);
        assertEquals("cancelled", cancelled.get("outcome").get("outcome").asText());
        assertNull(cancelled.get("outcome").get("optionId"));
    }
}
