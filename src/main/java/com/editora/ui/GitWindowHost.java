package com.editora.ui;

import java.nio.file.Path;

import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link GitWindowCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class GitWindowHost implements GitWindowCoordinator.Host {

    private final MainController mc;

    GitWindowHost(MainController mc) {
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
    public StatusBar statusBar() {
        return mc.statusBar;
    }

    @Override
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public GitPanel gitPanel() {
        return mc.gitPanel();
    }

    @Override
    public ToolWindow commitToolWindow() {
        return mc.commitToolWindow;
    }

    @Override
    public GitLogPanel gitLogPanel() {
        return mc.gitLogPanel();
    }

    @Override
    public GitLogPanel.Actions gitLogOps() {
        return mc.gitLogOps;
    }

    @Override
    public ToolWindow gitLogToolWindow() {
        return mc.gitLogToolWindow;
    }

    @Override
    public GitHubPanel githubPanel() {
        return mc.githubPanel();
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
    }

    @Override
    public DiffCoordinator diffCoordinator() {
        return mc.diffCoordinator;
    }

    @Override
    public GitHubCoordinator github() {
        return mc.github;
    }

    @Override
    public @Nullable EditorBuffer openBufferFor(Path target) {
        return mc.openBufferFor(target);
    }

    @Override
    public void reloadAllFromDiskSilently() {
        mc.reloadAllFromDiskSilently();
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
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }
}
