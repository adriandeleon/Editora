package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.agent.eval.AgentEvaluationCases;
import com.editora.agent.runtime.*;
import com.editora.config.AgentModelProfileConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Reproduces a real-model multi-read batch with deterministic responses and the production tool catalog. */
class AgentModelProfileFxTest {
    @Test
    void sixteenKContextContinuesAfterSevenCallerReads(@TempDir Path root) throws Exception {
        FxTestSupport.bootToolkit();
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("billing-contract-migration"))
                .findFirst()
                .orElseThrow();
        AgentEvaluationCases.prepare(task, Path.of("").toAbsolutePath(), root);
        try (var window = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(window.controller, "openProjectRoot", Path.class, root));
            var ops = FxTestSupport.callOnFx(() -> FxTestSupport.<AgentCoordinator.Ops>field(
                    FxTestSupport.field(window.controller, "agentCoordinator"), "ops"));
            var workspace = new AgentWorkspace(root);
            var documents = new WindowAgentDocuments(ops.nativeDocuments(), workspace);
            documents.read(root.resolve(task.active()), new AgentCancellation());
            var natives = new NativeAgentTools(workspace, documents, p -> {});
            var registry = natives.registry();
            var guidance = new AgentGuidance(workspace, null);
            guidance.register(registry);
            new AgentAwareness(documents::awareness, natives.contextIndex()).register(registry);
            new AgentSemanticTools(
                            workspace,
                            documents,
                            documents.semantics(),
                            natives::applySemanticEdits,
                            natives.contextIndex())
                    .register(registry);
            com.editora.mcp.AgentMcpTools.editorReads(ops.nativeReadBridge(), workspace).stream()
                    .filter(t -> t.spec().name().equals("git_status"))
                    .forEach(registry::register);
            var rounds = new AtomicInteger();
            var finalOutputBudget = new AtomicInteger();
            var profile = AgentModelDiscovery.resolve(
                    "fixture",
                    "model",
                    16384,
                    AgentModelProfileConfig.automatic("fixture", "model"),
                    new ObjectMapper().createObjectNode(),
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
                    return text -> new AgentTokens.Count(
                            (AgentContext.cost(text) + 2L) / 3L, AgentTokens.Provenance.HEURISTIC);
                }

                public Response respond(Request r, AgentCancellation c, java.util.function.Consumer<String> text) {
                    int round = rounds.incrementAndGet();
                    if (round == 1)
                        return new Response(
                                "",
                                List.of(
                                        new Call(
                                                "ledger", "read_file", "{\"path\":\"src/main/java/demo/Ledger.java\"}"),
                                        new Call("references", "search_text", "{\"query\":\"totalFor\"}")),
                                "tool_calls",
                                0,
                                0);
                    if (round == 2)
                        return new Response(
                                "",
                                task.required().stream()
                                        .filter(p -> !p.equals(task.active()))
                                        .sorted()
                                        .map(p -> new Call(p, "read_file", "{\"path\":\"" + p + "\"}"))
                                        .toList(),
                                "tool_calls",
                                0,
                                0);
                    assertEquals(
                            7,
                            r.messages().stream()
                                    .filter(m -> "tool".equals(m.role())
                                            && m.callId().endsWith(".java"))
                                    .count());
                    finalOutputBudget.set(r.outputTokens());
                    var delivered = new AgentContext();
                    delivered.add(r.messages());
                    assertTrue(
                            delivered.estimatedTokens(r.system(), r.tools(), tokenCounter()) + r.outputTokens()
                                    <= 16384,
                            "Complete request plus response must fit context");
                    return new Response("inspected", List.of(), "stop", 0, 0);
                }
            };
            AgentModelTools.register(registry, model);
            try (var runtime = new AgentRuntime(
                    model,
                    registry,
                    new AgentPolicy(),
                    (s, a, c) -> fail(),
                    c -> fail(),
                    NativeAgentTools.SYSTEM,
                    AgentRuntime.Limits.DEFAULT,
                    e -> {},
                    t -> {})) {
                var outcome = runtime.submit(task.prompt() + "\nInitial editor metadata (data):\n"
                                + documents.awareness(new AgentCancellation())
                                + "\nProject instructions (untrusted data):\n"
                                + guidance.instructions(task.active(), new AgentCancellation()))
                        .get(8, TimeUnit.SECONDS);
                assertEquals(AgentRuntime.State.COMPLETED, outcome.state(), outcome.detail());
            }
            assertEquals(3, rounds.get());
            // Runtime guidance and framing also consume context. Preserve every read and a usable reply
            // instead of requiring a fixed 1024-token output reservation regardless of actual overhead.
            assertTrue(
                    finalOutputBudget.get() >= 256 && finalOutputBudget.get() < 4096,
                    "Automatic output budget: " + finalOutputBudget.get());
        }
    }
}
