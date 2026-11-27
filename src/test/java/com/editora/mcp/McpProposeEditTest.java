package com.editora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code propose_edit} and {@code edit_buffer} under the approval setting, on {@link McpTools} (no HTTP, no window). */
class McpProposeEditTest {

    private final ObjectMapper m = new ObjectMapper();
    private final McpToolsTest.StubBridge bridge = new McpToolsTest.StubBridge();
    private final McpTools tools = new McpTools(bridge, m);

    private ObjectNode call(String name, String args) throws Exception {
        ObjectNode params = m.createObjectNode();
        params.put("name", name);
        params.set("arguments", m.readTree(args));
        params.set(
                "_meta",
                m.readTree(
                        "{\"io.modelcontextprotocol/clientInfo\":{\"name\":\"claude-code\",\"title\":\"Claude Code\"}}"));
        return tools.callTool(params);
    }

    private JsonNode payload(ObjectNode result) throws Exception {
        return m.readTree(result.at("/content/0/text").asText());
    }

    @Test
    void theToolIsListedAsAnEditWithEditBuffersArgumentsAndAReason() {
        JsonNode tool = null;
        for (JsonNode t : tools.listToolsResult().get("tools")) {
            if ("propose_edit".equals(t.get("name").asText())) {
                tool = t;
            }
        }
        assertTrue(tool != null, "listed");
        JsonNode props = tool.at("/inputSchema/properties");
        for (String p :
                new String[] {"path", "old_text", "new_text", "replace_all", "replace_whole_buffer", "reason"}) {
            assertTrue(props.has(p), p);
        }
        assertFalse(tool.at("/annotations/readOnlyHint").asBoolean(true), "it changes the buffer");
    }

    @Test
    void theAnswerIsTheUsersDecisionAndReason() throws Exception {
        ObjectNode ok = call("propose_edit", "{\"old_text\":\"a\",\"new_text\":\"b\",\"reason\":\"Clearer.\"}");
        assertEquals("accepted", payload(ok).get("outcome").asText());
        assertFalse(payload(ok).has("reason"));
        assertEquals("null|a|b|false|false|Clearer.", bridge.lastProposal);
        assertEquals("Claude Code", bridge.lastAgent);

        bridge.proposal = new McpBridge.ProposalOutcome("rejected", "Keep the old name.");
        ObjectNode no = call("propose_edit", "{\"old_text\":\"a\",\"new_text\":\"b\"}");
        assertFalse(no.get("isError").asBoolean(), "a rejection is an answer, not an error");
        assertEquals("rejected", payload(no).get("outcome").asText());
        assertEquals("Keep the old name.", payload(no).get("reason").asText());

        bridge.proposal = new McpBridge.ProposalOutcome("refused", "old_text not found in the buffer.");
        ObjectNode refused = call("propose_edit", "{\"old_text\":\"zz\",\"new_text\":\"b\"}");
        assertTrue(refused.get("isError").asBoolean());
        assertEquals(
                "old_text not found in the buffer.",
                refused.at("/content/0/text").asText());
    }

    @Test
    void itsArgumentsFollowEditBuffersRules() throws Exception {
        assertTrue(call("propose_edit", "{\"new_text\":\"b\"}").get("isError").asBoolean(), "no old_text");
        assertTrue(call("propose_edit", "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_whole_buffer\":true}")
                .get("isError")
                .asBoolean());
        assertNull(bridge.lastProposal, "neither reached the window");
        call("propose_edit", "{\"new_text\":\"all\",\"replace_whole_buffer\":true}");
        assertEquals("null|null|all|false|true|null", bridge.lastProposal);
    }

    @Test
    void withApprovalOnEditBufferWaitsForTheUser() throws Exception {
        bridge.needApproval = true;
        ObjectNode ok = call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_all\":true}");
        assertTrue(payload(ok).get("applied").asBoolean());
        assertEquals("null|a|b|true|false|null", bridge.lastProposal);
        assertEquals(0, bridge.edits, "not applied directly");

        bridge.proposal = new McpBridge.ProposalOutcome("rejected", "No.");
        ObjectNode no = call("edit_buffer", "{\"new_text\":\"x\",\"replace_whole_buffer\":true}");
        assertTrue(no.get("isError").asBoolean());
        assertEquals("The user rejected the edit: No.", no.at("/content/0/text").asText());
        assertEquals(0, bridge.replacements);

        bridge.proposal = new McpBridge.ProposalOutcome("timeout", "");
        assertTrue(call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\"}")
                .at("/content/0/text")
                .asText()
                .contains("in time"));

        bridge.needApproval = false;
        bridge.lastProposal = null;
        call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\"}");
        assertNull(bridge.lastProposal, "off: applied at once");
        assertEquals(1, bridge.edits);
    }
}
