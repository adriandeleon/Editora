package com.editora.ui;

import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;

/**
 * What {@link InstallPromptCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class InstallPromptHost implements InstallPromptCoordinator.Host {

    private final MainController mc;

    InstallPromptHost(MainController mc) {
        this.mc = mc;
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
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public InstallCoordinator installCoordinator() {
        return mc.installCoordinator;
    }

    @Override
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public MermaidCoordinator mermaid() {
        return mc.mermaid;
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
    public void turnOnLsp() {
        if (!mc.config.getSettings().isLspSupport()) {
            mc.toggleLsp();
        }
    }

    @Override
    public void openLspSettings() {
        mc.settingsWindow.showDoctorTarget("lsp", mc.stage);
    }
}
