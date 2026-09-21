package com.editora.agent.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Single-writer session runtime. All model/tool work is off FX; UI consumes immutable events. */
public final class AgentRuntime implements AutoCloseable {
    public enum State {
        IDLE,
        REASONING,
        TOOL,
        PERMISSION,
        VERIFYING,
        COMPLETED,
        NEEDS_INPUT,
        LIMIT,
        CANCELLED,
        FAILED
    }

    public record Event(State state, String tool, String detail, boolean error, long elapsedMillis) {}

    public record Outcome(State state, String detail) {}

    public record Verification(boolean passed, String detail) {}

    public record Limits(int iterations, int calls, int contextTokens, int resultChars, Duration operationTimeout) {
        public static final Limits DEFAULT = new Limits(64, 256, 32_768, 8_000, Duration.ofMinutes(5));

        public Limits {
            if (iterations < 1
                    || calls < 1
                    || contextTokens < 1024
                    || resultChars < 256
                    || operationTimeout.isZero()
                    || operationTimeout.isNegative()) {
                throw new IllegalArgumentException("Invalid agent limits");
            }
        }
    }

    @FunctionalInterface
    public interface Approval {
        boolean approve(AgentTool.Spec tool, String arguments, AgentCancellation cancellation) throws Exception;
    }

    @FunctionalInterface
    public interface Verifier {
        Verification verify(AgentCancellation cancellation) throws Exception;
    }

