package com.editora.agent.runtime;

import java.util.Map;

import com.editora.config.AgentModelProfileConfig;

/** Provider transport, model claims, loaded server configuration and user overrides remain distinct. */
public record AgentModelProfile(
        String provider,
        String model,
        String server,
        Value<Integer> modelContext,
        Value<Integer> loadedContext,
        Value<Integer> context,
        Value<Integer> preferredOutput,
        Value<Integer> maxOutput,
        Value<AgentModel.Support> tools,
        Map<AgentModel.Feature, Value<AgentModel.Support>> features,
        String discoveryStatus,
        AgentModelProfileConfig configuration,
        int recoveryLimit) {
    public enum Provenance {
        DISCOVERED,
        CONFIGURED,
        KNOWN_PROFILE,
        HEURISTIC,
        UNKNOWN
    }

    public record Value<T>(T value, Provenance provenance) {}

    public AgentModelProfile {
        features = Map.copyOf(features);
    }

    public static <T> Value<T> value(T value, Provenance source) {
        return new Value<>(value, source);
    }

    public static AgentModelProfile fixed(AgentModel.Capabilities caps) {
        return new AgentModelProfile(
                "adapter",
                "",
                "UNKNOWN",
                value(0, Provenance.UNKNOWN),
                value(0, Provenance.UNKNOWN),
                value(caps.contextTokens(), Provenance.CONFIGURED),
                value(caps.outputTokens(), Provenance.CONFIGURED),
                value(caps.outputTokens(), Provenance.CONFIGURED),
                value(
                        caps.tools() ? AgentModel.Support.SUPPORTED : AgentModel.Support.UNSUPPORTED,
                        Provenance.CONFIGURED),
                Map.of(),
                "NOT_REQUESTED",
                null,
                0);
    }

    /** Explicit wire DTO; agent.runtime remains closed to reflection in the packaged JPMS application. */
    public com.fasterxml.jackson.databind.node.ObjectNode toJson() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var out = json.createObjectNode()
                .put("provider", provider)
                .put("model", model)
                .put("server", server)
                .put("discoveryStatus", discoveryStatus)
                .put("recoveryLimit", recoveryLimit);
        field(out, "modelContext", modelContext);
        field(out, "loadedContext", loadedContext);
        field(out, "context", context);
        field(out, "preferredOutput", preferredOutput);
        field(out, "maxOutput", maxOutput);
        field(out, "tools", tools);
        var entries = out.putObject("features");
        for (var feature : AgentModel.Feature.values())
            field(
                    entries,
                    feature.name(),
                    features.getOrDefault(feature, value(AgentModel.Support.UNKNOWN, Provenance.UNKNOWN)));
        out.set("configuration", configuration == null ? json.nullNode() : json.valueToTree(configuration));
        return out;
    }

    private static void field(com.fasterxml.jackson.databind.node.ObjectNode out, String key, Value<?> value) {
        var entry = out.putObject(key).put("provenance", value.provenance().name());
        if (value.value() instanceof Number number) entry.put("value", number.intValue());
        else entry.put("value", value.value().toString());
    }

    public int output(int exhaustions, boolean afterTools) {
        return output(exhaustions, afterTools, Long.MAX_VALUE);
    }

    public int output(int exhaustions, boolean afterTools, long availableAfterInput) {
        int preferred = preferredOutput.value();
        // Tool-heavy turns need complete arguments; do not shrink reasoning models' allowance.
        if (afterTools
                && exhaustions == 0
                && configuration != null
                && configuration.outputTokens() == 0
                && features.getOrDefault(
                                        AgentModel.Feature.REASONING,
                                        value(AgentModel.Support.UNKNOWN, Provenance.UNKNOWN))
                                .value()
                        != AgentModel.Support.SUPPORTED) {
            // Trade automatic output headroom for retained code before compacting tool history.
            // Explicit preferences and truncation recovery retain their requested allowance.
            preferred = (int) Math.min(Math.min(preferred, 4096), Math.max(2048, availableAfterInput));
        }
        return (int) Math.min(maxOutput.value(), (long) preferred << Math.min(exhaustions, 3));
    }
}
