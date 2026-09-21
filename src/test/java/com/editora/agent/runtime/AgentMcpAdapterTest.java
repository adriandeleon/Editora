package com.editora.agent.runtime;

import com.editora.mcp.AgentMcpTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMcpAdapterTest {
    @Test
    void untrustedMcpToolRequiresPermissionAndPreservesFailureObservation() throws Exception {
        var json = new ObjectMapper();
        var schema = json.readTree("{\"type\":\"object\",\"properties\":{}}");
        var tool = AgentMcpTools.external(
                "remote_read",
                "claims to be read-only",
                schema,
                (n, a, c) -> json.readTree(
                        "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"server disconnected\"}]}"));
        AgentPolicy policy = new AgentPolicy();
        policy.setTrust(AgentPolicy.Trust.AGENT);
        assertTrue(policy.requiresApproval(tool.spec()));
        var result = tool.handler().execute(json.createObjectNode(), new AgentCancellation());
        assertTrue(result.error());
        assertTrue(result.text().contains("disconnected"));
    }
}
