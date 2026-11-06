package com.editora.ui;

/**
 * What {@link InstallCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class InstallOpsHost implements InstallCoordinator.Ops {

    private final MainController mc;

    InstallOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public java.nio.file.Path configDir() {
        return mc.config.getConfigDir();
    }

    @Override
    public void stopServer(String serverId) {
        mc.lspCoordinator.stopServer(serverId);
    }

    @Override
    public boolean lspAvailable(String serverId) {
        return mc.lspCoordinator.isServerAvailable(serverId) && mc.lspCoordinator.serverTrouble(serverId) == null;
    }

    @Override
    public boolean dapAvailable(String language) {
        return "java".equals(language)
                ? mc.dapManager.isJavaDebugInstalled()
                : mc.dapManager.isLanguageAvailable(language);
    }

    @Override
    public boolean mmdcAvailable() {
        return mc.mermaid.mmdcAvailable();
    }

    @Override
    public boolean typstCliAvailable() {
        return mc.typst.isTypstCliAvailable();
    }

    @Override
    public boolean toolAvailable(String toolId) {
        return mc.lspCoordinator.bashToolFound(toolId);
    }

    @Override
    public void reapplyToolSupport() {
        mc.lspManager.invalidateDetection();
        mc.lspCoordinator.applySupport();
        mc.debugCoordinator.applySupport();
        mc.mermaid.applySupport();
        mc.diagram.applySupport();
        mc.typst.applySupport();
        mc.requestSave(); // persist a resolved command (e.g. the installed jdtls launcher path)
        if (mc.settingsWindow != null) {
            mc.settingsWindow.refreshDetectionStatus(); // flip the Settings Install buttons to "Installed"
        }
        mc.installPrompts.maybeOfferInstall(
                mc.activeBuffer()); // re-evaluate the editor install banner after re-detection
    }
}
