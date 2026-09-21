package com.editora.agent.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentRuntime.Limits LIMITS =
            new AgentRuntime.Limits(10, 30, 16_384, 512, Duration.ofSeconds(2));

    private static AgentModel.Response done(String text) {
        return new AgentModel.Response(text, List.of(), "stop", 10, 20);
    }

    private static AgentModel.Call call(String id, String tool, String args) {
        return new AgentModel.Call(id, tool, args);
    }

    private static AgentModel.Response calls(AgentModel.Call... calls) {
        return new AgentModel.Response("", List.of(calls), "tool_calls", 10, 20);
    }

    private static AgentTool tool(String name, AgentTool.Effect effect, AgentTool.Handler handler) throws Exception {
        return new AgentTool(
                new AgentTool.Spec(
                        name,
                        "test",
                        JSON.readTree("{\"type\":\"object\",\"properties\":{}}"),
                        null,
                        effect,
                        Duration.ofMillis(250),
                        true,
                        "test"),
                handler);
    }

    private static final class FakeModel implements AgentModel {
        final List<Request> requests = new ArrayList<>();
        final List<Response> responses;

        FakeModel(Response... responses) {
            this.responses = List.of(responses);
        }

        public Capabilities capabilities() {
            return new Capabilities(true, true, 16_384, 512);
        }

        public Response respond(Request request, AgentCancellation cancel, Consumer<String> text) {
            requests.add(request);
            return responses.get(requests.size() - 1);
        }
    }

    private AgentRuntime runtime(
            AgentModel model, AgentTools tools, AgentRuntime.Approval approval, AgentRuntime.Verifier verifier) {
        return new AgentRuntime(
                model, tools, new AgentPolicy(), approval, verifier, "system", LIMITS, event -> {}, delta -> {});
    }

    @Test
    void outputExhaustionExplainsIncompleteWorkAndAllowsContinuation() throws Exception {
        for (String reason : List.of("length", "max_tokens")) {
            var model = new FakeModel(
                    new AgentModel.Response("partial explanation", List.of(), reason, 10, 512),
                    done("complete after a smaller step"));
            try (var runtime = runtime(model, new AgentTools(), (s, a, c) -> fail("No tools"), c -> fail("No edits"))) {
                var outcome = runtime.submit("understand").get(3, TimeUnit.SECONDS);
                assertEquals(AgentRuntime.State.NEEDS_INPUT, outcome.state());
                assertTrue(outcome.detail().contains("Model output limit reached"));
                assertTrue(outcome.detail().contains("Completion has not been verified"));
                assertEquals(
                        AgentRuntime.State.COMPLETED,
                        runtime.submit("continue with a smaller step")
                                .get(3, TimeUnit.SECONDS)
                                .state());
            }
        }
    }

    @Test
    void unchangedReadLoopGetsRecoveryObservationThenStopsAndCanResume() throws Exception {
        var script = new ArrayList<AgentModel.Response>();
        for (int i = 0; i < 6; i++) script.add(calls(call("r" + i, "read", "{}")));
        script.add(done("continue with a new query"));
        var model = new FakeModel(script.toArray(AgentModel.Response[]::new));
        var tools = new AgentTools()
                .register(tool(
                        "read", AgentTool.Effect.READ, (a, c) -> AgentTool.Result.ok("same revision and preview")));
        try (var runtime = runtime(model, tools, (s, a, c) -> fail("read"), c -> fail("No writes"))) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT,
                    runtime.submit("explore").get(3, TimeUnit.SECONDS).state());
            assertTrue(model.requests.get(3).messages().stream()
                    .anyMatch(m -> m.text().contains("three identical")));
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("new direction").get(3, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void sameReadWithChangingEvidenceDoesNotTriggerProgressGuard() throws Exception {
        var responses = new ArrayList<AgentModel.Response>();
        for (int i = 0; i < 7; i++) responses.add(calls(call("r" + i, "read", "{}")));
        responses.add(done("complete"));
        var n = new AtomicInteger();
        var tools = new AgentTools()
                .register(tool(
                        "read",
                        AgentTool.Effect.READ,
                        (a, c) -> AgentTool.Result.ok("revision " + n.incrementAndGet())));
        try (var runtime = runtime(
                new FakeModel(responses.toArray(AgentModel.Response[]::new)),
                tools,
                (s, a, c) -> fail("read"),
                c -> fail("no writes"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("observe revisions").get(3, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void repeatedPermissionDenialsStopWithCompleteProtocolAndAllowContinuation() throws Exception {
        var model = new FakeModel(
                calls(call("a", "exec", "{}")),
                calls(call("b", "exec", "{}")),
                calls(call("c", "exec", "{}"), call("d", "exec", "{}")),
                done("use native tools instead"));
        var saved = new java.util.concurrent.atomic.AtomicReference<AgentContext.Saved>();
        var approvals = new AtomicInteger();
        var tools = new AgentTools().register(tool("exec", AgentTool.Effect.EXTERNAL, (a, c) -> fail("Denied")));
        try (var runtime = runtime(
                model,
                tools,
                (s, a, c) -> {
                    approvals.incrementAndGet();
                    return false;
                },
                c -> fail("No writes"))) {
            runtime.setCheckpoint(saved::set);
            var result = runtime.submit("work").get(3, TimeUnit.SECONDS);
            assertEquals(AgentRuntime.State.NEEDS_INPUT, result.state());
            assertTrue(result.detail().contains("Permission denied"));
            assertEquals(3, approvals.get());
            var exchange = saved.get().exchanges().getLast();
            assertEquals(
                    List.of("c", "d"),
                    exchange.stream()
                            .filter(m -> m.role().equals("tool"))
                            .map(AgentModel.Message::callId)
                            .toList());
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("continue with reads")
                            .get(3, TimeUnit.SECONDS)
                            .state());
        }
    }

    @Test
    void restoredUnverifiedMutationCannotSkipTheCompletionGate() throws Exception {
        var model = new FakeModel(done("already done"), done("still done"));
        var saved = new java.util.concurrent.atomic.AtomicReference<AgentContext.Saved>();
        try (var runtime = runtime(
                model,
                new AgentTools(),
                (s, a, c) -> fail("no new permission"),
                c -> new AgentRuntime.Verification(false, "fresh validation required"))) {
            runtime.restore(new AgentContext.Saved(
                    List.of(List.of(AgentModel.Message.text("user", "fix bug"))), List.of(), 0, true));
            runtime.setCheckpoint(saved::set);
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT,
                    runtime.submit("continue").get(3, TimeUnit.SECONDS).state());
        }
        assertTrue(saved.get().needsVerification());
        assertTrue(model.requests.getLast().messages().stream()
                .anyMatch(m -> m.text().contains("fresh validation required")));
    }

    @Test
    void executesMultipleCallsAndSubsequentRounds() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        var model = new FakeModel(
                calls(call("a", "read", "{}"), call("b", "read", "{}")),
                calls(call("c", "read", "{}")),
                done("complete"));
        var tools = new AgentTools()
                .register(tool(
                        "read",
                        AgentTool.Effect.READ,
                        (a, c) -> AgentTool.Result.ok("read " + executed.incrementAndGet())));
        try (var runtime = runtime(model, tools, (s, a, c) -> fail("read should not ask"), c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("goal").get(3, TimeUnit.SECONDS).state());
            assertEquals(3, executed.get());
            var messages = model.requests.get(1).messages();
            assertEquals(
                    List.of("user", "assistant", "tool", "tool"),
                    messages.stream().map(AgentModel.Message::role).toList());
            assertEquals("a", messages.get(2).callId());
            assertEquals("b", messages.get(3).callId());
        }
    }

    @Test
    void malformedUnknownAndThrowingToolsBecomeObservations() throws Exception {
        var model = new FakeModel(
                calls(call("a", "read", "{bad"), call("b", "missing", "{}"), call("c", "read", "{}")),
                done("recovered"));
        var tools = new AgentTools().register(tool("read", AgentTool.Effect.READ, (a, c) -> {
            throw new java.io.IOException("MCP disconnected");
        }));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED, runtime.submit("goal").get().state());
            var observations = model.requests.get(1).messages().stream()
                    .filter(m -> m.role().equals("tool"))
                    .toList();
            assertEquals(3, observations.size());
            assertTrue(observations.stream().allMatch(AgentModel.Message::error));
            assertTrue(observations.get(2).text().contains("MCP disconnected"));
        }
    }

    @Test
    void emptyToolResultStillProducesExactlyOnePairedObservation() throws Exception {
        var model = new FakeModel(calls(call("a", "read", "{}")), done("complete"));
        var tools = new AgentTools().register(tool("read", AgentTool.Effect.READ, (a, c) -> AgentTool.Result.ok("")));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED, runtime.submit("goal").get().state());
            var results = model.requests.get(1).messages().stream()
                    .filter(m -> m.role().equals("tool"))
                    .toList();
            assertEquals(1, results.size());
            assertEquals("a", results.getFirst().callId());
            assertEquals("", results.getFirst().text());
        }
    }

    @Test
    void missingDuplicateAndSessionReusedCallIdsAreRejectedBeforeExecution() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        var tools = new AgentTools().register(tool("read", AgentTool.Effect.READ, (a, c) -> {
            executed.incrementAndGet();
            return AgentTool.Result.ok("ok");
        }));
        try (var missing = runtime(
                new FakeModel(calls(call("", "read", "{}"))), tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(AgentRuntime.State.FAILED, missing.submit("goal").get().state());
        }
        try (var duplicate = runtime(
                new FakeModel(calls(call("same", "read", "{}"), call("same", "read", "{}"))),
                tools,
                (s, a, c) -> true,
                c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.FAILED, duplicate.submit("goal").get().state());
        }
        assertEquals(0, executed.get());
        var reusedModel = new FakeModel(calls(call("same", "read", "{}")), calls(call("same", "read", "{}")));
        try (var reused = runtime(reusedModel, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(AgentRuntime.State.FAILED, reused.submit("goal").get().state());
            assertEquals(1, executed.get());
        }
    }

    @Test
    void readTimeoutIsPairedAndDoesNotSkipSiblingCalls() throws Exception {
        AtomicInteger later = new AtomicInteger();
        var model = new FakeModel(calls(call("slow", "wait", "{}"), call("next", "read", "{}")), done("complete"));
        var tools = new AgentTools()
                .register(tool("wait", AgentTool.Effect.READ, (a, c) -> {
                    new CountDownLatch(1).await();
                    return AgentTool.Result.ok("unexpected");
                }))
                .register(tool("read", AgentTool.Effect.READ, (a, c) -> {
                    later.incrementAndGet();
                    return AgentTool.Result.ok("later");
                }));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("goal").get(2, TimeUnit.SECONDS).state());
            assertEquals(1, later.get());
            var results = model.requests.get(1).messages().stream()
                    .filter(m -> m.role().equals("tool"))
                    .toList();
            assertEquals(
                    List.of("slow", "next"),
                    results.stream().map(AgentModel.Message::callId).toList());
            assertTrue(results.getFirst().error());
        }
    }

    @Test
    void denialDoesNotExecuteOrEndSession() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        var model = new FakeModel(calls(call("a", "write", "{}")), done("need another approach"));
        var tools = new AgentTools().register(tool("write", AgentTool.Effect.WORKSPACE_WRITE, (a, c) -> {
            writes.incrementAndGet();
            return new AgentTool.Result("changed", false, true);
        }));
        try (var runtime = runtime(model, tools, (s, a, c) -> false, c -> fail("denied edit"))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED, runtime.submit("goal").get().state());
            assertEquals(0, writes.get());
            assertTrue(model.requests.get(1).messages().getLast().error());
        }
    }

    @Test
    void editFinalAnswerIsGatedByVerificationAndFailureCanBeRepaired() throws Exception {
        var model = new FakeModel(
                calls(call("a", "write", "{}")),
                done("premature"),
                calls(call("b", "write", "{}")),
                done("ready"),
                done("verified"));
        var tools = new AgentTools()
                .register(tool(
                        "write",
                        AgentTool.Effect.WORKSPACE_WRITE,
                        (a, c) -> new AgentTool.Result("changed", false, true)));
        AtomicInteger checks = new AtomicInteger();
        try (var runtime = runtime(
                model,
                tools,
                (s, a, c) -> true,
                c -> new AgentRuntime.Verification(checks.incrementAndGet() >= 2, "test result"))) {
            var outcome = runtime.submit("fix bug").get();
            assertEquals(AgentRuntime.State.COMPLETED, outcome.state());
            assertEquals("verified", outcome.detail());
            assertEquals(3, checks.get());
            assertTrue(model.requests.get(2).messages().getLast().text().contains("test result"));
        }
    }

    @Test
    void failedVerificationStopsWithoutClaimingCompletion() throws Exception {
        var model = new FakeModel(calls(call("a", "write", "{}")), done("premature"), done("still premature"));
        var tools = new AgentTools()
                .register(tool(
                        "write",
                        AgentTool.Effect.WORKSPACE_WRITE,
                        (a, c) -> new AgentTool.Result("changed", false, true)));
        try (var runtime =
                runtime(model, tools, (s, a, c) -> true, c -> new AgentRuntime.Verification(false, "tests failing"))) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT, runtime.submit("goal").get().state());
        }
    }

    @Test
    void mutatingToolExceptionCannotBypassVerification() throws Exception {
        var model = new FakeModel(calls(call("a", "write", "{}")), done("candidate"), done("verified"));
        var tools = new AgentTools().register(tool("write", AgentTool.Effect.WORKSPACE_WRITE, (a, c) -> {
            throw new java.io.IOException("failed after a possible commit");
        }));
        AtomicInteger checks = new AtomicInteger();
        try (var runtime = runtime(
                model,
                tools,
                (s, a, c) -> true,
                c -> new AgentRuntime.Verification(true, "workspace inspected " + checks.incrementAndGet()))) {
            assertEquals(
                    AgentRuntime.State.COMPLETED, runtime.submit("goal").get().state());
            assertEquals(2, checks.get(), "verification runs before and after the final model round");
        }
    }

    @Test
    void editAfterSuccessfulValidationPreventsFinalCompletion() throws Exception {
        var model =
                new FakeModel(calls(call("a", "write", "{}")), done("candidate"), done("stale"), done("still stale"));
        var tools = new AgentTools()
                .register(tool(
                        "write",
                        AgentTool.Effect.WORKSPACE_WRITE,
                        (a, c) -> new AgentTool.Result("changed", false, true)));
        AtomicInteger checks = new AtomicInteger();
        try (var runtime = runtime(
                model,
                tools,
                (s, a, c) -> true,
                c -> new AgentRuntime.Verification(checks.incrementAndGet() == 1, "revision evidence"))) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT, runtime.submit("goal").get().state());
            assertEquals(3, checks.get());
        }
    }

    @Test
    void cancellationInterruptsVerification() throws Exception {
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var model = new FakeModel(calls(call("a", "write", "{}")), done("candidate"));
        var tools = new AgentTools()
                .register(tool(
                        "write",
                        AgentTool.Effect.WORKSPACE_WRITE,
                        (a, c) -> new AgentTool.Result("changed", false, true)));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> {
            verifying.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                interrupted.countDown();
            }
            return fail("cancelled verification returned");
        })) {
            var future = runtime.submit("goal");
            assertTrue(verifying.await(2, TimeUnit.SECONDS));
            runtime.cancel();
            assertEquals(
                    AgentRuntime.State.CANCELLED,
                    future.get(2, TimeUnit.SECONDS).state());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void iterationExhaustionLeavesEveryExecutedCallPaired() throws Exception {
        AgentModel.Response[] responses = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> calls(call("call-" + i, "read", "{}")))
                .toArray(AgentModel.Response[]::new);
        var model = new FakeModel(responses);
        AtomicInteger executed = new AtomicInteger();
        var tools = new AgentTools().register(tool("read", AgentTool.Effect.READ, (a, c) -> {
            return AgentTool.Result.ok("revision " + executed.incrementAndGet());
        }));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(AgentRuntime.State.LIMIT, runtime.submit("goal").get().state());
            assertEquals(10, executed.get());
            for (int i = 1; i < model.requests.size(); i++) {
                assertEquals(
                        i,
                        model.requests.get(i).messages().stream()
                                .filter(m -> m.role().equals("tool"))
                                .count());
            }
        }
    }

    @Test
    void cancellationInterruptsBlockedToolAndPreservesPairedHistoryForContinuation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var model = new FakeModel(calls(call("a", "wait", "{}"), call("b", "wait", "{}")), done("continued"));
        var tools = new AgentTools().register(tool("wait", AgentTool.Effect.READ, (a, c) -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                interrupted.countDown();
            }
            return AgentTool.Result.ok("unexpected");
        }));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            var result = runtime.submit("goal");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runtime.cancel();
            assertEquals(
                    AgentRuntime.State.CANCELLED,
                    result.get(2, TimeUnit.SECONDS).state());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(
                    AgentRuntime.State.COMPLETED,
                    runtime.submit("continue").get().state());
            assertEquals(
                    2,
                    model.requests.get(1).messages().stream()
                            .filter(m -> m.role().equals("tool"))
                            .count());
        }
    }

    @Test
    void mutationTimeoutStopsBeforeSubsequentActions() throws Exception {
        AtomicInteger later = new AtomicInteger();
        var model = new FakeModel(calls(call("a", "write", "{}"), call("b", "later", "{}")));
        var tools = new AgentTools()
                .register(tool("write", AgentTool.Effect.WORKSPACE_WRITE, (a, c) -> {
                    new CountDownLatch(1).await();
                    return AgentTool.Result.ok("never");
                }))
                .register(tool("later", AgentTool.Effect.READ, (a, c) -> {
                    later.incrementAndGet();
                    return AgentTool.Result.ok("later");
                }));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("not completed"))) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT,
                    runtime.submit("goal").get(2, TimeUnit.SECONDS).state());
            assertEquals(0, later.get());
        }
    }

    @Test
    void capsHugeToolOutput() throws Exception {
        var model = new FakeModel(calls(call("a", "read", "{}")), done("done"));
        var tools = new AgentTools()
                .register(tool("read", AgentTool.Effect.READ, (a, c) -> AgentTool.Result.ok("x".repeat(100_000))));
        try (var runtime = runtime(model, tools, (s, a, c) -> true, c -> fail("no edits"))) {
            runtime.submit("goal").get();
            String observation = model.requests.get(1).messages().getLast().text();
            assertEquals(512, observation.length());
            assertTrue(observation.contains("truncated"));
        }
    }

    @Test
    void contextOverflowStopsBeforeRequest() throws Exception {
        var model = new FakeModel(done("never"));
        try (var runtime = runtime(model, new AgentTools(), (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.LIMIT,
                    runtime.submit("x".repeat(20_000)).get().state());
            assertTrue(model.requests.isEmpty());
        }
    }

    @Test
    void unsupportedCapabilitiesDoNotSendRequest() throws Exception {
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(false, true, 4096, 512);
            }

            public Response respond(Request r, AgentCancellation c, Consumer<String> t) {
                return fail("unsupported");
            }
        };
        try (var runtime = runtime(model, new AgentTools(), (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(
                    AgentRuntime.State.NEEDS_INPUT, runtime.submit("goal").get().state());
        }
    }

    @Test
    void modelInterruptionIsFailureWithoutTools() throws Exception {
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 4096, 512);
            }

            public Response respond(Request r, AgentCancellation c, Consumer<String> t) throws Exception {
                throw new java.io.IOException("partial stream");
            }
        };
        try (var runtime = runtime(model, new AgentTools(), (s, a, c) -> true, c -> fail("no edits"))) {
            assertEquals(AgentRuntime.State.FAILED, runtime.submit("goal").get().state());
        }
    }

    @Test
    void transcriptListenerFailureDoesNotBreakProtocolState() throws Exception {
        AgentModel model = new AgentModel() {
            public Capabilities capabilities() {
                return new Capabilities(true, true, 4096, 512);
            }

            public Response respond(Request request, AgentCancellation cancellation, Consumer<String> text) {
                text.accept("streamed");
                return done("complete");
            }
        };
        try (var runtime = new AgentRuntime(
                model,
                new AgentTools(),
                new AgentPolicy(),
                (s, a, c) -> true,
                c -> fail("no edits"),
                "system",
                LIMITS,
                event -> {},
                delta -> {
                    throw new IllegalStateException("view disposed");
                })) {
            assertEquals(
                    AgentRuntime.State.COMPLETED, runtime.submit("goal").get().state());
        }
    }

    @Test
    void refusesOverlappingTurnsAndCancelsPendingPermission() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        var model = new FakeModel(calls(call("a", "write", "{}")));
        var tools = new AgentTools()
                .register(tool("write", AgentTool.Effect.WORKSPACE_WRITE, (a, c) -> fail("no permission")));
        try (var runtime = runtime(
                model,
                tools,
                (s, a, c) -> {
                    waiting.countDown();
                    new CountDownLatch(1).await();
                    return true;
                },
                c -> fail("no edits"))) {
            var result = runtime.submit("goal");
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> runtime.submit("overlap").get());
            runtime.cancel();
            assertEquals(
                    AgentRuntime.State.CANCELLED,
                    result.get(2, TimeUnit.SECONDS).state());
        }
    }
}
