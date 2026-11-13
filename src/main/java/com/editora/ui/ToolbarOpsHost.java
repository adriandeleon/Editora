package com.editora.ui;

/**
 * What {@link ToolbarCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class ToolbarOpsHost implements ToolbarCoordinator.Ops {

    private final MainController mc;

    ToolbarOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public javafx.scene.control.ToolBar toolBar() {
        return mc.toolBar;
    }

    @Override
    public java.util.Map<String, javafx.scene.Node> baseWidgets() {
        return mc.toolbar.toolbarBaseWidgets();
    }

    @Override
    public void appendFixedTail() {
        mc.toolbar.appendFixedTail();
    }

    @Override
    public void afterRebuild() {
        mc.chrome.applySimpleMode(); // hides the curated Simple-mode buttons + collapses orphaned separators
        mc.toolbar.refreshCommandState(); // the buttons just built start in their command's state
    }

    @Override
    public void trackButton(javafx.scene.control.Button button, String commandId) {
        mc.toolbar.track(button, commandId);
    }

    @Override
    public void openSettingsToolbarPage() {
        mc.settingsWindow.showToolbar(mc.stage);
    }

    @Override
    public void broadcastToolbarChanged() {
        mc.onSettingsApplied(mc.config.getSettings()); // re-applies + rebuilds every window's toolbar
    }
}
