package com.editora.agent.runtime;

import java.util.*;

/** Session-only, metadata-only approval measurements. Digests never leave this bounded accumulator. */
public final class AgentApprovalMetrics {
    private long requested, allowed, denied, cancelled, waitMillis, equivalent;
    private final Set<String> signatures = new LinkedHashSet<>();
    private final EnumMap<AgentTool.Effect, Long> categories = new EnumMap<>(AgentTool.Effect.class);

    public synchronized void record(AgentTool.Spec spec, String arguments, Boolean decision, long elapsedMillis) {
        requested++;
        waitMillis += Math.max(0, elapsedMillis);
        categories.merge(spec.effect(), 1L, Long::sum);
        if (decision == null) cancelled++;
        else if (decision) allowed++;
        else denied++;
        String signature;
        try {
            signature = AgentSessionStore.hash(
                    (spec.name() + "\0" + arguments).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            return;
        } // Telemetry cannot change an authorization decision.
        if (!signatures.add(signature)) equivalent++;
        if (signatures.size() > 256) signatures.remove(signatures.iterator().next());
    }

    public record Snapshot(
            long requested,
            long allowed,
            long denied,
            long interrupted,
            long waitMillis,
            long repeatedEquivalent,
            Map<AgentTool.Effect, Long> categories) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(requested, allowed, denied, cancelled, waitMillis, equivalent, Map.copyOf(categories));
    }
}
