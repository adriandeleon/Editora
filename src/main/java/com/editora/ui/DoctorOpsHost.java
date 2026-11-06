package com.editora.ui;

import com.editora.build.BuildTool;
import org.jspecify.annotations.Nullable;

/**
 * What {@link DoctorCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class DoctorOpsHost implements DoctorCoordinator.Ops {

    private final MainController mc;

    DoctorOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public com.editora.mermaid.MermaidService mermaidService() {
        return mc.mermaid.service();
    }

    @Override
    public com.editora.diagram.DiagramService diagramService() {
        return mc.diagram.service();
    }

    @Override
    public com.editora.typst.TypstService typstService() {
        return mc.typst.service();
    }

    @Override
    public boolean gitFeatureEnabled() {
        return mc.git.isEnabled();
    }

    @Override
    public boolean lspFeatureEnabled() {
        return mc.lspEnabled();
    }

    @Override
    public boolean debugFeatureEnabled() {
        return mc.debugCoordinator != null && mc.debugCoordinator.debugSupportEnabled();
    }

    @Override
    public boolean javaDebugFromServer() {
        return mc.lspManager.javaDebugCommandsAvailable();
    }

    @Override
    public java.util.List<String> lspServerIds() {
        return LspServerCommands.serverIds();
    }

    @Override
    public boolean lspServerEnabled(String serverId) {
        return mc.lspCoordinator.serverEnabled(serverId);
    }

    @Override
    public java.util.List<String> lspServerArgv(String serverId) {
        return mc.lspCoordinator.serverArgv(serverId);
    }

    @Override
    public LspCoordinator.@Nullable ServerTrouble lspServerFailure(String serverId) {
        return mc.lspCoordinator.serverTrouble(serverId);
    }

    @Override
    public void installServer(String serverId, boolean update, java.util.function.Consumer<Boolean> onDone) {
        mc.installCoordinator.installServer(serverId, update, onDone);
    }

    @Override
    public void installLang(
            com.editora.install.InstallCatalog.Lang lang,
            @Nullable String updateStep,
            java.util.function.Consumer<Boolean> onDone) {
        mc.installCoordinator.installSupport(lang, updateStep, onDone);
    }

    @Override
    public void installTypstCli(java.util.function.Consumer<Boolean> onDone) {
        mc.installCoordinator.installTypstCli(onDone);
    }

    @Override
    public void installTool(String toolId, boolean update, java.util.function.Consumer<Boolean> onDone) {
        mc.installCoordinator.installTools(java.util.List.of(toolId), update, onDone);
    }

    @Override
    public String bashTool(String toolId) {
        return mc.lspCoordinator.bashToolCommand(toolId);
    }

    @Override
    public java.nio.file.@Nullable Path projectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public java.nio.file.@Nullable Path buildRoot(BuildTool tool) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool) {
                return c.markerRoot();
            }
        }
        return null;
    }

    @Override
    public @Nullable String buildExecutableSummary(BuildTool tool) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool) {
                return c.executableSummary();
            }
        }
        return null;
    }

    @Override
    public String pythonInterpreterChoice() {
        return mc.config.getWorkspaceState().getPythonInterpreter();
    }

    @Override
    public void openSettingsFor(String settingsKey) {
        mc.settingsWindow.showDoctorTarget(settingsKey, mc.stage);
    }

    @Override
    public com.editora.plugin.@Nullable PluginManager plugins() {
        return mc.pluginManager;
    }

    @Override
    public java.util.function.Supplier<com.editora.run.PythonInterpreters.PythonInterpreter> pythonInterpreter() {
        return mc.lspCoordinator.pythonInterpreterForDoctor();
    }

    @Override
    public boolean pythonInterpreterElsewhere() {
        return mc.lspCoordinator.pythonInterpreterElsewhereForDoctor();
    }

    @Override
    public boolean lspServerCommandSet(String serverId) {
        return mc.lspCoordinator.serverCommandSet(serverId);
    }

    @Override
    public void runCommand(String commandId) {
        mc.registry.run(commandId);
    }

    @Override
    public void reveal(java.nio.file.Path path) {
        mc.revealInFileManager(path, java.nio.file.Files.isDirectory(path), true);
    }
}
