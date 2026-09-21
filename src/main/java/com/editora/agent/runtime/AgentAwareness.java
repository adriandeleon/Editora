package com.editora.agent.runtime;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Progressive IDE context: metadata and ranked paths first, source text only on demand. */
public final class AgentAwareness {
    @FunctionalInterface
    public interface Source {
        JsonNode snapshot(AgentCancellation cancellation) throws Exception;
    }

    private final Source source;
    private final AgentContextRanker.Index discoveries;

    public AgentAwareness(Source source) {
        this(source, new AgentContextRanker.Index());
    }

    public AgentAwareness(Source source, AgentContextRanker.Index discoveries) {
        this.source = source;
        this.discoveries = discoveries;
    }

    public void register(AgentTools tools) {
        var json = new ObjectMapper();
        var schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties");
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        "editor_context",
                        "Inspect current editor/cursor/selection, open document revisions and ranked context candidates with reasons. Metadata only; use semantic tools and targeted reads for deeper discovery.",
                        schema,
                        null,
                        AgentTool.Effect.READ,
                        Duration.ofSeconds(15),
                        true,
                        "editora"),
                (args, c) -> {
                    var snapshot = (ObjectNode) source.snapshot(c);
                    var candidates = new java.util.ArrayList<>(discoveries.candidates());
                    String active = snapshot.path("active").path("path").asText();
                    for (var file : snapshot.path("open")) {
                        var signals = java.util.EnumSet.noneOf(AgentContextRanker.Signal.class);
                        String path = file.path("path").asText();
                        if (path.equals(active)) signals.add(AgentContextRanker.Signal.ACTIVE);
                        if (path.equals(active)
                                && !snapshot.path("active")
                                        .path("selection")
                                        .asText()
                                        .isEmpty()) signals.add(AgentContextRanker.Signal.USER_SELECTED);
                        if (file.path("dirty").asBoolean()) signals.add(AgentContextRanker.Signal.UNSAVED);
                        if (path.equals(active)
                                && snapshot.path("active").path("errors").asInt() > 0)
                            signals.add(AgentContextRanker.Signal.DIAGNOSTIC);
                        candidates.add(new AgentContextRanker.Candidate(path, signals));
                    }
                    var ranked = snapshot.putArray("ranked");
                    for (var candidate : AgentContextRanker.rank(candidates, 12)) {
                        var item =
                                ranked.addObject().put("path", candidate.path()).put("score", candidate.score());
                        var why = item.putArray("signals");
                        candidate.signals().stream().sorted().forEach(s -> why.add(s.name()));
                    }
                    return AgentTool.Result.ok(snapshot.toString());
                }));
    }
}
