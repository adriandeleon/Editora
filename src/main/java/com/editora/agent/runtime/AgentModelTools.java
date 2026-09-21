package com.editora.agent.runtime;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Model introspection reports unknown capability support honestly; observed support can strengthen it. */
public final class AgentModelTools {
    private AgentModelTools() {}

    public static void register(AgentTools tools, AgentModel model) {
        var json = new ObjectMapper();
        var schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties");
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        "agent_model",
                        "Inspect model capabilities, configured context/output limits and token-count provenance. UNKNOWN means unconfirmed; byte estimates are heuristic.",
                        schema,
                        null,
                        AgentTool.Effect.READ,
                        Duration.ofSeconds(5),
                        true,
                        "editora"),
                (a, c) -> {
                    var caps = model.capabilities();
                    var out = json.createObjectNode()
                            .put("tools", caps.tools())
                            .put("streaming", caps.streaming())
                            .put("contextTokens", caps.contextTokens())
                            .put("outputTokens", caps.outputTokens())
                            .put(
                                    "contextLimitSource",
                                    model.profile().context().provenance().name())
                            .put(
                                    "tokenCountProvenance",
                                    model.tokenCounter().count("").provenance().name());
                    out.set("profile", model.profile().toJson());
                    var features = out.putObject("features");
                    for (var feature : AgentModel.Feature.values())
                        features.put(feature.name(), caps.support(feature).name());
                    return AgentTool.Result.ok(out.toString());
                }));
    }
}
