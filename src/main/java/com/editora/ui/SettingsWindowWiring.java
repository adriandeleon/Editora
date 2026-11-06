package com.editora.ui;

import java.util.List;

import static com.editora.i18n.Messages.tr;

/**
 * Connects a window's {@link SettingsWindow} to the window's features: the actions, probes and suggestions its
 * pages call back into. Each callback resolves the window's state when the page calls it, as it did inline in
 * {@link MainController#init}, which runs this at the same point (after the plugin coordinator is built). Moved
 * out of {@code MainController} for that file's size.
 */
final class SettingsWindowWiring {

    private SettingsWindowWiring() {}

    /** {@code templates} is the window's template registry, built earlier in {@code init}. */
    static void wire(
            MainController mc, SettingsWindow settingsWindow, com.editora.template.TemplateRegistry templates) {
        settingsWindow.setPluginActions(
                mc.pluginCoordinator::browse,
                mc.pluginCoordinator::installFromDisk,
                mc.pluginCoordinator::uninstall,
                mc.pluginCoordinator::enableFromSettings,
                mc.pluginCoordinator::disableFromSettings);
        settingsWindow.setDictionaryActions(
                mc::openTechnicalDictionary, mc::openPersonalDictionary); // Spell Check file links
        settingsWindow.setInstallActions(mc.installCoordinator::installSettingsKey); // Settings "Install…" buttons
        settingsWindow.setInstallServerActions(mc.installCoordinator::installServer); // per-LSP-server Install
        settingsWindow.setOpenUrl(mc::openExternalUrl); // Diagrams' "How to install" docs link
        settingsWindow.setInstallToolActions(id -> mc.installCoordinator.installTools(java.util.List.of(id), ok -> {}));
        settingsWindow.setSnippetManager(mc.snippets); // backs the Settings → Snippets management page
        // Read on each Add click, not now: the Settings window outlives whichever tab is in front.
        settingsWindow.setRunConfigSuggestion(mc::suggestedMainClass);
        settingsWindow.setRunConfigScriptSuggestion(mc::suggestedScriptConfiguration, mc::windowProjectRoot);
        settingsWindow.setTemplateRegistry(templates); // backs the Settings → Templates management page
        settingsWindow.setMcpConfirm(mc::confirmEnableMcp); // security notice before enabling MCP
        settingsWindow.setOnRestart(() -> mc.registry.run("app.restart")); // footer's Restart now
        settingsWindow.setRunCommand(mc.registry::run); // Advanced → Import…, LSP → Python → Choose…
        settingsWindow.setMcpActions(new SettingsWindow.McpActions( // the MCP page's status and buttons
                () -> MainController.mcpServer == null || !MainController.mcpServer.isRunning()
                        ? ""
                        : MainController.mcpServer.url(),
                mc::copyMcpEndpoint,
                () -> {
                    if (MainController.mcpServer != null) { // the buttons only show while it runs
                        MainController.mcpServer.newToken();
                    }
                    mc.setStatus(tr("status.mcp.newToken"));
                }));
        settingsWindow.setRemoteConnect(site -> mc.remoteCoordinator.connect(site)); // Remote page's Connect…
        settingsWindow.setEditDeployments(mc.deployments::editDeployments); // Run on → Deployments…
        settingsWindow.setTrustActions(new SettingsWindow.TrustActions() {
            @Override
            public List<String> trustedRoots() {
                return mc.config.getTrustStore().trustedRoots();
            }

            @Override
            public void revoke(String root) {
                mc.config.getTrustStore().revoke(java.nio.file.Path.of(root));
                mc.saveTrustDecision();
            }

            @Override
            public void revokeAll() {
                mc.config.getTrustStore().revokeAll();
                mc.saveTrustDecision();
            }
        });
        settingsWindow.setRipgrepProbe(mc.searchCoordinator::probeRipgrep); // Settings → Search found/not-found status
        settingsWindow.setAiConnectionProbe(
                mc.aiCoordinator::checkConnection); // Settings → AI Actions green/red connection status
        settingsWindow.setProjectLsp(mc.lspCoordinator); // project scope (User / Project) + LSP override notes
        settingsWindow.setProjectName(() -> mc.windowProject == null ? null : mc.windowProject.name());
        settingsWindow.setOnKeymapChanged(mc.editorSettings::reloadKeymap); // picker/combo → live keymap switch
        settingsWindow.setShortcutActions(new SettingsWindow.ShortcutActions() {
            @Override
            public java.util.List<SettingsWindow.Shortcut> rows() {
                return mc.editorSettings.shortcutRows();
            }

            @Override
            public java.util.List<com.editora.command.KeybindingEdits.Conflict> conflicts(
                    String chordSeq, String commandId) {
                return com.editora.command.KeybindingEdits.conflicts(mc.keymap.bindings(), chordSeq, commandId);
            }

            @Override
            public void rebind(String commandId, String chordSeq) {
                mc.editorSettings.rebindShortcut(commandId, chordSeq);
            }

            @Override
            public void reset(String commandId) {
                mc.editorSettings.resetShortcut(commandId);
            }

            @Override
            public void resetAll() {
                mc.editorSettings.resetAllShortcuts();
            }

            @Override
            public String display(String chordSeq) {
                return mc.keymap.display(chordSeq);
            }

            @Override
            public void clear(String commandId) {
                mc.editorSettings.clearShortcut(commandId);
            }

            @Override
            public java.util.List<com.editora.command.KeybindingEdits.Conflict> takenDefaults(String commandId) {
                return mc.editorSettings.takenDefaults(commandId);
            }

            @Override
            public void forget(String commandId) {
                mc.editorSettings.forgetShortcut(commandId);
            }
        });
        settingsWindow.setRunConfigsChangedHandler(
                mc.runConfigurations
                        ::refreshRunConfigs); // Run Configurations page edits → repopulate the toolbar selector
        settingsWindow.setMacrosChangedHandler(
                mc::refreshSavedMacroCommandsAllWindows); // Macros page edits → re-register commands everywhere
        settingsWindow.setAgentCoordinator(mc.agentCoordinator); // AI Agent page: per-client status + combo
        // Toolbar page ↔ the (later-constructed) ToolbarCoordinator; the lambdas resolve the field lazily.
        settingsWindow.setToolbarActions(new SettingsWindow.ToolbarActions() {
            @Override
            public java.util.List<String> current() {
                return mc.toolbarCoordinator.effectiveLayout();
            }

            @Override
            public void apply(java.util.List<String> layout) {
                mc.toolbarCoordinator.setLayout(layout);
            }

            @Override
            public void restoreDefault() {
                mc.toolbarCoordinator.restoreDefault();
            }
        });
    }
}
