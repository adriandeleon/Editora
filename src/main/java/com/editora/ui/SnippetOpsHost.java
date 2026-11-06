package com.editora.ui;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * What {@link SnippetCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class SnippetOpsHost implements SnippetCoordinator.Ops {

    private final MainController mc;

    SnippetOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openPath(Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void showPicker(String title, Runnable onCancelled) {
        mc.navigation.showSnippetPalette(title, onCancelled, mc.stage);
    }

    @Override
    public @Nullable Path projectRoot() {
        // This window's project, not the ProjectManager's active one (the last-focused window's): it is
        // what $WORKSPACE_FOLDER and $WORKSPACE_NAME expand to in this window's snippets.
        return mc.windowProjectRoot();
    }

    @Override
    public void broadcast() {
        if (mc.windowManager != null) mc.windowManager.broadcastSnippetsChanged(mc);
    }

    @Override
    public StatusBar statusBar() {
        return mc.statusBar;
    }

    @Override
    public SettingsWindow settingsWindow() {
        return mc.settingsWindow;
    }
}
