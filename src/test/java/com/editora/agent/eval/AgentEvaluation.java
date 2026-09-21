package com.editora.agent.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Opt-in evaluation instrumentation. Reports contain metadata, never prompts, arguments or tool payloads. */
public final class AgentEvaluation {
    private final ObjectMapper json = new ObjectMapper();
    private final Path workspace;
    private final long started = System.nanoTime();
    private final List<ObjectNode> calls = new ArrayList<>();
    private final List<ObjectNode> rounds = new ArrayList<>();
    private final List<ObjectNode> checks = new ArrayList<>();
    private final List<ObjectNode> observations = new ArrayList<>();
    private final Set<String> readFiles = new TreeSet<>();
    private final Set<String> signatures = new HashSet<>();
    private int approvals, denied, repeated, verificationFailures;
    private final List<ObjectNode> permissions = new ArrayList<>();

    public AgentEvaluation(Path workspace) {
        this.workspace = workspace;
    }

    public AgentModel model(AgentModel delegate) {
        return new AgentModel() {
            public Capabilities capabilities() {
                return delegate.capabilities();
            }

            public AgentTokens.Counter tokenCounter() {
                return delegate.tokenCounter();
            }

            public Response respond(Request request, AgentCancellation c, Consumer<String> text) throws Exception {
                long start = System.nanoTime();
                var first = new AtomicLong(-1);
                var round = json.createObjectNode()
                        .put("iteration", rounds.size() + 1)
                        .put(
                                "requestBytes",
                                request.messages().stream()
                                        .mapToLong(m -> AgentContext.cost(m.text())
                                                + m.calls().stream()
                                                        .mapToLong(call -> AgentContext.cost(call.arguments()))
                                                        .sum())
                                        .sum())
                        .put(
                                "catalogBytes",
                                request.tools().stream()
                                        .mapToLong(s -> AgentContext.cost(s.description() + s.inputSchema()))
                                        .sum());
                try {
                    var result = delegate.respond(request, c, delta -> {
                        first.compareAndSet(-1, elapsed(start));
                        text.accept(delta);
                    });
                    round.put("inputTokens", result.inputTokens())
                            .put("assistantTextChars", result.text().length())
                            .put("outputTokens", result.outputTokens())
                            .put("requestedCalls", result.calls().size())
                            .put("stop", result.stopReason());
                    var selected = round.putArray("toolsRequested");
                    for (var call : result.calls()) {
                        var spec = request.tools().stream()
                                .filter(s -> s.name().equals(call.name()))
                                .findFirst();
                        selected.add(spec.isPresent() ? call.name() : "UNKNOWN_TOOL");
                    }
                    return result;
                } catch (Exception failure) {
                    round.put("failure", classify(failure.getMessage()));
                    throw failure;
                } finally {
                    round.put("elapsedMs", elapsed(start));
                    if (first.get() >= 0) round.put("firstTextDeltaMs", first.get());
                    rounds.add(round);
                    System.out.println("AGENT_EVAL_ROUND iteration="
                            + round.path("iteration").asInt() + " elapsedMs="
                            + round.path("elapsedMs").asLong() + " calls="
                            + round.path("requestedCalls").asInt() + " failure="
                            + round.path("failure").asText());
                    System.out.println("AGENT_EVAL_SELECTION " + round.path("toolsRequested"));
                }
            }
        };
    }

    /** Includes rejected schema/unknown-tool calls, which never enter a handler. No payload is retained. */
    public void event(AgentRuntime.Event event) {
        if (event.state() != AgentRuntime.State.TOOL || event.detail().isEmpty()) return;
        var observation = json.createObjectNode()
                .put("tool", event.tool())
                .put("error", event.error())
                .put("elapsedMs", event.elapsedMillis());
        if (event.error()) observation.put("failure", classify(event.detail()));
        observations.add(observation);
        System.out.println("AGENT_EVAL_OBSERVATION " + observation);
    }

    public AgentTools tools(AgentTools original) {
        var measured = new AgentTools();
        for (var spec : original.specs()) {
            var tool = original.get(spec.name());
            measured.register(new AgentTool(spec, (a, c) -> {
                long start = System.nanoTime();
                var entry = json.createObjectNode()
                        .put("tool", spec.name())
                        .put("iteration", rounds.size())
                        .put("effect", spec.effect().name());
                String path = relative(a.path("path").asText());
                if (path != null) entry.put("path", path);
                if (spec.name().equals("search_text")) {
                    String query = a.path("query").asText();
                    entry.put("queryChars", query.length())
                            .put(
                                    "containsPatternSyntax",
                                    query.contains("|")
                                            || query.contains(".*")
                                            || query.contains("\\b")
                                            || query.contains("(?"));
                }
                String operation = a.path("operation").asText();
                if (Set.of(
                                "symbols",
                                "workspace_symbols",
                                "definition",
                                "declaration",
                                "implementation",
                                "type_definition",
                                "references",
                                "hover",
                                "signature",
                                "highlights",
                                "call_hierarchy",
                                "type_hierarchy",
                                "code_actions",
                                "rename",
                                "format",
                                "code_action")
                        .contains(operation)) entry.put("operation", operation);
                String signature = spec.name()
                        + AgentSessionStore.hash(a.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!signatures.add(signature)) repeated++;
                try {
                    var result = tool.handler().execute(a, c);
                    entry.put("error", result.error())
                            .put("changed", result.changed())
                            .put("resultChars", result.text().length());
                    if (result.error()) entry.put("failure", classify(result.text()));
                    if (spec.name().equals("read_file") && !result.error() && path != null) readFiles.add(path);
                    if (spec.name().equals("run_command"))
                        entry.put("purpose", a.path("purpose").asText());
                    return result;
                } catch (Exception failure) {
                    entry.put("error", true).put("failure", classify(failure.getMessage()));
                    throw failure;
                } finally {
                    entry.put("elapsedMs", elapsed(start));
                    calls.add(entry);
                    System.out.println("AGENT_EVAL_TOOL " + entry);
                }
            }));
        }
        return measured;
    }

