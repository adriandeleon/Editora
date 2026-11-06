package com.editora.ui;

import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link TemplateCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class TemplateHost implements TemplateCoordinator.Host {

    private final MainController mc;

    TemplateHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public FileWorkflowCoordinator fileWorkflows() {
        return mc.fileWorkflows;
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
    public KeymapManager keymap() {
        return mc.keymap;
    }

    @Override
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public ProjectPanel projectPanel() {
        return mc.projectPanel;
    }

    @Override
    public ProjectManager projects() {
        return mc.projects;
    }

    @Override
    public @Nullable Project windowProject() {
        return mc.enabledWindowProject();
    }

    @Override
    public @Nullable WindowManager windowManager() {
        return mc.windowManager;
    }

    @Override
    public boolean projectsEnabled() {
        return mc.projectsEnabled();
    }

    @Override
    public String homeCollapsed(String full) {
        return com.editora.config.PathDisplay.collapseHome(full);
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
    public Tab addBuffer(EditorBuffer buffer) {
        return mc.addBuffer(buffer);
    }

    @Override
    public Tab addBuffer(EditorBuffer buffer, boolean select) {
        return mc.addBuffer(buffer, select);
    }

    @Override
    public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
        return mc.addBuffer(buffer, select, resolvePathSettings);
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }
}