    private static final Logger LOG = Logger.getLogger(AgentRuntime.class.getName());
    private final AgentModel model;
    private final AgentTools tools;
    private final AgentPolicy policy;
    private final Approval approval;
    private final Verifier verifier;
    private final Consumer<Event> events;
    private final Consumer<String> text;
    private final Limits limits;
    private final String system;
    private final AgentContext context = new AgentContext();
    private final HashSet<String> seenCallIds = new HashSet<>();
    private final ExecutorService operations = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService session = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("editora-agent-session").factory());
    private final ObjectMapper json = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private AgentCancellation active;
    private boolean closed;
    private boolean needsVerification;
    private Consumer<AgentContext.Saved> checkpoint = saved -> {};

    public synchronized void setCheckpoint(Consumer<AgentContext.Saved> checkpoint) {
        if (active != null) throw new IllegalStateException("Session busy");
        this.checkpoint = checkpoint;
    }

    public synchronized void restore(AgentContext.Saved saved) {
        if (active != null) throw new IllegalStateException("Session busy");
        context.restore(saved);
        needsVerification = saved.needsVerification();
        saved.exchanges().stream()
                .flatMap(List::stream)
                .flatMap(m -> m.calls().stream())
                .forEach(c -> seenCallIds.add(c.id()));
    }

    public AgentRuntime(
            AgentModel model,
            AgentTools tools,
            AgentPolicy policy,
            Approval approval,
            Verifier verifier,
            String system,
            Limits limits,
            Consumer<Event> events,
            Consumer<String> text) {
        this.model = model;
        this.tools = tools;
        this.policy = policy;
        this.approval = approval;
        this.verifier = verifier;
        this.system = system;
        this.limits = limits;
        this.events = events;
        this.text = text;
    }

    public synchronized CompletableFuture<Outcome> submit(String goal) {
        if (closed || active != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Agent session is closed or busy"));
        }
        AgentCancellation cancellation = new AgentCancellation();
        active = cancellation;
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        session.execute(() -> {
            Outcome outcome;
            try {
                outcome = run(goal, cancellation);
            } catch (CancellationException e) {
                outcome = new Outcome(State.CANCELLED, "Cancelled; any applied edits remain available for review");
            } catch (Exception e) {
                outcome = new Outcome(State.FAILED, safeError(e));
            }
            try {
                var saved = context.save();
                checkpoint.accept(new AgentContext.Saved(
                        saved.exchanges(), saved.memory(), saved.compacted(), needsVerification));
            } catch (RuntimeException failure) {
                emit(State.FAILED, "", "Session checkpoint failed; current turn result remains available", true, 0);
            }
            synchronized (this) {
                active = null;
            }
            emit(outcome.state(), "", outcome.detail(), outcome.state() == State.FAILED, 0);
            done.complete(outcome);
        });
        return done;
    }

    private Outcome run(String goal, AgentCancellation cancellation) throws Exception {
        if (!model.capabilities().tools()) {
            return new Outcome(State.NEEDS_INPUT, "This model adapter does not support tool calls");
        }
        if (AgentContext.cost(goal) > limits.contextTokens()) {
            return new Outcome(State.LIMIT, "User input exceeds context budget");
        }
        context.add(List.of(AgentModel.Message.text("user", goal)));
        boolean changedThisTurn = needsVerification;
        int calls = 0;
        int failedVerification = 0;
        int consecutiveDenials = 0;
        String previousRead = null;
        int unchangedReads = 0;
        for (int iteration = 0; iteration < limits.iterations(); iteration++) {
            cancellation.check();
            emit(State.REASONING, "", "", false, 0);
            AgentModel.Request request;
            try {
                int budget =
                        Math.min(limits.contextTokens(), model.capabilities().contextTokens())
                                - model.capabilities().outputTokens();
                request = context.request(system, tools.specs(), budget, model.tokenCounter());
            } catch (IllegalStateException exhausted) {
                return new Outcome(State.LIMIT, exhausted.getMessage());
            }
            AgentModel.Response response = bounded(
                    cancellation,
                    limits.operationTimeout(),
                    child -> model.respond(request, child, delta -> {
                        if (!cancellation.isCancelled()) {
                            try {
                                text.accept(delta);
                            } catch (RuntimeException ignored) {
                                // A broken transcript view must not corrupt the model/tool protocol state.
                            }
                        }
                    }));
            cancellation.check();
            LOG.fine("agent.model iteration=" + iteration + " inputTokens=" + response.inputTokens() + " outputTokens="
                    + response.outputTokens() + " calls=" + response.calls().size());
            if (response.calls().size() > limits.calls() - calls) {
                return new Outcome(State.LIMIT, "Tool-call limit reached; no calls from this response were executed");
            }
            if (AgentContext.cost(response.text()) > limits.contextTokens() * 4L) {
                return new Outcome(State.LIMIT, "Model response exceeds resource limit");
            }
            var ids = new HashSet<String>();
            for (var call : response.calls()) {
                if (call.id() == null
                        || call.id().isBlank()
                        || !ids.add(call.id())
                        || seenCallIds.contains(call.id())) {
                    return new Outcome(State.FAILED, "Model returned a missing or reused tool-call id");
                }
            }
            var exchange = new ArrayList<AgentModel.Message>();
            exchange.add(new AgentModel.Message("assistant", response.text(), response.calls(), null, false));
            if (response.calls().isEmpty()) {
                context.add(exchange);
                if (!"stop".equals(response.stopReason()) && !"end_turn".equals(response.stopReason())) {
                    return new Outcome(
                            State.NEEDS_INPUT,
                            "length".equals(response.stopReason()) || "max_tokens".equals(response.stopReason())
                                    ? "Model output limit reached. Retry with a smaller step or choose a model profile that fits the output budget. Completion has not been verified."
                                    : "Model stopped: " + response.stopReason());
                }
                if (needsVerification || changedThisTurn) {
                    emit(State.VERIFYING, "", "", false, 0);
                    Verification result;
                    try {
                        result = bounded(cancellation, limits.operationTimeout(), verifier::verify);
                    } catch (CancellationException cancelled) {
                        throw cancelled;
                    } catch (Exception failure) {
                        result = new Verification(false, "Verification unavailable: " + safeError(failure));
                    }
                    cancellation.check();
                    if (result.passed() && !needsVerification) {
                        return new Outcome(State.COMPLETED, response.text());
                    }
                    needsVerification = !result.passed();
                    context.add(List.of(AgentModel.Message.text(
                            "observation",
                            "Runtime verification observation (data):\n"
                                    + AgentContext.bounded(result.detail(), limits.resultChars())
                                    + "\nUse these results to continue or explain the verified outcome.")));
                    emit(State.VERIFYING, "", result.detail(), !result.passed(), 0);
                    if (!result.passed() && ++failedVerification >= 2) {
                        return new Outcome(State.NEEDS_INPUT, result.detail());
                    }
                    continue; // Verification itself is an observation; model sees it before its final answer.
                }
                return new Outcome(State.COMPLETED, response.text());
            }
            boolean uncertainMutation = false;
            for (var call : response.calls()) {
                calls++;
                if (cancellation.isCancelled()) {
                    exchange.add(AgentModel.Message.observation(call.id(), "Cancelled before execution", true));
                    continue;
                }
                AgentTool tool = tools.get(call.name());
                AgentTool.Result result;
                long start = System.nanoTime();
                boolean executionStarted = false;
                boolean mutationAttempted = false;
                try {
                    if (tool == null) {
                        throw new IllegalArgumentException("Unknown tool; use the advertised catalog");
                    }
                    if (call.arguments() == null || call.arguments().length() > 1_000_000) {
                        throw new IllegalArgumentException("Invalid tool arguments size");
                    }
                    var arguments = json.readTree(call.arguments());
                    if (arguments == null) {
                        throw new IllegalArgumentException("Expected tool arguments object");
                    }
                    AgentTools.validate(arguments, tool.spec().inputSchema());
                    if (policy.requiresApproval(tool.spec())) {
                        emit(State.PERMISSION, call.name(), "", false, 0);
                        boolean allowed = bounded(
                                cancellation,
                                limits.operationTimeout(),
                                child -> approval.approve(tool.spec(), arguments.toPrettyString(), child));
                        if (!allowed) {
                            unchangedReads = 0;
                            previousRead = null;
                            exchange.add(AgentModel.Message.observation(
                                    call.id(), "Permission denied; choose another action", true));
                            emit(State.TOOL, call.name(), "Permission denied", true, 0);
                            if (++consecutiveDenials >= 3) {
                                for (int i = exchange.size() - 1;
                                        i < response.calls().size();
                                        i++) {
                                    exchange.add(AgentModel.Message.observation(
                                            response.calls().get(i).id(),
                                            "Not executed: repeated permission denials require user input",
                                            true));
                                }
                                context.add(exchange);
                                seenCallIds.addAll(ids);
                                return new Outcome(
                                        State.NEEDS_INPUT,
                                        "Permission denied three consecutive times. Use an allowed native tool or clarify the required action before continuing.");
                            }
                            continue;
                        }
                    }
                    cancellation.check();
                    emit(State.TOOL, call.name(), "", false, 0);
                    executionStarted = true;
                    mutationAttempted = tool.spec().effect() == AgentTool.Effect.WORKSPACE_WRITE
                            || tool.spec().effect() == AgentTool.Effect.DESTRUCTIVE;
                    result = bounded(
                            cancellation,
                            tool.spec().timeout(),
                            child -> tool.handler().execute(arguments, child));
                    consecutiveDenials = 0;
                    changedThisTurn |= result.changed();
                    needsVerification |= result.changed();
                } catch (Exception failure) {
                    // A mutating handler may have committed before it failed or observed cancellation.
                    // Its exception cannot prove that the workspace is unchanged.
                    needsVerification |= executionStarted && mutationAttempted;
                    changedThisTurn |= executionStarted && mutationAttempted;
                    result = AgentTool.Result.failure(safeError(failure));
                    if (failure instanceof TimeoutException
                            && executionStarted
                            && tool != null
                            && tool.spec().effect() != AgentTool.Effect.READ) {
                        uncertainMutation = true;
                        needsVerification = true;
                    }
                }
                String observation = AgentContext.bounded(result.text(), limits.resultChars());
                if (tool != null
                        && tool.spec().effect() == AgentTool.Effect.READ
                        && !result.error()
                        && !result.changed()) {
                    String fingerprint =
                            AgentSessionStore.hash((call.name() + "\0" + call.arguments() + "\0" + result.text())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    unchangedReads = fingerprint.equals(previousRead) ? unchangedReads + 1 : 1;
                    previousRead = fingerprint;
                } else {
                    unchangedReads = 0;
                    previousRead = null;
                }
                exchange.add(AgentModel.Message.observation(call.id(), observation, result.error()));
                emit(State.TOOL, call.name(), observation, result.error(), (System.nanoTime() - start) / 1_000_000);
                if (uncertainMutation) {
                    for (int i = exchange.size() - 1; i < response.calls().size(); i++) {
                        exchange.add(AgentModel.Message.observation(
                                response.calls().get(i).id(), "Not executed: preceding mutation timed out", true));
                    }
                    break;
                }
            }
            context.add(exchange);
            seenCallIds.addAll(ids);
            cancellation.check();
            if (unchangedReads >= 6) {
                return new Outcome(
                        State.NEEDS_INPUT,
                        "Repeated unchanged read observations; no progress after six identical calls. Continue with a different range, query or capability, or clarify the missing information.");
            }
            if (unchangedReads == 3) {
                context.add(
                        List.of(
                                AgentModel.Message.text(
                                        "observation",
                                        "Progress observation: three identical read calls returned unchanged data. Use the existing result; follow paging metadata, narrow the query or choose another capability instead of repeating the same call.")));
            }
            if (uncertainMutation) {
                return new Outcome(
                        State.NEEDS_INPUT, "A mutation timed out; inspect workspace state before continuing");
            }
        }
        return new Outcome(State.LIMIT, "Iteration limit reached; the session can continue with another prompt");
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run(AgentCancellation child) throws Exception;
    }

    private <T> T bounded(AgentCancellation parent, Duration timeout, Operation<T> operation) throws Exception {
        parent.check();
        AgentCancellation child = new AgentCancellation();
        var future = operations.submit((Callable<T>) () -> {
            child.check();
            return operation.run(child);
        });
        try (var parentHook = parent.onCancel(child::cancel);
                var interrupt = child.onCancel(() -> future.cancel(true))) {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("Agent operation failed", failure.getCause());
        } finally {
            child.cancel();
            future.cancel(true);
        }
    }

    public synchronized void cancel() {
        if (active != null) {
            active.cancel();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancel();
        session.shutdown();
        operations.shutdownNow();
    }

    private void emit(State state, String tool, String detail, boolean error, long elapsed) {
        // Metadata-only logging: never arguments, source, model content, environment or credentials.
        String safeTool = tool.isEmpty() || tools.get(tool) != null ? tool : "unknown";
        LOG.fine("agent.event state=" + state + " tool=" + safeTool + " error=" + error + " elapsedMs=" + elapsed);
        try {
            events.accept(
                    new Event(state, safeTool, AgentContext.bounded(detail, limits.resultChars()), error, elapsed));
        } catch (RuntimeException ignored) {
            /* A view listener must not break the execution contract. */
        }
    }

    private static String safeError(Exception failure) {
        return failure instanceof CancellationException
                ? "Cancelled"
                : failure instanceof TimeoutException
                        ? "Operation timed out"
                        : AgentContext.bounded(
                                failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage(),
                                1000);
    }
}