    public boolean approval(AgentTool.Spec spec, String args, AgentCancellation c, boolean writesAllowed)
            throws Exception {
        approvals++;
        boolean allowed = writesAllowed && spec.effect() == AgentTool.Effect.WORKSPACE_WRITE;
        if (writesAllowed && spec.name().equals("run_command")) {
            var a = json.readTree(args);
            var argv = a.path("argv");
            // Harness consent covers the repository's test lifecycle only, never arbitrary model commands.
            allowed = argv.isArray()
                    && argv.size() > 1
                    && argv.get(0).asText().equals("mvn")
                    && workspace.resolve(a.path("cwd").asText(".")).normalize().equals(workspace.normalize());
            boolean test = false;
            for (int i = 1; i < argv.size(); i++) {
                String v = argv.get(i).asText();
                test |= v.equals("test");
                allowed &= Set.of(
                                "test",
                                "-q",
                                "-B",
                                "-o",
                                "-Dtest=AiEndpointsTest",
                                "-Dtest=LedgerTest",
                                "-DskipTests=false",
                                "-Dmaven.test.skip=false")
                        .contains(v);
            }
            allowed &= test;
        }
        if (!allowed) denied++;
        var permission = json.createObjectNode().put("tool", spec.name()).put("allowed", allowed);
        if (spec.name().equals("run_command")) {
            String executable = json.readTree(args).path("argv").path(0).asText();
            permission.put(
                    "commandFamily",
                    Set.of("mvn", "ls", "find", "rg", "grep", "cat", "sh", "bash", "git")
                                    .contains(executable)
                            ? executable
                            : "OTHER");
        }
        permissions.add(permission);
        c.check();
        return allowed;
    }

    public AgentRuntime.Verifier verifier(AgentRuntime.Verifier delegate) {
        return c -> {
            long start = System.nanoTime();
            var result = delegate.verify(c);
            checks.add(json.createObjectNode().put("passed", result.passed()).put("elapsedMs", elapsed(start)));
            if (!result.passed()) verificationFailures++;
            return result;
        };
    }

