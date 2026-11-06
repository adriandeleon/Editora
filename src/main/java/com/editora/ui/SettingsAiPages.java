package com.editora.ui;

import java.util.List;
import java.util.Map;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import com.editora.ui.SettingsWindow.McpActions;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → AI (the master switch), AI Agent (the ACP clients and their commands), AI Actions (provider, endpoint,
 * model, key and the connection check) and MCP Server. The pages' controls, their load and the injected AI hooks
 * live here; {@link SettingsWindow} builds them with the other pages and forwards its setters to them.
 */
final class SettingsAiPages {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsAiPages(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        mcpCheck = new CheckBox(tr("settings.mcp.enable"));
        mcpCheck.selectedProperty().addListener((obs, was, now) -> {
            // A user-initiated enable shows a security notice first; declining reverts the checkbox.
            if (!w.loading && now && mcpConfirm != null && !mcpConfirm.getAsBoolean()) {
                boolean prev = w.loading;
                w.loading = true;
                try {
                    mcpCheck.setSelected(false);
                } finally {
                    w.loading = prev;
                }
                return;
            }
            config.getSettings().setMcpSupport(now);
            w.apply(); // starts or stops the server
            refreshMcpStatus();
        });

        aiMasterCheck = new CheckBox(tr("settings.ai.masterEnable"));
        aiMasterCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiEnabled(now);
            w.apply();
            scheduleAiStatus(); // the AI Actions status said "Connected" after AI was turned off
            refreshAgentClientStatus();
        });

        agentCheck = new CheckBox(tr("settings.agent.enable"));
        agentCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAgentSupport(now);
            w.apply();
            refreshAgentClientStatus(); // the pills turn red/neutral with the switch
        });
        agentClientCombo = new ComboBox<>();
        com.editora.agent.AcpAgentRegistry.all()
                .forEach(d -> agentClientCombo.getItems().add(d.id()));
        agentClientCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : tr("settings.agent.client." + id);
            }

            @Override
            public String fromString(String str) {
                return str;
            }
        });
        agentClientCombo.valueProperty().addListener((obs, was, now) -> {
            if (!w.loading && now != null && agentCoordinator != null) {
                agentCoordinator.switchAgentClient(com.editora.agent.AcpAgentRegistry.from(now));
                w.apply(); // run the standard settings-applied hook for consistency with the other rows
            }
        });
        for (AgentClientUi a : agentClientUis()) {
            TextField field = new TextField();
            field.setPromptText(a.defaultCommand());
            w.commitOnEnterOrBlur(field, a.getCommand(), now -> {
                a.setCommand().accept(now);
                w.apply();
                if (agentCoordinator != null) {
                    agentCoordinator.invalidateDetection(); // command changed -> re-probe
                }
                refreshAgentClientStatus();
            });
            agentCommandFields.put(a.id(), field);
        }
        agentIncludeContextCheck = new CheckBox(tr("settings.agent.includeContext"));
        agentIncludeContextCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAgentIncludeContext(now);
            w.apply();
        });

        aiCheck = new CheckBox(tr("settings.ai.enable"));
        aiCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiSupport(now);
            w.apply();
            scheduleAiStatus();
        });
        aiProviderCombo = new ComboBox<>();
        aiProviderCombo.getItems().addAll(com.editora.ai.AiProvider.ids());
        aiProviderCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : tr("settings.ai.provider." + id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        aiProviderCombo.valueProperty().addListener((obs, was, now) -> {
            if (!w.loading && now != null) {
                if (was != null) {
                    // Text typed but not yet committed belongs to the provider it was typed for; the fields
                    // are about to be refilled with the new provider's values.
                    commitAiFieldsFor(com.editora.ai.AiProvider.from(was));
                }
                config.getSettings().setAiProvider(now);
                // Keys are per-provider: show the newly-selected provider's key (never carry one provider's
                // credential over to the other, which would send it to that provider's endpoint).
                syncAiProviderFields();
                w.apply();
                scheduleAiStatus();
            }
        });
        aiStatusDebounce.setOnFinished(e -> refreshAiStatus(false));
        aiEndpointField = new TextField();
        aiEndpointField.setPromptText(tr("settings.ai.endpointPrompt"));
        w.commitOnEnterOrBlur(
                aiEndpointField, () -> config.getSettings().getAiEndpointFor(selectedAiProvider()), now -> {
                    config.getSettings().setAiEndpointFor(selectedAiProvider(), now);
                    w.apply();
                    scheduleAiStatus();
                });
        aiModelField = new TextField();
        aiModelField.setPromptText("claude-opus-4-8");
        w.commitOnEnterOrBlur(aiModelField, () -> config.getSettings().getAiModelFor(selectedAiProvider()), now -> {
            config.getSettings().setAiModelFor(selectedAiProvider(), now);
            w.apply();
            scheduleAiStatus();
        });
        aiApiKeyField = new javafx.scene.control.PasswordField();
        aiApiKeyField.setPromptText(tr("settings.ai.apiKeyPrompt"));
        w.commitOnEnterOrBlur(aiApiKeyField, () -> config.getSettings().getApiKeyFor(selectedAiProvider()), now -> {
            // Store under the currently-selected provider so each provider keeps its own key.
            config.getSettings().setApiKeyFor(selectedAiProvider(), now);
            w.apply();
            scheduleAiStatus();
        });
        aiInlineCheck = new CheckBox(tr("settings.ai.inline"));
        aiInlineCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiInlineCompletion(now);
            w.apply();
        });
        aiCompletionModelField = new TextField();
        aiCompletionModelField.setPromptText("claude-haiku-4-5");
        w.commitOnEnterOrBlur(
                aiCompletionModelField,
                () -> config.getSettings().getAiCompletionModelFor(selectedAiProvider()),
                now -> {
                    config.getSettings().setAiCompletionModelFor(selectedAiProvider(), now);
                    w.apply();
                });
    }

    /** Security-notice confirm shown before the MCP checkbox enables the server; null = no gate. */
    private java.util.function.@Nullable BooleanSupplier mcpConfirm;

    private CheckBox mcpCheck;
    private CheckBox aiMasterCheck;
    private CheckBox agentCheck;
    private @Nullable AgentCoordinator agentCoordinator; // injected; backs the AI Agent page's detection status rows
    private ComboBox<String> agentClientCombo;
    private final java.util.Map<String, TextField> agentCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ToolStatusRow> agentStatusLabels = new java.util.LinkedHashMap<>();
    private CheckBox agentIncludeContextCheck;
    private CheckBox aiCheck;
    private TextField aiModelField;
    private TextField aiApiKeyField;
    private CheckBox aiInlineCheck;
    private TextField aiCompletionModelField;
    private ComboBox<String> aiProviderCombo;
    private TextField aiEndpointField;
    private @Nullable Label aiStatusLabel;
    /** Injected by MainController: runs a live connection check, delivering (ok, message) on the FX thread.
     *  (fresh, onResult): a fresh check, or the last one when it is recent (see AiCoordinator.checkConnection). */
    private java.util.function.@Nullable BiConsumer<Boolean, java.util.function.BiConsumer<Boolean, String>>
            aiConnectionProbe;
    /** Coalesces rapid field edits into a single connection check (~600 ms after the last keystroke). */
    private final javafx.animation.PauseTransition aiStatusDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    /** Wires the security-notice confirm shown before the MCP checkbox enables the server. */
    public void setMcpConfirm(java.util.function.BooleanSupplier confirm) {
        this.mcpConfirm = confirm;
    }

    VBox mcpPage() {
        VBox p = w.page(tr("settings.cat.mcp"), tr("settings.mcp.subtitle"));
        Card c = w.card(p, null);
        w.checkRow(
                c,
                Category.MCP,
                mcpCheck,
                tr("settings.mcp.hint"),
                "mcp model context protocol agent server enable ai claude llm loopback token endpoint");
        mcpStatusLabel = new Label();
        mcpStatusLabel.setWrapText(true);
        mcpCopyButton = new Button(tr("settings.mcp.copy"));
        mcpCopyButton.setOnAction(e -> {
            if (mcpActions != null) { // the buttons are enabled only while the server runs
                mcpActions.copyCommand().run();
            }
        });
        mcpNewTokenButton = new Button(tr("settings.mcp.newToken"));
        mcpNewTokenButton.setOnAction(e -> {
            if (mcpActions != null) {
                mcpActions.newToken().run();
            }
            refreshMcpStatus();
        });
        w.cardRow(
                c,
                Category.MCP,
                SettingsWindow.settingRow(
                        tr("settings.mcp.status"),
                        tr("settings.mcp.status.desc"),
                        new VBox(6, mcpStatusLabel, new HBox(8, mcpCopyButton, mcpNewTokenButton))),
                "mcp status running port endpoint url copy command token new rotate claude mcp add");
        refreshMcpStatus();
        return p;
    }

    private @Nullable McpActions mcpActions;
    private @Nullable Label mcpStatusLabel;
    private @Nullable Button mcpCopyButton;
    private @Nullable Button mcpNewTokenButton;

    void setMcpActions(McpActions actions) {
        this.mcpActions = actions;
        refreshMcpStatus();
    }

    /** Running at which endpoint, or off; the buttons only while it runs. */
    private void refreshMcpStatus() {
        if (mcpStatusLabel == null || mcpCopyButton == null || mcpNewTokenButton == null) { // built together
            return;
        }
        String endpoint = mcpActions == null ? "" : mcpActions.endpoint().get();
        boolean running = !endpoint.isEmpty();
        mcpStatusLabel.setText(
                running
                        ? tr("settings.mcp.running", endpoint)
                        : tr(config.getSettings().isMcpSupport() ? "status.mcp.notRunning" : "settings.mcp.off"));
        mcpCopyButton.setDisable(!running);
        mcpNewTokenButton.setDisable(!running);
    }

    /**
     * The AI group's landing page: the master AI kill switch. Off by default; when off, the AI Agent and
     * AI Actions pages' own enable checkboxes are disabled (their settings still exist but have no effect
     * — see {@link AgentCoordinator#isEnabled()} / {@link AiCoordinator#isEnabled()}), mirroring the
     * autocomplete master/sub-toggle pattern. Deliberately doesn't touch the separate MCP Server page —
     * that lets an *external* agent drive Editora, not Editora calling out to AI.
     */
    VBox aiGeneralPage() {
        VBox p = w.page(tr("settings.cat.aiGeneral"), tr("settings.aiGeneral.subtitle"));
        Card mainCard = w.card(p, null);
        w.checkRow(
                mainCard,
                Category.AI_GENERAL,
                aiMasterCheck,
                null,
                "ai enable disable master switch agent actions all");
        Label hint = SettingsWindow.note(tr("settings.ai.masterHint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        w.cardRow(mainCard, Category.AI_GENERAL, hint, "ai enable disable master switch agent actions all off default");
        return p;
    }

    VBox agentPage() {
        VBox p = w.page(tr("settings.cat.agent"), tr("settings.agent.subtitle"));
        w.dependsOn(p, aiMasterCheck);
        Card mainCard = w.card(p, null);
        SettingsDependents.bind(aiMasterCheck.selectedProperty(), mainCard.box());
        w.checkRow(mainCard, Category.AGENT, agentCheck, null, "ai agent acp claude code chat enable assistant");
        // What the agent is comes first, under its switch; each client's own setup note sits on its card.
        w.cardRow(
                mainCard,
                Category.AGENT,
                wrappedNote("settings.agent.hint"),
                "ai agent acp install npm chat tool window");
        w.cardRow(
                mainCard,
                Category.AGENT,
                w.labeledRow(tr("settings.agent.client"), agentClientCombo),
                "ai agent acp active client switch gemini copilot codex qwen opencode claude");
        w.checkRow(
                mainCard,
                Category.AGENT,
                agentIncludeContextCheck,
                null,
                "ai agent acp context cursor line selection file attach prompt");
        // One (untitled) card per agent client, mirroring the Language Servers page: the command row
        // names the client, so a title would just repeat it in every locale.
        for (AgentClientUi a : agentClientUis()) {
            Card c = w.card(p, null);
            SettingsDependents.bind(aiMasterCheck.selectedProperty(), c.box());
            // The card of the client the combo picked says so.
            Label active = new Label(tr("settings.agent.active"));
            active.getStyleClass().addAll("settings-git-status", "settings-git-found");
            active.visibleProperty().bind(agentClientCombo.valueProperty().isEqualTo(a.id()));
            active.managedProperty().bind(active.visibleProperty());
            ToolStatusRow status = new ToolStatusRow(agentToolName(a)).lead(active);
            agentStatusLabels.put(a.id(), status);
            String title = tr(a.commandLabelKey());
            w.toolRows(c, Category.AGENT, title, null, agentCommandFields.get(a.id()), null, status, a.keywords());
            String setup = switch (a.id()) {
                case "codex" -> "settings.agent.codexHint";
                case "lmstudio" -> "settings.agent.lmstudioHint";
                default -> null;
            };
            if (setup != null) {
                w.cardRow(c, Category.AGENT, wrappedNote(setup), a.keywords() + " install login setup local model");
            }
        }
        return p;
    }

    /** A grey note row of {@code key}'s text, wrapped to the kit's description width. */
    private static Label wrappedNote(String key) {
        Label l = SettingsWindow.note(tr(key));
        l.setWrapText(true);
        l.setMaxWidth(440);
        return l;
    }

    /** Injected by MainController: backs the AI Agent page's per-client PATH-detection rows + the active-
     *  client combo (which routes through the coordinator's single switch path). */
    void setAgentCoordinator(AgentCoordinator coordinator) {
        this.agentCoordinator = coordinator;
    }

    /** A per-agent-client Settings row (data-driven; mirrors LspServerUi but with no enable flag — exactly
     *  one ACP agent is active at a time, chosen by the combo, not per-client toggles). */
    private record AgentClientUi(
            String id,
            String displayName,
            String defaultCommand,
            String commandLabelKey,
            String keywords,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand) {}

    private java.util.List<AgentClientUi> agentClientUis() {
        Settings s = config.getSettings();
        return java.util.List.of(
                new AgentClientUi(
                        "claude",
                        "Claude Code",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("claude"),
                        "settings.agent.command.claude",
                        "agent claude code acp command executable path",
                        s::setAgentCommand,
                        s::getAgentCommand),
                new AgentClientUi(
                        "gemini",
                        "Gemini CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("gemini"),
                        "settings.agent.command.gemini",
                        "agent gemini cli google acp command executable path",
                        s::setGeminiAgentCommand,
                        s::getGeminiAgentCommand),
                new AgentClientUi(
                        "copilot",
                        "GitHub Copilot CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("copilot"),
                        "settings.agent.command.copilot",
                        "agent copilot github cli acp command executable path",
                        s::setCopilotAgentCommand,
                        s::getCopilotAgentCommand),
                new AgentClientUi(
                        "codex",
                        "Codex CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("codex"),
                        "settings.agent.command.codex",
                        "agent codex openai cli acp command executable path",
                        s::setCodexAgentCommand,
                        s::getCodexAgentCommand),
                new AgentClientUi(
                        "qwen",
                        "Qwen Code",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("qwen"),
                        "settings.agent.command.qwen",
                        "agent qwen code alibaba cli acp command executable path",
                        s::setQwenAgentCommand,
                        s::getQwenAgentCommand),
                new AgentClientUi(
                        "opencode",
                        "OpenCode",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("opencode"),
                        "settings.agent.command.opencode",
                        "agent opencode sst cli acp command executable path",
                        s::setOpencodeAgentCommand,
                        s::getOpencodeAgentCommand),
                new AgentClientUi(
                        "lmstudio",
                        "LM Studio / Bionic (OpenCode)",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("lmstudio"),
                        "settings.agent.command.lmstudio",
                        "agent lm studio bionic local opencode acp command executable path",
                        s::setLmstudioAgentCommand,
                        s::getLmstudioAgentCommand));
    }

    /** Re-probes each ACP agent client's PATH availability (mirrors {@link #refreshLspStatus()}). */
    void refreshAgentClientStatus() {
        if (agentCoordinator == null || agentStatusLabels.isEmpty()) {
            return;
        }
        for (AgentClientUi a : agentClientUis()) {
            ToolStatusRow status = agentStatusLabels.get(a.id());
            if (status == null) {
                continue;
            }
            List<String> argv = agentArgv(a);
            status.tool(agentToolName(a));
            status.checking();
            agentCoordinator.detect(
                    a.id(),
                    found -> status.show(
                            found,
                            found ? ToolStatusRow.pathOf(argv) : "",
                            aiMasterCheck.isSelected() && agentCheck.isSelected()));
        }
    }

    private static List<String> agentArgv(AgentClientUi a) {
        return com.editora.agent.AcpAgentRegistry.commandFor(
                a.id(), Map.of(a.id(), a.getCommand().get()));
    }

    /** The program an agent client runs (claude-code-acp, gemini, codex-acp, …), as its pill names it. */
    private static String agentToolName(AgentClientUi a) {
        return ToolStatusRow.toolName(agentArgv(a), a.id());
    }

    /** Injected by MainController: runs a live AI connection check (green/red status on the AI page). */
    public void setAiConnectionProbe(
            java.util.function.BiConsumer<Boolean, java.util.function.BiConsumer<Boolean, String>> probe) {
        this.aiConnectionProbe = probe;
    }

    /** Debounced connection re-check after a field edit (avoids a network call per keystroke). */
    private void scheduleAiStatus() {
        if (!w.loading) {
            aiStatusDebounce.playFromStart();
        }
    }

    /**
     * Checks the connection for the AI Actions page — only while that page is on screen, so opening Settings
     * sends no request. Unless {@code fresh} (Test connection) a recent result for the same configuration is
     * shown without a request. "Checking…" shows until the result lands.
     */
    private void refreshAiStatus(boolean fresh) {
        if (aiStatusLabel == null || aiConnectionProbe == null || aiStatusLabel.getScene() == null) {
            return;
        }
        aiStatusLabel.getStyleClass().setAll("settings-git-status");
        aiStatusLabel.setText(tr("settings.ai.checking"));
        aiConnectionProbe.accept(fresh, this::syncAiStatus);
    }

    /** Colors the AI status label green/red (the Git/Mermaid/LSP idiom): connected vs the error message. */
    private void syncAiStatus(boolean ok, String message) {
        if (aiStatusLabel == null) {
            return;
        }
        aiStatusLabel
                .getStyleClass()
                .setAll(
                        "settings-git-status",
                        SettingsWindow.pillClass(ok, aiMasterCheck.isSelected() && aiCheck.isSelected()));
        aiStatusLabel.setText(ok ? tr("settings.ai.connected") : tr("settings.ai.connectFailed", message));
    }

    private com.editora.ai.AiProvider selectedAiProvider() {
        return com.editora.ai.AiProvider.from(config.getSettings().getAiProvider());
    }

    /** Stores what the four per-provider fields show under {@code provider} (the caller saves and applies). */
    private void commitAiFieldsFor(com.editora.ai.AiProvider provider) {
        Settings settings = config.getSettings();
        if (provider != com.editora.ai.AiProvider.CODEX) { // disabled for Codex, which owns its own login
            settings.setAiEndpointFor(provider, aiEndpointField.getText());
            settings.setApiKeyFor(provider, aiApiKeyField.getText());
            settings.setAiCompletionModelFor(provider, aiCompletionModelField.getText());
        }
        settings.setAiModelFor(provider, aiModelField.getText());
    }

    /** Reload together: switching providers must not write the previous provider's fields back. */
    private void syncAiProviderFields() {
        boolean previous = w.loading;
        w.loading = true;
        try {
            var provider = selectedAiProvider();
            var settings = config.getSettings();
            aiModelField.setText(settings.getAiModelFor(provider));
            aiCompletionModelField.setText(settings.getAiCompletionModelFor(provider));
            aiEndpointField.setText(settings.getAiEndpointFor(provider));
            aiApiKeyField.setText(settings.getApiKeyFor(provider));
            if (aiKeyNote != null) {
                aiKeyNote.setText(apiKeyNote(settings));
            }
        } finally {
            w.loading = previous;
        }
        updateAiProviderControls();
    }

    /** Says where the API keys are stored: the system keychain, or settings.json and why. */
    private @Nullable Label aiKeyNote;

    static String apiKeyNote(Settings settings) {
        return switch (settings.apiKeyStorage()) {
            case KEYCHAIN -> tr("settings.ai.apiKeyInKeychain", settings.apiKeyStoreName());
            case KEYCHAIN_UNREACHABLE -> tr("settings.ai.apiKeyKeychainUnreachable");
            case SETTINGS_FILE -> tr("settings.ai.apiKeyNote");
        };
    }

    /** Codex owns its login/transport; inline completion remains available for HTTP providers. */
    private void updateAiProviderControls() {
        var provider = com.editora.ai.AiProvider.from(aiProviderCombo.getValue());
        boolean codex = provider == com.editora.ai.AiProvider.CODEX;
        boolean anthropic = provider == com.editora.ai.AiProvider.ANTHROPIC;
        aiEndpointField.setDisable(codex);
        aiApiKeyField.setDisable(codex);
        aiInlineCheck.setDisable(codex);
        aiCompletionModelField.setDisable(codex);
        aiModelField.setPromptText(
                anthropic
                        ? AiCoordinator.DEFAULT_MODEL
                        : tr(codex ? "settings.ai.modelDefault" : "settings.ai.localModelPrompt"));
        aiCompletionModelField.setPromptText(
                anthropic
                        ? AiCoordinator.DEFAULT_COMPLETION_MODEL
                        : tr(
                                provider == com.editora.ai.AiProvider.LMSTUDIO
                                        ? "settings.ai.lmstudioCompletionPrompt"
                                        : codex ? "settings.ai.modelDefault" : "settings.ai.localModelPrompt"));
        aiApiKeyField.setPromptText(tr(anthropic ? "settings.ai.apiKeyPrompt" : "settings.ai.localApiKeyPrompt"));
        aiEndpointField.setPromptText(provider.defaultEndpoint());
    }

    VBox aiPage() {
        VBox p = w.page(tr("settings.cat.ai"), tr("settings.ai.subtitle"));
        w.dependsOn(p, aiMasterCheck);
        Card mainCard = w.card(p, null);
        SettingsDependents.bind(aiMasterCheck.selectedProperty(), mainCard.box());
        // A switch row like every other page's enable toggle; what it turns on stays in the (i) tooltip.
        HBox aiEnable = new HBox(
                8, SettingsWindow.infoIcon(tr("settings.ai.actionsTooltip")), SettingsWindow.switchFor(aiCheck));
        aiEnable.setAlignment(Pos.CENTER_RIGHT);
        w.cardRow(
                mainCard,
                Category.AI,
                SettingsWindow.settingRow(aiCheck.getText(), null, aiEnable),
                "ai actions anthropic claude commit message explain rewrite enable");
        aiStatusLabel = new Label(tr("settings.ai.statusUnknown"));
        aiStatusLabel.getStyleClass().add("settings-git-status");
        aiStatusLabel.setWrapText(true);
        aiStatusLabel.setMaxWidth(440);
        Button test = new Button(tr("settings.ai.testConnection"));
        test.setOnAction(e -> refreshAiStatus(true));
        SettingsWindow.keepWidth(test);
        HBox status = new HBox(8, aiStatusLabel, test);
        status.setAlignment(Pos.CENTER_LEFT);
        w.cardRow(mainCard, Category.AI, status, "ai connection status test green red working reachable");
        p.parentProperty()
                .addListener((o, was, now) -> javafx.application.Platform.runLater(() -> refreshAiStatus(false)));
        w.cardRow(
                mainCard,
                Category.AI,
                w.labeledRow(tr("settings.ai.provider"), aiProviderCombo),
                "ai provider anthropic openai local lm studio bionic ollama vllm");
        w.cardRow(
                mainCard,
                Category.AI,
                w.labeledRow(tr("settings.ai.endpoint"), aiEndpointField),
                "ai endpoint url local server lm studio ollama port");
        Label providerNote = SettingsWindow.note(tr("settings.ai.providerNote"));
        providerNote.setWrapText(true);
        providerNote.setMaxWidth(440);
        w.cardRow(mainCard, Category.AI, providerNote, "ai provider local lm studio no api key endpoint");
        w.cardRow(
                mainCard,
                Category.AI,
                w.labeledRow(tr("settings.ai.model"), aiModelField),
                "ai model anthropic claude opus id");
        w.cardRow(
                mainCard,
                Category.AI,
                w.labeledRow(tr("settings.ai.apiKey"), aiApiKeyField),
                "ai api key anthropic token credential");
        aiKeyNote = SettingsWindow.note(apiKeyNote(config.getSettings()));
        aiKeyNote.setWrapText(true);
        aiKeyNote.setMaxWidth(440);
        w.cardRow(
                mainCard,
                Category.AI,
                aiKeyNote,
                "ai api key keychain credential manager environment variable plain text");
        w.checkRow(
                mainCard,
                Category.AI,
                aiInlineCheck,
                null,
                "ai inline ghost completion suggestion copilot autocomplete");
        w.cardRow(
                mainCard,
                Category.AI,
                w.labeledRow(tr("settings.ai.completionModel"), aiCompletionModelField),
                "ai inline completion model haiku fast latency");
        Label inlineNote = SettingsWindow.note(tr("settings.ai.inlineHint"));
        inlineNote.setWrapText(true);
        inlineNote.setMaxWidth(440);
        w.cardRow(mainCard, Category.AI, inlineNote, "ai inline ghost completion tab accept cost");
        Label codexHint = SettingsWindow.note(tr("settings.ai.codexHint"));
        codexHint.setWrapText(true);
        codexHint.setMaxWidth(440);
        w.cardRow(mainCard, Category.AI, codexHint, "ai codex login acp agent subscription setup");
        Label hint = SettingsWindow.note(tr("settings.ai.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        w.cardRow(mainCard, Category.AI, hint, "ai commit message explain rewrite selection anthropic streaming");
        return p;
    }

    void load(Settings settings) {
        mcpCheck.setSelected(settings.isMcpSupport());
        refreshMcpStatus();
        aiMasterCheck.setSelected(settings.isAiEnabled());
        agentCheck.setSelected(settings.isAgentSupport());
        agentClientCombo.setValue(com.editora.agent.AcpAgentRegistry.from(settings.getAgentClient())
                .id());
        for (AgentClientUi a : agentClientUis()) {
            TextField field = agentCommandFields.get(a.id());
            if (field != null) {
                field.setText(a.getCommand().get());
            }
        }
        if (agentCoordinator != null) {
            agentCoordinator.invalidateDetection(); // an agent installed meanwhile is found
        }
        refreshAgentClientStatus();
        agentIncludeContextCheck.setSelected(settings.isAgentIncludeContext());
        aiCheck.setSelected(settings.isAiSupport());
        aiInlineCheck.setSelected(settings.isAiInlineCompletion());
        aiProviderCombo.setValue(
                com.editora.ai.AiProvider.from(settings.getAiProvider()).id());
        syncAiProviderFields();
        javafx.application.Platform.runLater(() -> refreshAiStatus(false)); // only if the AI page is shown
    }

    void syncMcpCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            mcpCheck.setSelected(config.getSettings().isMcpSupport());
        } finally {
            w.loading = prev;
        }
        refreshMcpStatus();
    }

    void syncAgentCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            agentCheck.setSelected(config.getSettings().isAgentSupport());
            agentClientCombo.setValue(
                    com.editora.agent.AcpAgentRegistry.from(config.getSettings().getAgentClient())
                            .id());
        } finally {
            w.loading = prev;
        }
    }

    void syncAiCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            aiCheck.setSelected(config.getSettings().isAiSupport());
            aiInlineCheck.setSelected(config.getSettings().isAiInlineCompletion());
            aiProviderCombo.setValue(
                    com.editora.ai.AiProvider.from(config.getSettings().getAiProvider())
                            .id());
            syncAiProviderFields();
        } finally {
            w.loading = prev;
        }
    }
}
