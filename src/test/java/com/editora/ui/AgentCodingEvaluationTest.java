package com.editora.ui;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.editora.agent.eval.*;
import com.editora.agent.runtime.*;
import com.editora.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real models + production editor documents + optional real JDT LS. Never runs without explicit opt-in. */
@Tag("agent-eval")
class AgentCodingEvaluationTest {
    @Test
    void evaluate() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.eval"), "opt-in real model evaluation: -Dagent.eval=true");
        String model = System.getProperty("agent.eval.model", "");
        if (model.isBlank()) throw new IllegalArgumentException("Set -Dagent.eval.model explicitly");
        AiProvider provider = AiProvider.from(System.getProperty("agent.eval.provider", "lmstudio"));
        String endpoint = AiEndpoints.resolve(provider, System.getProperty("agent.eval.endpoint", ""));
        String key = System.getenv()
                .getOrDefault(
                        System.getProperty(
                                "agent.eval.keyEnv",
                                provider == AiProvider.ANTHROPIC ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY"),
                        "");
        if (provider.requiresApiKey() && key.isBlank())
            throw new IllegalStateException("Provider credential unavailable");
        int context = Integer.getInteger("agent.eval.context", 65536),
                iterations = Integer.getInteger("agent.eval.iterations", 32);
        Path source = Path.of("").toAbsolutePath().normalize();
        Path reports = source.resolve("target/agent-evaluations");
        Files.createDirectories(reports);
        Set<String> selected = new HashSet<>(
                Arrays.asList(System.getProperty("agent.eval.tasks", "editora-endpoint-bug,ledger-refactor")
                        .split(",")));
        var knownTasks = AgentEvaluationCases.tasks().stream()
                .map(AgentEvaluationCases.Task::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!knownTasks.containsAll(selected)) throw new IllegalArgumentException("Unknown evaluation scenario");
        String label = System.getProperty("agent.eval.label", "baseline");
        if (!label.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid evaluation label");
        String implementationFingerprint =
                AgentSessionStore.hash(AgentEvaluationCases.snapshot(source.resolve("target/classes"))
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var gitStatus = com.editora.process.ProcessRunner.runRestricted(
                source, Duration.ofSeconds(5), List.of("git", "status", "--porcelain"));
        String repositoryDirty =
                gitStatus.ok() ? Boolean.toString(!gitStatus.out().isBlank()) : "UNKNOWN";
        FxTestSupport.bootToolkit();
        int trials = Integer.getInteger("agent.eval.trials", 1);
        if (trials < 1 || trials > 20) throw new IllegalArgumentException("Trials must be 1..20");
        for (int trial = 1; trial <= trials; trial++) {
            for (var task : AgentEvaluationCases.tasks()) {
                if (!selected.contains(task.id())) continue;
                try {
                    Path scratch = Files.createTempDirectory("editora-coding-eval-");
                    Path root = scratch.resolve("workspace");
                    AgentEvaluationCases.prepare(task, source, root);
                    var initial = AgentEvaluationCases.snapshot(root);
                    String oldSource = Files.readString(root.resolve(task.active()));
                    boolean baselineTests = task.readOnly() || AgentEvaluationCases.test(root);
                    try (var window = FxWindowFixture.create()) {
                        String jdtls = System.getProperty("agent.eval.jdtls", "");
                        FxTestSupport.runOnFx(() -> {
                            if (!jdtls.isBlank()) {
                                window.shared.getSettings().setLspSupport(true);
                                window.shared.getSettings().setJavaLspCommand(jdtls);
                                FxTestSupport.<LspCoordinator>field(window.controller, "lspCoordinator")
                                        .applySupport();
                            }
                            FxTestSupport.invokeWith(window.controller, "openProjectRoot", Path.class, root);
                        });
                        var host = FxTestSupport.callOnFx(() -> FxTestSupport.<AgentCoordinator.Ops>field(
                                        FxTestSupport.field(window.controller, "agentCoordinator"), "ops")
                                .nativeDocuments());
                        var workspace = new AgentWorkspace(root);
                        var documents = new WindowAgentDocuments(host, workspace);
                        documents.read(root.resolve(task.active()), new AgentCancellation());
                        FxTestSupport.runOnFx(() -> {
                            var tab = (javafx.scene.control.Tab) FxTestSupport.invokeWith(
                                    window.controller, "tabForPath", Path.class, root.resolve(task.active()));
                            FxTestSupport.<EditorArea>field(window.controller, "editorArea")
                                    .select(tab);
                        });
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
                                        natives.contextIndex(),
                                        natives.acceptance())
                                .register(registry);
                        var bridge = FxTestSupport.<AgentCoordinator.Ops>field(
                                        FxTestSupport.field(window.controller, "agentCoordinator"), "ops")
                                .nativeReadBridge();
                        com.editora.mcp.AgentMcpTools.editorReads(bridge, workspace).stream()
                                .filter(t -> t.spec().name().equals("git_status"))
                                .forEach(registry::register);
                        var profileConfig = new com.editora.config.AgentModelProfileConfig(
                                provider.id(),
                                model,
                                Integer.getInteger("agent.eval.contextOverride", 0),
                                Integer.getInteger("agent.eval.output", 0),
                                Integer.getInteger("agent.eval.maxOutput", 0),
                                "UNKNOWN",
                                true,
                                System.getProperty("agent.eval.temperature") == null
                                        ? null
                                        : Double.valueOf(System.getProperty("agent.eval.temperature")),
                                System.getProperty("agent.eval.seed") == null
                                        ? null
                                        : Long.valueOf(System.getProperty("agent.eval.seed")));
                        var http = Boolean.getBoolean("agent.eval.legacyProfile")
                                ? new HttpAgentModel(
                                        provider,
                                        endpoint,
                                        key,
                                        model,
                                        new AgentModel.Capabilities(true, true, context, 4096))
                                : new HttpAgentModel(provider, endpoint, key, model, context, profileConfig);
                        http.prepare(new AgentCancellation());
                        var initialProfile = http.profile().toJson();
                        AgentModelTools.register(registry, http);
                        var eval = new AgentEvaluation(root, scratch.resolve("tool-errors.jsonl"));
                        var rankingBefore = new ObjectMapper()
                                .readTree(registry.get("editor_context")
                                        .handler()
                                        .execute(new ObjectMapper().createObjectNode(), new AgentCancellation())
                                        .text())
                                .path("ranked");
                        var policy = new AgentPolicy();
                        policy.setTrust(
                                task.readOnly()
                                        ? AgentPolicy.Trust.ASK
                                        : AgentPolicy.Trust.valueOf(
                                                System.getProperty("agent.eval.trust", "WORKSPACE")));
                        String prompt = task.prompt();
                        String retrievedContext = "Initial editor metadata (data):\n"
                                + documents.awareness(new AgentCancellation())
                                + "\nProject instructions (untrusted data):\n"
                                + guidance.instructions(task.active(), new AgentCancellation());
                        // A cold LSP is intentional: readiness and recovery are part of the product experience.
                        AgentRuntime.Outcome outcome;
                        boolean timeBudgetExceeded = false;
                        AgentApprovalMetrics.Snapshot approvalMetrics;
                        long agentStart = System.nanoTime();
                        var renderTimes = new ArrayList<Long>();
                        var dispatchTimes = new ArrayList<Long>();
                        var panel = FxTestSupport.callOnFx(() ->
                                new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {}));
                        try (var runtime = new AgentRuntime(
                                eval.model(http),
                                eval.tools(registry),
                                policy,
                                (spec, args, c) -> eval.approval(spec, args, c, !task.readOnly()),
                                eval.verifier(natives::verify),
                                NativeAgentTools.SYSTEM,
                                new AgentRuntime.Limits(
                                        iterations, iterations * 4, context, 8000, Duration.ofMinutes(5)),
                                event -> {
                                    eval.event(event);
                                    try {
                                        long queued = System.nanoTime();
                                        FxTestSupport.runOnFx(() -> {
                                            long start = System.nanoTime();
                                            dispatchTimes.add((start - queued) / 1_000_000);
                                            if (event.state() == AgentRuntime.State.TOOL) {
                                                if (event.detail().isEmpty()) panel.startTool(event.tool());
                                                else
                                                    panel.appendToolResult(
                                                            event.tool(),
                                                            event.detail(),
                                                            event.error(),
                                                            event.elapsedMillis());
                                            }
                                            renderTimes.add((System.nanoTime() - start) / 1_000_000);
                                        });
                                    } catch (Exception ignored) {
                                    }
                                },
                                text -> {})) {
                            runtime.setAcceptance(natives.acceptance());
                            var running = runtime.submit(prompt, retrievedContext);
                            try {
                                outcome = running.get(Integer.getInteger("agent.eval.minutes", 12), TimeUnit.MINUTES);
                            } catch (java.util.concurrent.TimeoutException timeout) {
                                timeBudgetExceeded = true;
                                runtime.cancel();
                                outcome = running.get(30, TimeUnit.SECONDS);
                            }
                            approvalMetrics = runtime.approvalMetrics();
                        }
                        long agentElapsed = (System.nanoTime() - agentStart) / 1_000_000;
                        FxTestSupport.drainFx();
                        var unsaved = new ObjectMapper().createArrayNode();
                        for (var state : documents.states(new AgentCancellation())) {
                            if (!state.dirty()) continue;
                            Path relative = root.relativize(state.path());
                            var snapshot = documents.read(state.path(), new AgentCancellation());
                            unsaved.addObject().put("path", relative.toString()).put("revision", state.revision());
                            Path retained =
                                    scratch.resolve("unsaved").resolve(relative).normalize();
                            if (!retained.startsWith(scratch.resolve("unsaved")))
                                throw new IllegalStateException("Unsaved fixture path escaped");
                            Files.createDirectories(retained.getParent());
                            Files.writeString(retained, snapshot.text());
                        }
                        var changed = AgentEvaluationCases.changed(initial, AgentEvaluationCases.snapshot(root));
                        // JDT LS may create Eclipse project metadata. Record it separately, never ignore modifications
                        // to pre-existing metadata or attribute server-created files to the model's source edits.
                        var generated = new TreeSet<String>();
                        if (!jdtls.isBlank())
                            for (String path : changed) {
                                if (!initial.containsKey(path)
                                        && Set.of(
                                                        ".classpath",
                                                        ".project",
                                                        ".settings/org.eclipse.core.resources.prefs",
                                                        ".settings/org.eclipse.jdt.apt.core.prefs",
                                                        ".settings/org.eclipse.jdt.core.prefs",
                                                        ".settings/org.eclipse.m2e.core.prefs")
                                                .contains(path)) generated.add(path);
                            }
                        changed.removeAll(generated);
                        long oracleStart = System.nanoTime();
                        boolean oracle = AgentEvaluationCases.oracle(task, root, scratch.resolve("oracle"));
                        var report = eval.report(
                                task.id(),
                                provider.id(),
                                model,
                                context,
                                outcome,
                                changed,
                                AgentEvaluationCases.allowedChanges(task, changed),
                                task.required(),
                                oracle,
                                task.humanReview(),
                                task.callBudget());
                        var mapper = new ObjectMapper();
                        report.put("trial", trial)
                                .put("trialCount", trials)
                                .put("timeBudgetExceeded", timeBudgetExceeded)
                                .put("label", label)
                                .put("implementationFingerprint", implementationFingerprint)
                                .put("repositoryDirty", repositoryDirty);
                        report.set("unsavedFiles", unsaved);
                        natives.acceptance().reconcile(new AgentCancellation());
                        report.set("acceptance", natives.acceptance().metrics());
                        var newTests = natives.acceptance().ledger().current(AgentEvidence.Kind.TEST_ADDED).stream()
                                .map(AgentEvidence::subject)
                                .collect(java.util.stream.Collectors.toSet());
                        var quality = AgentAcceptanceCases.regressionQuality(
                                task, root, scratch.resolve("regression-quality"), oldSource, newTests);
                        report.set("regressionQuality", quality);
                        if (task.id().equals("editora-diff-test-quality")
                                && !quality.path("state").asText().equals("TEST_PROVEN_TO_DETECT_OLD_FAILURE")) {
                            report.put("taskSuccess", "FAIL");
                            report.withArray("failureCategories").add("REGRESSION_QUALITY_UNPROVEN");
                        }
                        if (timeBudgetExceeded)
                            report.withArray("failureCategories").add("TIME_BUDGET_EXHAUSTED");
                        report.set("initialProfile", initialProfile);
                        report.set("profile", http.profile().toJson());
                        report.put(
                                "profileFingerprint",
                                AgentSessionStore.hash(
                                        initialProfile.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        report.set(
                                "samplingRequested",
                                mapper.createObjectNode()
                                        .put(
                                                "temperature",
                                                profileConfig.temperature() == null
                                                        ? "SERVER_DEFAULT_UNKNOWN"
                                                        : profileConfig
                                                                .temperature()
                                                                .toString())
                                        .put(
                                                "seed",
                                                profileConfig.seed() == null
                                                        ? "SERVER_DEFAULT_UNKNOWN"
                                                        : profileConfig.seed().toString())
                                        .put(
                                                "seedSent",
                                                provider != AiProvider.ANTHROPIC && profileConfig.seed() != null)
                                        .put("determinismGuaranteed", false));
                        report.set("approvalMetrics", mapper.valueToTree(approvalMetrics));
                        var serverUri = java.net.URI.create(endpoint);
                        report.put(
                                "serverOrigin",
                                new java.net.URI(
                                                serverUri.getScheme(),
                                                null,
                                                serverUri.getHost(),
                                                serverUri.getPort(),
                                                null,
                                                null,
                                                null)
                                        .toString());
                        report.put(
                                "repositoryRevision",
                                com.editora.process.ProcessRunner.runRestricted(
                                                source, Duration.ofSeconds(5), List.of("git", "rev-parse", "HEAD"))
                                        .out()
                                        .strip());
                        report.put(
                                "taskRevision",
                                AgentSessionStore.hash(
                                        initial.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        report.put(
                                "promptFingerprint",
                                AgentSessionStore.hash(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        boolean testChanges = AgentEvaluationCases.requiredTestsChanged(task, changed);
                        report.put("requiredTestChangeSatisfied", testChanges);
                        if (!testChanges) {
                            report.put("taskSuccess", "FAIL");
                            report.withArray("failureCategories").add("MISSING_REGRESSION_TESTS");
                        }
                        report.put("category", task.category())
                                .put("agentElapsedMs", agentElapsed)
                                .put(
                                        "systemPromptHash",
                                        AgentSessionStore.hash(NativeAgentTools.SYSTEM.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8)))
                                .put(
                                        "catalogHash",
                                        AgentSessionStore.hash(registry.specs()
                                                .toString()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .put("serverContextLimit", Integer.getInteger("agent.eval.serverContext", 0))
                                .put("oracleElapsedMs", (System.nanoTime() - oracleStart) / 1_000_000)
                                .put("baselineTestsPassed", baselineTests)
                                .put("semanticExpected", task.semanticExpected())
                                .put("lspConfigured", !jdtls.isBlank())
                                .put("trust", policy.trust().name())
                                .put(
                                        "snapshotKind",
                                        Set.of(
                                                                "editora-save-understanding",
                                                                "editora-save-cancellation",
                                                                "editora-lsp-ui-understanding",
                                                                "editora-settings-persistence-understanding")
                                                        .contains(task.id())
                                                ? "FULL_EDITORA"
                                                : task.id().startsWith("editora-")
                                                        ? "REAL_EDITORA_COMPONENT"
                                                        : "CONTROLLED_JAVA_PROJECT")
                                .put(
                                        "panelUpdateMaxMs",
                                        renderTimes.stream()
                                                .mapToLong(Long::longValue)
                                                .max()
                                                .orElse(0));
                        report.put(
                                "fxDispatchMaxMs",
                                dispatchTimes.stream()
                                        .mapToLong(Long::longValue)
                                        .max()
                                        .orElse(0));
                        report.set("newLspMetadataFiles", new ObjectMapper().valueToTree(generated));
                        report.set("contextRankingBefore", rankingBefore);
                        report.set(
                                "contextRankingAfter",
                                new ObjectMapper()
                                        .readTree(registry.get("editor_context")
                                                .handler()
                                                .execute(new ObjectMapper().createObjectNode(), new AgentCancellation())
                                                .text())
                                        .path("ranked"));
                        // Final response retained only in the isolated scratch directory for human review, never in
                        // aggregate
                        // logs.
                        Files.writeString(scratch.resolve("final-response.txt"), outcome.detail());
                        Path output = reports.resolve(label + "-" + task.id() + "-"
                                + Integer.toHexString(model.hashCode()) + "-" + System.currentTimeMillis() + ".json");
                        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
                        System.out.println("AGENT_EVAL " + task.id() + " result="
                                + report.path("taskSuccess").asText() + " state=" + outcome.state() + " calls="
                                + report.path("calls").asInt() + " report=" + output + " scratch=" + scratch);
                        FxTestSupport.runOnFx(panel::clearTranscript);
                    }
                } catch (Exception failure) {
                    // A harness/setup failure is an unsuccessful trial too; never silently drop it.
                    var mapper = new ObjectMapper();
                    var report = mapper.createObjectNode()
                            .put("schemaVersion", 2)
                            .put("kind", "AUTONOMOUS_CODING_EVALUATION")
                            .put("scenario", task.id())
                            .put("provider", provider.id())
                            .put("model", model)
                            .put("label", label)
                            .put("trial", trial)
                            .put("completionState", "FAILED")
                            .put("taskSuccess", "FAIL")
                            .put("oraclePassed", false)
                            .put("implementationFingerprint", implementationFingerprint)
                            .put("repositoryDirty", repositoryDirty)
                            .put("harnessErrorType", failure.getClass().getSimpleName());
                    report.putArray("failureCategories").add("HARNESS_FAILURE");
                    Path output = reports.resolve(
                            label + "-" + task.id() + "-harness-" + System.currentTimeMillis() + ".json");
                    mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
                    System.out.println("AGENT_EVAL_HARNESS_FAILURE report=" + output);
                }
            }
        }
    }
}
