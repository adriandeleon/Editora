package com.editora.ui;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * What {@link TodoCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class TodoOpsHost implements TodoCoordinator.Ops {

    private final MainController mc;

    TodoOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public java.nio.file.@Nullable Path projectRoot() {
        return (mc.windowProject != null && mc.projectsEnabled()) ? mc.windowProject.rootPath() : null;
    }

    @Override
    public void openMatch(java.nio.file.Path file, int line, int col) {
        mc.fileWorkflows.openPath(file);
        Platform.runLater(() -> mc.sessions.gotoInFile(file, line, col));
    }

    @Override
    public boolean isToolWindowOpen() {
        return mc.todoToolWindow != null && mc.toolWindows.isOpen(mc.todoToolWindow);
    }

    @Override
    public void toggleToolWindow() {
        mc.toolWindows.toggle(mc.todoToolWindow);
    }

    @Override
    public String homeCollapsed(String absolutePath) {
        return com.editora.config.PathDisplay.collapseHome(absolutePath);
    }

    @Override
    public void applyLineEdit(
            java.nio.file.Path file, int line, String expectedLine, String newLine, Runnable afterApply) {
        mc.applyTodoLineEdit(file, line, expectedLine, newLine, afterApply);
    }
}
