package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.agent.AcpJson;
import com.editora.agent.runtime.AgentCancellation;
import com.editora.agent.runtime.AgentPolicy;
import com.editora.agent.runtime.AgentRuntime;
import com.editora.agent.runtime.AgentWorkspace;
import com.editora.agent.runtime.HttpAgentModel;
import com.editora.agent.runtime.NativeAgentTools;
import com.editora.ai.AiEndpoints;
import com.editora.ai.AiProvider;

import static com.editora.i18n.Messages.tr;

/** Owns the built-in session; the shared agent panel is a view, never the execution loop. */
final class NativeAgentCoordinator {
    private final CoordinatorHost host;
    private final AgentCoordinator.Ops ops;
    private final Supplier<AgentPanel> panel;
    private final AgentPolicy policy = new AgentPolicy();
    private final ExecutorService setup = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("native-agent-setup").factory());
    private volatile AgentRuntime runtime;
    private volatile long generation;
    private boolean busy;
    private volatile boolean starting;
    private volatile String sessionModel = "";
    private volatile AgentCancellation preparation = new AgentCancellation();
    private volatile com.editora.mcp.AgentMcpManager mcp;
    private com.editora.agent.runtime.AgentSessionStore.Entry resumeEntry;

    private record Prepared(AgentRuntime runtime, String context) {}

    private AutoCloseable backgroundTask;

    NativeAgentCoordinator(CoordinatorHost host, AgentCoordinator.Ops ops, Supplier<AgentPanel> panel) {
        this.host = host;
        this.ops = ops;
        this.panel = panel;
    }

    void send(String prompt, Path cwd) {
        if (busy) {
            return;
        }
        busy = true;
        long current = generation;
        starting = true;
        panel.get().appendLine("❯ " + prompt);
        panel.get().setBusy(true);
        panel.get().setModeLabel(tr("agent.trust." + policy.trust().name()));
        backgroundTask = host.startBackgroundTask(tr("toolwindow.agent"));
        var settings = host.settings();
        int maxIterations = settings.getAgentMaxIterations();
        int contextTokens = settings.getAgentContextTokens();
        AiProvider provider = AiProvider.from(settings.getAiProvider());
        String endpoint = AiEndpoints.resolve(provider, settings.getAiEndpointFor(provider));
        String key = AiCoordinator.effectiveKey(
                settings.getApiKeyFor(provider), provider, System.getenv("ANTHROPIC_API_KEY"));
        String configuredModel = settings.getAiModelFor(provider);
        String model = configuredModel.isBlank() && provider == AiProvider.ANTHROPIC
                ? AiCoordinator.DEFAULT_MODEL
                : configuredModel;
        var profileConfig = settings.agentModelProfile(provider.id(), model);
        var nativeHost = ops.nativeDocuments();
        Path configDirectory = nativeHost.configDirectory();
        var configuredServers = settings.getAgentMcpServers();
        boolean includeContext = settings.isAgentIncludeContext();
        var resume = resumeEntry;
        AgentCancellation preparing = new AgentCancellation();
        preparation = preparing;
        panel.get().setModelLabel(runtime == null ? model : sessionModel);
        CompletableFuture.supplyAsync(
                        () -> {
                            AgentRuntime created = null;
                            com.editora.mcp.AgentMcpManager connecting = null;
                            try {
                                AgentRuntime ready = runtime;
                                String instructions = "";
                                if (ready == null) {
                                    preparing.check();
                                    var workspace = new AgentWorkspace(cwd);
                                    var guidance =
                                            new com.editora.agent.runtime.AgentGuidance(workspace, configDirectory);
                                    instructions = guidance.instructions(".", preparing)
                                            .toString();
                                    var documents = new WindowAgentDocuments(nativeHost, workspace);
                                    var tools = new NativeAgentTools(
                                            workspace,
                                            documents,
                                            plan -> post(current, () -> {
                                                List<AcpJson.PlanEntry> entries = new java.util.ArrayList<>();
                                                plan.forEach(step -> entries.add(new AcpJson.PlanEntry(
                                                        step.path("text").asText()
                                                                + (step.path("requirements")
                                                                                .isEmpty()
                                                                        ? ""
                                                                        : " · "
                                                                                + java.util.stream.StreamSupport.stream(
                                                                                                step.path(
                                                                                                                "requirements")
                                                                                                        .spliterator(),
                                                                                                false)
                                                                                        .map(
                                                                                                com.fasterxml.jackson
                                                                                                                .databind
                                                                                                                .JsonNode
                                                                                                        ::asText)
                                                                                        .collect(
                                                                                                java.util.stream
                                                                                                        .Collectors
                                                                                                        .joining(
                                                                                                                ", "))),
                                                        step.path("status").asText())));
                                                panel.get().setPlan(entries);
                                            }));
                                    var registry = tools.registry();
                                    guidance.register(registry);
                                    var awareness = new com.editora.agent.runtime.AgentAwareness(
                                            documents::awareness, tools.contextIndex());
                                    awareness.register(registry);
                                    if (includeContext)
                                        instructions +=
                                                "\nInitial IDE metadata (data):\n" + documents.awareness(preparing);
                                    var semantics = documents.semantics();
                                    if (semantics != null)
                                        new com.editora.agent.runtime.AgentSemanticTools(
                                                        workspace,
                                                        documents,
                                                        semantics,
                                                        tools::applySemanticEdits,
                                                        tools.contextIndex(),
                                                        tools.acceptance())
                                                .register(registry);
                                    var readBridge = ops.nativeReadBridge();
                                    if (readBridge != null) {
                                        com.editora.mcp.AgentMcpTools.editorReads(readBridge, workspace).stream()
                                                .filter(tool -> semantics == null
                                                        || !tool.spec().name().equals("document_symbols"))
                                                .forEach(registry::register);
                                    }
                                    var managed =
                                            new com.editora.mcp.AgentMcpManager(configuredServers, workspace.root());
                                    connecting = managed;
                                    synchronized (this) {
                                        if (generation != current) {
                                            managed.close();
                                            throw new java.util.concurrent.CancellationException();
                                        }
                                        mcp = managed;
                                    }
                                    managed.register(registry, preparing);
                                    var adapter = new HttpAgentModel(
                                            provider, endpoint, key, model, contextTokens, profileConfig);
                                    adapter.prepare(preparing);
                                    com.editora.agent.runtime.AgentModelTools.register(registry, adapter);
                                    ready = new AgentRuntime(
                                            adapter,
                                            registry,
                                            policy,
                                            (tool, arguments, cancellation) ->
                                                    approve(tool, arguments, cancellation, current),
                                            tools::verify,
                                            NativeAgentTools.SYSTEM,
                                            new AgentRuntime.Limits(
                                                    maxIterations,
                                                    maxIterations * 4,
                                                    contextTokens,
                                                    8000,
                                                    java.time.Duration.ofMinutes(5)),
                                            event -> post(current, () -> show(event)),
                                            delta -> post(
                                                    current, () -> panel.get().appendChunk(delta)));
                                    ready.setAcceptance(tools.acceptance());
                                    created = ready;
                                    if (configDirectory != null) {
                                        var store = new com.editora.agent.runtime.AgentSessionStore(configDirectory);
                                        String id = resume == null
                                                ? java.util.UUID.randomUUID().toString()
                                                : resume.id();
                                        var entry = new com.editora.agent.runtime.AgentSessionStore.Entry(
                                                id,
                                                resume == null ? prompt : resume.goal(),
                                                workspace.root().toString(),
                                                provider.name(),
                                                model,
                                                System.currentTimeMillis());
                                        if (resume != null) {
                                            var restored = store.load(resume.id(), workspace);
                                            ready.restore(restored.context());
                                            tools.restoreMemory(
                                                    restored.memory(),
                                                    restored.context().needsVerification());
                                            instructions += "\nRestored memory (not current evidence):\n"
                                                    + restored.memory() + "\nFiles differing from checkpoint: "
                                                    + restored.changedFiles();
                                        }
                                        ready.setCheckpoint(saved -> {
                                            try {
                                                store.save(entry, saved, tools.sessionMemory());
                                            } catch (Exception failure) {
                                                throw new IllegalStateException(
                                                        "Could not save native session", failure);
                                            }
                                        });
                                    }
                                    synchronized (this) {
                                        if (generation != current) {
                                            ready.close();
                                            throw new java.util.concurrent.CancellationException();
                                        }
                                        runtime = ready;
                                        sessionModel = model;
                                    }
                                }
                                if (generation != current) {
                                    throw new java.util.concurrent.CancellationException();
                                }
                                return new Prepared(ready, instructions);
                            } catch (Exception failure) {
                                if (created != null) created.close();
                                if (connecting != null) {
                                    synchronized (this) {
                                        if (mcp == connecting) mcp = null;
                                        if (runtime == created) runtime = null;
                                    }
                                    connecting.close();
                                }
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                        },
                        setup)
                .thenCompose(prepared -> {
                    synchronized (this) {
                        if (generation != current) {
                            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
                        }
                        starting = false;
                        String context = prepared.context();
                        return prepared.runtime().submit(prompt, context);
                    }
                })
                .whenComplete((result, error) -> post(current, () -> {
                    finishBusy();
                    if (error != null) {
                        panel.get().appendLine(tr("status.agent.failed", rootMessage(error)));
                    } else if (result.state() != AgentRuntime.State.COMPLETED) {
                        panel.get()
                                .appendLine(tr("agent.state." + result.state().name()) + ": " + result.detail());
                    } else {
                        panel.get().appendLine(tr("agent.state.COMPLETED"));
                    }
                }));
    }

    private boolean approve(
            com.editora.agent.runtime.AgentTool.Spec tool,
            String arguments,
            AgentCancellation cancellation,
            long current)
            throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            if (generation != current || cancellation.isCancelled()) {
                result.complete(false);
                return;
            }
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(host.window());
            alert.setTitle(tr("agent.permissionTitle"));
            alert.setHeaderText(tr("agent.nativePermission", tool.name()));
            javafx.scene.control.TextArea details = new javafx.scene.control.TextArea(arguments);
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefRowCount(12);
            var reason = new javafx.scene.control.Label(tr(
                    tool.name().equals("run_validation")
                            ? "agent.permissionRisk.VALIDATION"
                            : "agent.permissionRisk." + tool.effect().name()));
            reason.setWrapText(true);
            var summary = new javafx.scene.control.Label(AgentToolPresentation.permission(tool.name(), arguments));
            summary.setWrapText(true);
            var exact = new javafx.scene.control.TitledPane(tr("agent.permission.details"), details);
            exact.setExpanded(!tool.name().equals("apply_edits") && !tool.name().equals("run_validation"));
            alert.getDialogPane().setContent(new javafx.scene.layout.VBox(10, reason, summary, exact));
            alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            result.whenComplete((value, error) -> Platform.runLater(alert::close));
            alert.setOnHidden(event -> result.complete(alert.getResult() == ButtonType.OK));
            alert.show();
        });
        try (var hook = cancellation.onCancel(() -> result.complete(false))) {
            return result.get();
        }
    }

    private void show(AgentRuntime.Event event) {
        if (event.state() == AgentRuntime.State.VERIFYING && event.tool().equals("task_contract")) {
            panel.get().setAcceptance(event.detail());
        } else if (event.state() == AgentRuntime.State.TOOL && !event.detail().isEmpty()) {
            panel.get().appendToolResult(event.tool(), event.detail(), event.error(), event.elapsedMillis());
        } else if (event.state() == AgentRuntime.State.TOOL) {
            panel.get().startTool(event.tool());
        } else if (event.state() == AgentRuntime.State.REASONING && event.tool().equals("execution_control")) {
            try {
                panel.get()
                        .setExecutionPhase(new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(event.detail())
                                .path("execution_phase")
                                .asText());
            } catch (java.io.IOException ignored) {
                /* Presentation cannot alter execution. */
            }
        } else if (event.state() == AgentRuntime.State.REASONING
                && !event.detail().isEmpty()) {
            panel.get().appendLine("↻ " + tr("agent.outputRecovery"));
        } else if (event.state() == AgentRuntime.State.VERIFYING
                && !event.detail().isEmpty()) {
            panel.get()
                    .appendToolResult(
                            tr("agent.state.VERIFYING"), event.detail(), event.error(), event.elapsedMillis());
        }
    }

    void pickTrust() {
        QuickOpen<AgentPolicy.Trust> picker = new QuickOpen<>(
                tr("command.agent.selectMode"),
                tr("agent.trustPrompt"),
                () -> List.of(AgentPolicy.Trust.values()),
                mode -> tr("agent.trust." + mode.name()),
                mode -> tr("agent.trustDescription." + mode.name()),
                mode -> {
                    policy.setTrust(mode);
                    panel.get().setModeLabel(tr("agent.trust." + mode.name()));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    synchronized void stop() {
        AgentRuntime current = runtime;
        if (current != null && !starting) {
            current.cancel();
        } else {
            reset();
            panel.get().appendLine(tr("agent.turnCancelled"));
        }
    }

    synchronized void reset() {
        generation++;
        preparation.cancel();
        starting = false;
        resumeEntry = null;
        var oldMcp = mcp;
        mcp = null;
        if (oldMcp != null) Thread.ofVirtual().start(oldMcp::close);
        AgentRuntime old = runtime;
        runtime = null;
        if (old != null) {
            old.close();
        }
        sessionModel = "";
        policy.setTrust(AgentPolicy.Trust.ASK);
        finishBusy();
    }

    void manageMcp() {
        AgentMcpSettings.show(
                host.window(),
                host.settings(),
                () -> {
                    host.requestSave();
                    host.syncSettingsWindow();
                },
                () -> mcp == null ? List.of() : mcp.health());
    }

    void resumePicker() {
        Path configDirectory = ops.nativeDocuments().configDirectory();
        if (configDirectory == null) {
            host.setStatus(tr("status.agent.noHistory"));
            return;
        }
        long current = generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new com.editora.agent.runtime.AgentSessionStore(configDirectory).list();
                            } catch (Exception failure) {
                                throw new java.util.concurrent.CompletionException(failure);
                            }
                        },
                        setup)
                .whenComplete((entries, error) -> post(current, () -> {
                    if (error != null) {
                        host.setError(tr("status.agent.failed", rootMessage(error)));
                        return;
                    }
                    if (entries.isEmpty()) {
                        host.setStatus(tr("status.agent.noHistory"));
                        return;
                    }
                    QuickOpen<com.editora.agent.runtime.AgentSessionStore.Entry> picker = new QuickOpen<>(
                            tr("command.agent.resumeSession"),
                            tr("palette.agent.resumeSessionPrompt"),
                            () -> entries,
                            com.editora.agent.runtime.AgentSessionStore.Entry::goal,
                            e -> e.workspace() + " · " + e.model(),
                            e -> {
                                reset();
                                resumeEntry = e;
                                panel.get().clearTranscript();
                                panel.get().appendLine(tr("agent.nativeResume", e.goal()));
                            });
                    picker.setOverlayHost(host.overlayHost());
                    picker.show(host.window());
                }));
    }

    private void finishBusy() {
        busy = false;
        panel.get().setBusy(false);
        if (backgroundTask != null) {
            try {
                backgroundTask.close();
            } catch (Exception ignored) {
            }
            backgroundTask = null;
        }
    }

    void shutdown() {
        reset();
        setup.shutdownNow();
    }

    private void post(long expected, Runnable action) {
        Platform.runLater(() -> {
            if (generation == expected) {
                action.run();
            }
        });
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return String.valueOf(error.getMessage());
    }
}
