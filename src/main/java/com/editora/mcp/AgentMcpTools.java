package com.editora.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentTool;
import com.editora.agent.runtime.AgentWorkspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Reuses MCP catalog/dispatch behind the runtime's policy boundary; no provider-specific tool lists. */
public final class AgentMcpTools {
    private AgentMcpTools() {}

    @FunctionalInterface
    public interface Transport {
        JsonNode call(String name, JsonNode arguments, AgentCancellation cancellation) throws Exception;
    }

    /** An outbound transport can register discovered tools here. Untrusted server annotations never grant read access. */
    public static AgentTool external(String name, String description, JsonNode inputSchema, Transport transport) {
        return new AgentTool(
                new AgentTool.Spec(
                        name,
                        description,
                        inputSchema,
                        null,
                        AgentTool.Effect.EXTERNAL,
                        Duration.ofSeconds(60),
                        true,
                        "mcp"),
                (arguments, cancellation) -> {
                    cancellation.check();
                    var result = observation(transport.call(name, arguments, cancellation));
                    // Unknown external effects are conservatively treated as potentially mutating.
                    return new AgentTool.Result(result.text(), result.error(), true);
                });
    }

    /** Only two audited read operations from Editora's inbound MCP surface are exposed natively. */
    public static List<AgentTool> editorReads(McpBridge bridge, AgentWorkspace workspace) {
        ObjectMapper mapper = new ObjectMapper();
        McpTools catalog = new McpTools(bridge, mapper);
        List<AgentTool> result = new ArrayList<>();
        for (JsonNode definition : catalog.listToolsResult().path("tools")) {
            String name = definition.path("name").asText();
            if (!name.equals("document_symbols") && !name.equals("git_status")) {
                continue;
            }
            ObjectNode schema = definition.path("inputSchema").deepCopy();
            if (name.equals("document_symbols")) {
                schema.putArray("required").add("path");
            }
            result.add(new AgentTool(
                    new AgentTool.Spec(
                            name,
                            definition.path("description").asText(),
                            schema,
                            null,
                            AgentTool.Effect.READ,
                            Duration.ofSeconds(20),
                            false,
                            "editora-mcp"),
                    (arguments, cancellation) -> {
                        cancellation.check();
                        if (name.equals("document_symbols")) {
                            workspace.resolve(arguments.path("path").asText());
                        }
                        if (name.equals("git_status")) {
                            McpBridge.GitState status = bridge.gitStatus();
                            if (status.repo()
                                    && !workspace
                                            .root()
                                            .equals(java.nio.file.Path.of(status.root())
                                                    .toRealPath())) {
                                return AgentTool.Result.failure("Git repository root is outside the agent workspace");
                            }
                            cancellation.check();
                            // Convert this snapshot directly; do not query mutable repository state a second time.
                            ObjectNode data = mapper.createObjectNode()
                                    .put("repo", status.repo())
                                    .put("branch", status.branch())
                                    .put("ahead", status.ahead())
                                    .put("behind", status.behind());
                            var files = data.putArray("files");
                            for (var file : status.files()) {
                                files.addObject()
                                        .put("path", file.path())
                                        .put("index", file.index())
                                        .put("worktree", file.worktree());
                            }
                            return AgentTool.Result.ok(data.toString());
                        }
                        ObjectNode params = mapper.createObjectNode().put("name", name);
                        ObjectNode confined = arguments.deepCopy();
                        confined.put(
                                "path",
                                workspace
                                        .resolve(arguments.path("path").asText())
                                        .toString());
                        params.set("arguments", confined);
                        var output = catalog.callTool(params);
                        cancellation.check();
                        return observation(output);
                    }));
        }
        return List.copyOf(result);
    }

    private static AgentTool.Result observation(JsonNode result) {
        if (result == null || !result.path("content").isArray()) {
            return AgentTool.Result.failure("Malformed MCP tool result");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(com.editora.agent.runtime.AgentContext.bounded(
                                block.path("text").asText(), 8000))
                        .append('\n');
            }
            if (text.length() >= 8000) {
                break;
            }
        }
        return new AgentTool.Result(
                com.editora.agent.runtime.AgentContext.bounded(text.toString(), 8000),
                result.path("isError").asBoolean(),
                false);
    }
}
