package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link HistoryCoordinator} needs from a window beyond the configuration ({@link HistoryConfigOps}),
 * resolved from {@link MainController} when called. It lived inside {@code MainController} as an anonymous
 * class; it is a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class HistoryOpsHost extends HistoryConfigOps {

    private final MainController mc;

    HistoryOpsHost(MainController mc) {
        super(mc.config);
        this.mc = mc;
    }

    @Override
    public void setToolWindowAvailable(boolean available) {
        mc.toolWindows.setAvailable(mc.fileHistoryToolWindow, available);
    }

    @Override
    public void openToolWindow() {
        mc.toolWindows.open(mc.fileHistoryToolWindow);
    }

    @Override
    public void openPath(java.nio.file.Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void refreshProjectTree() {
        mc.refreshProjectTreeIfBuilt();
    }

    @Override
    public String currentTextOf(java.nio.file.Path file) {
        return mc.gitWindows.currentTextOf(file);
    }

    @Override
    public @Nullable EditorBuffer openBufferFor(java.nio.file.Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public void historyChanged() {
        if (mc.windowManager != null) {
            mc.windowManager.localHistoryChanged(mc);
        }
    }

    @Override
    public java.nio.file.@Nullable Path projectRoot() {
        return mc.windowProject != null && mc.projectsEnabled() ? mc.windowProject.rootPath() : null;
    }
}
