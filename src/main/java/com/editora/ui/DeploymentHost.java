package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Deployment;
import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What {@link DeploymentCoordinator} needs from a window, resolved from {@link MainController} when called. A
 * file of its own, like {@link WindowCommandHost}, because of {@code MainController}'s size. Also owns the
 * window's {@link DeploymentsWindow}.
 */
final class DeploymentHost implements DeploymentCoordinator.Host {

    private final MainController mc;
    private @Nullable DeploymentsWindow editor;

    DeploymentHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
    }

    /** The window's project root when it is a folder of this machine: a remote project needs no deployment. */
    @Override
    public @Nullable Path projectRoot() {
        Path root = mc.windowProjectRoot();
        return root != null && root.getFileSystem() == java.nio.file.FileSystems.getDefault() ? root : null;
    }

    @Override
    public void hold(com.editora.vfs.RemoteConnection conn, Consumer<Path> onReady) {
        mc.remoteCoordinator.holdForDeployment(conn, onReady);
    }

    @Override
    public javafx.stage.Window window() {
        return mc.stage;
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
    public @Nullable RunConfiguration selectedRunConfiguration() {
        String name = mc.config.getWorkspaceState().getSelectedRunConfig();
        for (RunConfiguration c : mc.config.getWorkspaceState().getRunConfigurations()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public void editDeployments(@Nullable String name) {
        if (editor == null) {
            editor = new DeploymentsWindow(mc.config, mc.config::getConnections, mc.deployments::deployNamed, () -> {
                if (mc.settingsWindow != null) {
                    mc.settingsWindow.refreshRunConfigDeployments();
                }
                mc.runConfigurations.refreshRunConfigs(); // a renamed deployment renames configurations' Run on
            });
        }
        editor.show(name, mc.stage);
    }

    @Override
    public void compare(Path local, Path deployed, String hostLabel) {
        mc.diffCoordinator.diffWithDeployed(local, deployed, hostLabel);
    }

    @Override
    public void openTerminal(Path dir) {
        mc.terminalCoordinator.openAt(dir);
    }

    @Override
    public void pick(List<Deployment> deployments, Consumer<Deployment> onPick) {
        QuickOpen<Deployment> picker = new QuickOpen<>(
                tr("deploy.pick.title"),
                tr("deploy.pick.prompt"),
                () -> deployments,
                Deployment::name,
                Deployment::displayTarget,
                onPick);
        picker.setOverlayHost(mc.overlayHost);
        picker.show(mc.stage);
    }
}
