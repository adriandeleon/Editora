package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RunConfigurationCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class RunConfigurationHost implements RunConfigurationCoordinator.Host {

    private final MainController mc;

    RunConfigurationHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public Button runConfigStopButton() {
        return mc.runConfigStopButton;
    }

    @Override
    public Button runConfigDebugButton() {
        return mc.runConfigDebugButton;
    }

    @Override
    public Button runConfigRunButton() {
        return mc.runConfigRunButton;
    }

    @Override
    public javafx.scene.control.ComboBox<com.editora.config.RunConfiguration> runConfigCombo() {
        return mc.runConfigCombo;
    }

    @Override
    public Stage stage() {
        return mc.stage;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
    }

    @Override
    public CommandRegistry registry() {
        return mc.registry;
    }

    @Override
    public SettingsWindow settingsWindow() {
        return mc.settingsWindow;
    }

    @Override
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public DebugCoordinator debugCoordinator() {
        return mc.debugCoordinator;
    }

    @Override
    public String homeCollapsed(String full) {
        return com.editora.config.PathDisplay.collapseHome(full);
    }

    @Override
    public List<BuildCoordinator> buildCoordinators() {
        return mc.buildCoordinators;
    }

    @Override
    public RunCoordinator runCoordinator() {
        return mc.runCoordinator;
    }

    @Override
    public String readGradleBuildFile(java.nio.file.Path root) {
        return com.editora.build.SpringBoot.readGradleBuildFile(root);
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
    }

    @Override
    public void setError(String message) {
        mc.setError(message);
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public boolean save(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer);
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public String programArgsFor(@Nullable Path path) {
        return mc.programArgsFor(path);
    }

    @Override
    public @Nullable String suggestedMainClass() {
        return mc.suggestedMainClass();
    }

    @Override
    public com.editora.config.@Nullable RunConfiguration suggestedScriptConfiguration() {
        return mc.suggestedScriptConfiguration();
    }

    @Override
    public @Nullable Path windowProjectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public void promptText(String title, String label, @Nullable String initial, Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }
}
