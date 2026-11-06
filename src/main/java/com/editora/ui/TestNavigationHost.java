package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link TestNavigationCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class TestNavigationHost implements TestNavigationCoordinator.Host {

    private final MainController mc;

    TestNavigationHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public EditorArea editorArea() {
        return mc.editorArea;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
    }

    @Override
    public ProjectManager projects() {
        return mc.projects;
    }

    @Override
    public @Nullable Project windowProject() {
        return mc.windowProject;
    }

    @Override
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public com.editora.lsp.LspManager lspManager() {
        return mc.lspManager;
    }

    @Override
    public ToolWindow testResultsToolWindow() {
        return mc.testResultsToolWindow;
    }

    @Override
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public FileWorkflowCoordinator fileWorkflows() {
        return mc.fileWorkflows;
    }

    @Override
    public WindowSessionCoordinator sessions() {
        return mc.sessions;
    }

    @Override
    public CoordinatorHost coordinatorHost() {
        return mc.coordinatorHost;
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
    public DebugCoordinator debugCoordinator() {
        return mc.debugCoordinator;
    }

    @Override
    public TestRunCoordinator testRunCoordinator() {
        return mc.testRunCoordinator;
    }

    @Override
    public LspCoordinator lspCoordinator() {
        return mc.lspCoordinator;
    }

    @Override
    public boolean isLocalBuffer(EditorBuffer b) {
        return mc.isLocalBuffer(b);
    }

    @Override
    public boolean lspEnabled() {
        return mc.lspEnabled();
    }

    @Override
    public void openAndGoto(Path file, int line0, int col0) {
        mc.openAndGoto(file, line0, col0);
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public @Nullable EditorBuffer ensureBuffer(@Nullable Tab tab) {
        return mc.ensureBuffer(tab);
    }

    @Override
    public @Nullable Tab tabForPath(Path file) {
        return mc.tabForPath(file);
    }
}
