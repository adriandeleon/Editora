package com.editora.ui;

import java.nio.file.Path;

import com.editora.config.Project;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RemoteCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class RemoteOpsHost implements RemoteCoordinator.Ops {

    private final MainController mc;

    RemoteOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public com.editora.command.KeymapManager keymap() {
        return mc.keymap;
    }

    @Override
    public void openPath(Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public @Nullable Project windowProject() {
        return mc.windowProject;
    }

    @Override
    public boolean projectsEnabled() {
        return mc.projectsEnabled() && mc.windowManager != null;
    }

    @Override
    public com.editora.config.ProjectManager projects() {
        return mc.projects;
    }

    @Override
    public void openRemoteProject(
            Project project,
            com.editora.vfs.RemoteConnection conn,
            com.editora.vfs.RemoteFileSystems.@Nullable LiveConnection live,
            char @Nullable [] secret) {
        if (mc.windowManager != null) { // null only before the window is placed
            mc.windowManager.openRemoteProject(project, conn, live, secret);
        }
        mc.refreshProjectPanelList();
    }

    @Override
    public ProjectPanel projectPanel() {
        return mc.projectPanel;
    }

    @Override
    public void restoreSessionTabs() {
        mc.sessions.reopenUnreachable();
    }

    @Override
    public boolean remoteExecAllowed(String id) {
        return mc.config.remoteExecAllowed(id);
    }

    @Override
    public void allowRemoteExec(String id) {
        mc.config.allowRemoteExec(id);
    }

    @Override
    public java.util.Map<String, String> remoteTools(String id) {
        return mc.config.remoteTools(id);
    }

    @Override
    public void remoteHostConnected(String authority) {
        mc.lspCoordinator.remoteHostConnected(authority);
    }

    @Override
    public void remoteExecChanged() {
        mc.toolsMoved();
    }

    @Override
    public void setProjectRoot(@Nullable Path root) {
        mc.setProjectRoot(root);
    }

    @Override
    public void openProjectToolWindow() {
        mc.toolWindows.open(mc.projectToolWindow);
    }

    @Override
    public @Nullable Path activeProjectRoot() {
        return mc.windowProjectRoot(); // this window's, not the ProjectManager's active (last-opened) one
    }

    @Override
    public void reportError(String summary, String detail) {
        mc.git.gitError(summary, detail);
    }

    @Override
    public java.util.List<com.editora.vfs.RemoteConnection> connections() {
        return mc.config.getConnections();
    }

    @Override
    public void putConnection(com.editora.vfs.RemoteConnection conn) {
        mc.config.putConnection(conn);
    }

    @Override
    public void removeConnection(String id) {
        mc.config.removeConnection(id);
    }

    @Override
    public void invalidatePendingWrite(@Nullable Path path) {
        mc.fileWorkflows.invalidatePendingWrite(path);
    }
}