    public ObjectNode report(
            String scenario,
            String provider,
            String model,
            int context,
            AgentRuntime.Outcome outcome,
            Set<String> changedFiles,
            Set<String> allowedFiles,
            Set<String> requiredFiles,
            boolean oraclePassed,
            boolean humanReview,
            int callBudget) {
        var out = json.createObjectNode()
                .put("schemaVersion", 1)
                .put("kind", "AUTONOMOUS_CODING_EVALUATION")
                .put("scenario", scenario)
                .put("provider", provider)
                .put("model", model)
                .put("contextLimit", context)
                .put("tokenEstimate", "HEURISTIC")
                .put("elapsedMs", elapsed(started))
                .put("completionState", outcome.state().name())
                .put("oraclePassed", oraclePassed)
                .put("humanReviewRequired", humanReview)
                .put("approvalsRequested", approvals)
                .put("approvalsDenied", denied)
                .put("approvalMode", "HARNESS_BOUNDED_AUTO_CONSENT")
                .put("repeatedCalls", repeated)
                .put("verificationFailures", verificationFailures);
        var readBatches = new HashMap<Integer, List<Long>>();
        calls.stream()
                .filter(n -> n.path("effect").asText().equals("READ"))
                .filter(n -> Set.of("read_file", "search_text", "find_files", "semantic_query")
                        .contains(n.path("tool").asText()))
                .forEach(n -> readBatches
                        .computeIfAbsent(n.path("iteration").asInt(), ignored -> new ArrayList<>())
                        .add(n.path("elapsedMs").asLong()));
        out.put(
                "idealParallelReadSavingsMs",
                readBatches.values().stream()
                        .mapToLong(batch ->
                                batch.stream().mapToLong(Long::longValue).sum()
                                        - batch.stream()
                                                .mapToLong(Long::longValue)
                                                .max()
                                                .orElse(0))
                        .sum());
        var unrelated = new TreeSet<>(changedFiles);
        unrelated.removeAll(allowedFiles);
        boolean success = outcome.state() == AgentRuntime.State.COMPLETED
                && oraclePassed
                && unrelated.isEmpty()
                && changedFiles.containsAll(requiredFiles);
        out.put("taskSuccess", success ? humanReview ? "REVIEW_REQUIRED" : "PASS" : "FAIL");
        out.set("changedFiles", json.valueToTree(changedFiles));
        out.set("unexpectedFiles", json.valueToTree(unrelated));
        out.set("filesRead", json.valueToTree(readFiles));
        out.set("modelRounds", json.valueToTree(rounds));
        out.set("toolCalls", json.valueToTree(calls));
        out.set("verification", json.valueToTree(checks));
        out.set("observations", json.valueToTree(observations));
        out.set("permissions", json.valueToTree(permissions));
        out.put(
                "requestedCalls",
                rounds.stream().mapToInt(r -> r.path("requestedCalls").asInt()).sum());
        out.put("iterations", rounds.size())
                .put("calls", calls.size())
                .put("withinCallBudget", calls.size() <= callBudget);
        out.put(
                "inputTokens",
                rounds.stream().mapToLong(r -> r.path("inputTokens").asLong()).sum());
        out.put(
                "outputTokens",
                rounds.stream().mapToLong(r -> r.path("outputTokens").asLong()).sum());
        out.put(
                "usageAvailable",
                rounds.stream()
                        .anyMatch(r -> r.path("inputTokens").asLong() > 0
                                || r.path("outputTokens").asLong() > 0));
        out.put(
                "semanticQueries",
                calls.stream()
                        .filter(e -> e.path("tool").asText().startsWith("semantic_"))
                        .count());
        out.put(
                "searches",
                calls.stream()
                        .filter(e -> e.path("tool").asText().equals("search_text"))
                        .count());
        var failures = out.putArray("failureCategories");
        var categories = new TreeSet<String>();
        calls.stream()
                .filter(n -> n.has("failure"))
                .forEach(n -> categories.add(n.path("failure").asText()));
        rounds.stream()
                .filter(n -> n.has("failure"))
                .forEach(n -> categories.add(n.path("failure").asText()));
        observations.stream()
                .filter(n -> n.has("failure"))
                .forEach(n -> categories.add(n.path("failure").asText()));
        if (!oraclePassed) categories.add("INCORRECT_RESULT");
        if (!unrelated.isEmpty()) categories.add("UNRELATED_EDITS");
        if (repeated > 2) categories.add("REPEATED_DISCOVERY");
        if (denied > 0) categories.add("PERMISSION_FRICTION");
        if (outcome.state() != AgentRuntime.State.COMPLETED) categories.add(classify(outcome.detail()));
        categories.forEach(failures::add);
        return out;
    }

    public static String classify(String detail) {
        String t = Objects.toString(detail, "").toLowerCase(Locale.ROOT);
        if (t.contains("model output limit") || t.contains("model stopped: length") || t.contains("max_tokens"))
            return "MODEL_OUTPUT_LIMIT";
        if (t.contains("response exceeds limit")
                || t.contains("response content exceeds")
                || t.contains("envelope exceeds")) return "PROVIDER_RESPONSE_SIZE";
        if (t.contains("unknown tool")) return "UNKNOWN_TOOL";
        if (t.contains("permission denied")) return "PERMISSION_DENIED";
        if (t.contains("integer outside limits")) return "ARGUMENT_RANGE";
        if (t.contains("schema rejects")) return "UNEXPECTED_ARGUMENT";
        if (t.contains("missing argument")) return "MISSING_ARGUMENT";
        if (t.contains("ambiguous workspaceedit")) return "SEMANTIC_EDIT_FORMAT";
        if (t.contains("annotated edits")) return "SEMANTIC_EDIT_CONSENT";
        if (t.contains("read and synchronize") || t.contains("read affected file")) return "SEMANTIC_PREIMAGE_MISSING";
        if (t.contains("outside line") || t.contains("line outside document") || t.contains("semantic range"))
            return "SEMANTIC_POSITION";
        if (t.contains("resource operations") || t.contains("snippet edits")) return "SEMANTIC_EDIT_UNSUPPORTED";
        if (t.contains("context") && (t.contains("budget") || t.contains("limit"))) return "CONTEXT_EXHAUSTED";
        if (t.contains("iteration") || t.contains("tool-call limit")) return "ITERATION_EXHAUSTED";
        if (t.contains("stale") || t.contains("changed during")) return "STALE_CONTEXT";
        if (t.contains("unsupported") || t.contains("lsp unavailable") || t.contains("synchroniz"))
            return "SEMANTICS_UNAVAILABLE";
        if (t.contains("validation") || t.contains("test") || t.contains("command exit")) return "VALIDATION_FAILURE";
        if (t.contains("argument") || t.contains("schema") || t.contains("expected")) return "INVALID_ARGUMENTS";
        if (t.contains("timeout") || t.contains("timed out")) return "TIMEOUT";
        if (t.contains("http") || t.contains("stream") || t.contains("provider")) return "PROVIDER_FAILURE";
        return "OTHER_FAILURE";
    }

    private String relative(String value) {
        if (value.isBlank()) return null;
        try {
            var path = workspace.resolve(value).normalize();
            return path.startsWith(workspace) ? workspace.relativize(path).toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
}
