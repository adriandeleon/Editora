package com.editora.ui;

/**
 * What {@link PluginCoordinator} needs from a window (open-path + the plugins' error dialog), resolved from
 * {@link MainController} when called. It lived inside {@code MainController} as an anonymous class; it is a file
 * of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class PluginOpsHost implements PluginCoordinator.Ops {

    private final MainController mc;

    PluginOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openPath(java.nio.file.Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void showError(String summary, String detail) {
        // Owned by Settings while the user works there; titled "Plugins", not "Git".
        javafx.stage.Window settings = mc.settingsWindow.stageIfShowing();
        PluginDialogs.error(settings != null ? settings : mc.stage, summary, detail);
    }
}
