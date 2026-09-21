package com.editora.config;

/** Explicit per-provider/model overrides. Zero limits mean automatic; no credentials belong here. */
public record AgentModelProfileConfig(
        String provider,
        String model,
        int contextTokens,
        int outputTokens,
        int maxOutputTokens,
        String tools,
        boolean discovery,
        Double temperature,
        Long seed) {
    public AgentModelProfileConfig {
        if (provider == null
                || model == null
                || model.length() > 200
                || contextTokens < 0
                || contextTokens > 2_000_000
                || (contextTokens > 0 && contextTokens < 1024)
                || outputTokens < 0
                || outputTokens > 131072
                || maxOutputTokens < 0
                || maxOutputTokens > 131072
                || (maxOutputTokens > 0 && outputTokens > maxOutputTokens)
                || (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)))
            throw new IllegalArgumentException("Invalid agent model profile");
        if (tools == null) tools = "UNKNOWN";
        if (!java.util.Set.of("UNKNOWN", "SUPPORTED", "UNSUPPORTED").contains(tools))
            throw new IllegalArgumentException("Invalid tool support");
    }

    public static AgentModelProfileConfig automatic(String provider, String model) {
        return new AgentModelProfileConfig(provider, model, 0, 0, 0, "UNKNOWN", true, null, null);
    }
}
