package com.editora.agent.runtime;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.AgentModelProfileConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentModelProfileTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void constrainedProfilePreservesCompleteReadBatchByReducingAutomaticOutput() throws Exception {
        var tools = new AgentTools();
        for (int i = 0; i < 35; i++) {
            String name = "read" + i;
            tools.register(new AgentTool(
                    new AgentTool.Spec(
                            name,
                            "description ".repeat(17),
                            json.readTree("{\"type\":\"object\"}"),
                            null,
                            AgentTool.Effect.READ,
                            java.time.Duration.ofSeconds(1),
                            true,
                            "test"),
                    (a, c) -> AgentTool.Result.ok(name + ":" + "x".repeat(4000))));
        }
        var rounds = new AtomicInteger();
        var profile = AgentModelDiscovery.resolve(
                "test",
                "test",
                16384,
                AgentModelProfileConfig.automatic("test", "test"),
                json.createObjectNode(),
                "UNKNOWN",
                "NOT_REQUESTED");
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 16384, 4096);
            }

            public AgentModelProfile profile() {
                return profile;
            }

            public AgentTokens.Counter tokenCounter() {
                return text ->
                        new AgentTokens.Count((AgentContext.cost(text) + 2L) / 3L, AgentTokens.Provenance.HEURISTIC);
            }

            public Response respond(Request r, AgentCancellation c, java.util.function.Consumer<String> text) {
                if (rounds.incrementAndGet() == 1)
                    return new Response(
                            "",
                            List.of(new Call("a", "read0", "{}"), new Call("b", "read1", "{}")),
                            "tool_calls",
                            0,
                            0);
                if (rounds.get() == 2) {
                    assertTrue(r.outputTokens() < 4096 && r.outputTokens() >= 2048);
                    assertEquals(
                            2,
                            r.messages().stream()
                                    .filter(m ->
                                            "tool".equals(m.role()) && m.text().length() > 4000)
                                    .count());
                    return new Response("partial", List.of(), "length", 0, 0);
                }
                assertTrue(
                        r.outputTokens() < 8192,
                        "recovery cannot reserve so much output that the catalog no longer fits");
                return new Response("done", List.of(), "stop", 0, 0);
            }
        };
        try (var runtime = new AgentRuntime(
                model,
                tools,
                new AgentPolicy(),
                (s, a, c) -> fail(),
                c -> fail(),
                "system",
                AgentRuntime.Limits.DEFAULT,
                e -> {},
                t -> {})) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("Compare both complete files")
                            .get(3, TimeUnit.SECONDS)
                            .state());
        }
        assertEquals(3, rounds.get());
    }

    @Test
    void automaticOutputRetainsMoreContextWithoutOverridingExplicitBudgetsOrRecovery() {
        var automatic = AgentModelDiscovery.resolve(
                "test",
                "test",
                16384,
                AgentModelProfileConfig.automatic("test", "test"),
                json.createObjectNode(),
                "UNKNOWN",
                "NOT_REQUESTED");
        assertEquals(2400, automatic.output(0, true, 2400));
        assertEquals(2048, automatic.output(0, true, 1000));
        assertEquals(8192, automatic.output(1, true, 1000));
        var explicit = AgentModelDiscovery.resolve(
                "test",
                "test",
                16384,
                new AgentModelProfileConfig("test", "test", 0, 8192, 0, "UNKNOWN", false, null, null),
                json.createObjectNode(),
                "UNKNOWN",
                "NOT_REQUESTED");
        assertEquals(8192, explicit.output(0, true, 1000));
    }

    @Test
    void discoverySeparatesLoadedCapacityAndOverridesWithoutGuessingFromNames() throws Exception {
        var cfg = AgentModelProfileConfig.automatic("lmstudio", "fictional-reasoning-pro");
        var data = json.readTree("""
            {"models":[{"type":"llm","key":"fictional-reasoning-pro","max_context_length":262144,
            "capabilities":{"trained_for_tool_use":true},"loaded_instances":[{"id":"one","config":{"context_length":16384}}]}]}
            """);
        var profile =
                AgentModelDiscovery.resolve("lmstudio", cfg.model(), 32768, cfg, data, "LM_STUDIO_API_V1", "AVAILABLE");
        assertEquals(262144, profile.modelContext().value());
        assertEquals(16384, profile.context().value());
        assertEquals(AgentModelProfile.Provenance.DISCOVERED, profile.context().provenance());
        assertFalse(profile.features().containsKey(AgentModel.Feature.REASONING));
        var configured = new AgentModelProfileConfig(
                "lmstudio", cfg.model(), 65536, 8192, 16384, "UNSUPPORTED", true, null, null);
        profile = AgentModelDiscovery.resolve(
                "lmstudio", cfg.model(), 32768, configured, data, "LM_STUDIO_API_V1", "AVAILABLE");
        assertEquals(65536, profile.context().value());
        assertEquals(AgentModel.Support.UNSUPPORTED, profile.tools().value());
        assertEquals(AgentModelProfile.Provenance.CONFIGURED, profile.context().provenance());
        var unknown = AgentModelDiscovery.resolve("openai", "not-reported", 32768, cfg, data, "UNKNOWN", "UNAVAILABLE");
        assertEquals(AgentModel.Support.UNKNOWN, unknown.tools().value());
        assertEquals(0, unknown.loadedContext().value());
        var capabilities = (com.fasterxml.jackson.databind.node.ObjectNode)
                data.path("models").get(0).path("capabilities");
        capabilities.putObject("reasoning").putArray("allowed_options").add("off");
        var offOnly =
                AgentModelDiscovery.resolve("lmstudio", cfg.model(), 32768, cfg, data, "LM_STUDIO_API_V1", "AVAILABLE");
        assertEquals(
                AgentModel.Support.UNSUPPORTED,
                offOnly.features().get(AgentModel.Feature.REASONING).value());
        assertEquals(4096, offOnly.preferredOutput().value());
        capabilities.putObject("reasoning").putArray("allowed_options").add("future-mode");
        assertFalse(
                AgentModelDiscovery.resolve("lmstudio", cfg.model(), 32768, cfg, data, "LM_STUDIO_API_V1", "AVAILABLE")
                        .features()
                        .containsKey(AgentModel.Feature.REASONING));
    }

    @Test
    void truncatedCallsAreDiscardedAsABatchAndRetriedWithBoundedBudgets() throws Exception {
        var outputs = new ArrayList<Integer>();
        var executed = new AtomicInteger();
        var rounds = new AtomicInteger();
        var profile = AgentModelDiscovery.resolve(
                "test",
                "test",
                32768,
                new AgentModelProfileConfig("test", "test", 32768, 2048, 8192, "SUPPORTED", false, null, null),
                json.createObjectNode(),
                "UNKNOWN",
                "NOT_REQUESTED");
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 32768, 2048);
            }

            public AgentModelProfile profile() {
                return profile;
            }

            public Response respond(Request request, AgentCancellation c, java.util.function.Consumer<String> text)
                    throws Exception {
                outputs.add(request.outputTokens());
                int i = rounds.incrementAndGet();
                if (i == 1)
                    return new Response("partial", List.of(new Call("discard", "mutate", "{}")), "length", 0, 0);
                if (i == 2) throw new AgentModel.OutputLimit();
                assertTrue(request.messages().stream()
                        .flatMap(m -> m.calls().stream())
                        .noneMatch(call -> call.id().equals("discard")));
                return new Response("done", List.of(), "stop", 0, 0);
            }
        };
        var tools = new AgentTools()
                .register(new AgentTool(
                        new AgentTool.Spec(
                                "mutate",
                                "test",
                                json.readTree("{\"type\":\"object\"}"),
                                null,
                                AgentTool.Effect.WORKSPACE_WRITE,
                                java.time.Duration.ofSeconds(1),
                                true,
                                "test"),
                        (a, c) -> {
                            executed.incrementAndGet();
                            return AgentTool.Result.ok("ran");
                        }));
        try (var runtime = new AgentRuntime(
                model,
                tools,
                new AgentPolicy(),
                (s, a, c) -> true,
                c -> new AgentRuntime.Verification(true, "verified"),
                "system",
                AgentRuntime.Limits.DEFAULT,
                e -> {},
                t -> {})) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("work").get(3, TimeUnit.SECONDS).state());
        }
        assertEquals(List.of(2048, 4096, 8192), outputs);
        assertEquals(0, executed.get());
    }

    @Test
    void repeatedTextExhaustionStopsAfterTwoRecoveries() throws Exception {
        var rounds = new AtomicInteger();
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 32768, 2048);
            }

            public AgentModelProfile profile() {
                return AgentModelDiscovery.resolve(
                        "test",
                        "test",
                        32768,
                        AgentModelProfileConfig.automatic("test", "test"),
                        json.createObjectNode(),
                        "UNKNOWN",
                        "NOT_REQUESTED");
            }

            public Response respond(Request r, AgentCancellation c, java.util.function.Consumer<String> text) {
                rounds.incrementAndGet();
                return new Response("partial", List.of(), "max_tokens", 0, 0);
            }
        };
        try (var runtime = new AgentRuntime(
                model,
                new AgentTools(),
                new AgentPolicy(),
                (s, a, c) -> fail(),
                c -> fail(),
                "system",
                AgentRuntime.Limits.DEFAULT,
                e -> {},
                t -> {})) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT,
                    runtime.submit("work").get(3, TimeUnit.SECONDS).state());
        }
        assertEquals(3, rounds.get());
    }
}
