package com.editora.agent.runtime;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Hundreds of deterministic operations, compaction, failed verification, disk resume and provider switch. */
class AgentReliabilityStressTest {
    @TempDir
    Path root;

    @Test
    void longSessionPreservesProtocolAndResetsAuthorityAfterResume() throws Exception {
        var json = new ObjectMapper();
        var revision = new AtomicInteger();
        var reads = new AtomicInteger();
        var verified = new AtomicInteger(-1);
        var tools = new AgentTools();
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        "observe",
                        "read current revision",
                        json.readTree("{\"type\":\"object\"}"),
                        null,
                        AgentTool.Effect.READ,
                        Duration.ofSeconds(1),
                        true,
                        "test"),
                (a, c) -> AgentTool.Result.ok("revision=" + revision.get() + " observation=" + reads.incrementAndGet()
                        + " evidence ".repeat(100))));
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        "edit",
                        "compare and change",
                        json.readTree(
                                "{\"type\":\"object\",\"properties\":{\"revision\":{\"type\":\"integer\"}},\"required\":[\"revision\"]}"),
                        null,
                        AgentTool.Effect.WORKSPACE_WRITE,
                        Duration.ofSeconds(1),
                        true,
                        "test"),
                (a, c) -> {
                    if (!revision.compareAndSet(
                            a.path("revision").asInt(), a.path("revision").asInt() + 1))
                        return AgentTool.Result.failure("Stale document; reread before editing");
                    return new AgentTool.Result("edited revision=" + revision.get(), false, true);
                }));
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        "validate",
                        "test current revision",
                        json.readTree("{\"type\":\"object\"}"),
                        null,
                        AgentTool.Effect.EXTERNAL,
                        Duration.ofSeconds(1),
                        true,
                        "test"),
                (a, c) -> {
                    verified.set(revision.get());
                    return AgentTool.Result.ok("tests passed");
                }));
        var saved = new AtomicReference<AgentContext.Saved>();
        var checks = new AtomicInteger();
        var store = new AgentSessionStore(root.resolve("settings"));
        var workspace = new AgentWorkspace(root);
        var entry = new AgentSessionStore.Entry(
                UUID.randomUUID().toString(), "preserve-goal", root.toRealPath().toString(), "fake-one", "one", 0);
        try (var runtime = runtime(model("one", revision, 0), tools, verified, revision, checks)) {
            runtime.setCheckpoint(saved::set);
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("preserve-goal").get(15, TimeUnit.SECONDS).state());
            assertEquals(120, reads.get());
            assertEquals(2, revision.get());
            assertTrue(checks.get() >= 2);
        }
        assertTrue(saved.get().compacted() > 100);
        store.save(entry, saved.get(), json.createObjectNode());
        revision.incrementAndGet(); // independent user edit between turns; old provider's remembered revision is
        // obsolete
        verified.set(-1);
        var restored = store.load(entry.id(), workspace);
        try (var runtime = runtime(model("two", revision, 2), tools, verified, revision, checks)) {
            runtime.restore(restored.context());
            runtime.setCheckpoint(saved::set);
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("continue preserve-goal")
                            .get(15, TimeUnit.SECONDS)
                            .state());
        }
        assertEquals(240, reads.get());
        assertEquals(4, revision.get(), "stale attempted edit must preserve the user's revision");
        assertTrue(saved.get().compacted() > 200);
    }

    private static AgentRuntime runtime(
            AgentModel model, AgentTools tools, AtomicInteger validated, AtomicInteger revision, AtomicInteger checks) {
        return new AgentRuntime(
                model,
                tools,
                new AgentPolicy(),
                (s, a, c) -> true,
                c -> {
                    checks.incrementAndGet();
                    return new AgentRuntime.Verification(
                            validated.get() == revision.get(), "Run validation for current revision");
                },
                "system",
                new AgentRuntime.Limits(140, 160, 8192, 1200, Duration.ofSeconds(3)),
                e -> {},
                t -> {});
    }

    private static AgentModel model(String id, AtomicInteger revision, int staleRevision) {
        return new AgentModel() {
            int n;

            public Capabilities capabilities() {
                return new Capabilities(true, true, 8192, 512);
            }

            public Response respond(Request request, AgentCancellation c, java.util.function.Consumer<String> text) {
                assertTrue(request.messages().stream()
                        .anyMatch(m -> m.role().equals("user") && m.text().contains("preserve-goal")));
                var pending = new HashSet<String>();
                for (var message : request.messages()) {
                    message.calls().forEach(call -> assertTrue(pending.add(call.id())));
                    if (message.role().equals("tool"))
                        assertTrue(pending.remove(message.callId()), "orphan observation after compaction");
                }
                assertTrue(pending.isEmpty());
                int i = n++;
                String tool = i < 120
                        ? "observe"
                        : i == 120 || (id.equals("one") ? i == 122 : i == 121) ? "edit" : i == 123 ? "validate" : null;
                if (tool == null) return new Response("done", List.of(), "stop", 0, 0);
                String args = tool.equals("edit")
                        ? "{\"revision\":" + (i == 120 ? staleRevision : revision.get()) + "}"
                        : "{}";
                return new Response("", List.of(new Call(id + "-" + i, tool, args)), "tool_calls", 0, 0);
            }
        };
    }
}
