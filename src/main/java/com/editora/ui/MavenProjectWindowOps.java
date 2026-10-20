package com.editora.ui;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The window hooks the New Maven Project wizard and Update Versions need, over one {@link MainController}:
 * where to start, the open buffer of a pom, registering and opening a generated project, the Maven tool
 * window's popup and custom prompt for a clicked pom. A named class rather than an anonymous one in the
 * controller so {@code MainController} stays under the source-size limit.
 */
final class MavenProjectWindowOps implements MavenProjectCoordinator.Ops {

    private final MainController c;

    MavenProjectWindowOps(MainController controller) {
        this.c = controller;
    }

    @Override
    public java.nio.file.@Nullable Path defaultParentDir() {
        return c.templateActions.defaultNewDir();
    }

    @Override
    public @Nullable EditorBuffer openBuffer(java.nio.file.Path file) {
        return MainController.bufferOf(c.tabForPath(file));
    }

    @Override
    public void openProject(
            java.nio.file.Path root, String name, com.editora.maven.GeneratedProject.@Nullable MainClass main) {
        if (projectsEnabled()) { // else still generated on disk; just not registered as a project
            NewMavenProjectSession.open(c.projects, c.config, c.windowManager, root, name, main);
        }
    }

    @Override
    public boolean projectsEnabled() {
        return c.projectsEnabled() && c.windowManager != null;
    }

    @Override
    public java.nio.file.@Nullable Path currentProjectRoot() {
        return c.windowProjectRoot();
    }

    @Override
    public void openPath(java.nio.file.Path file) {
        c.fileWorkflows.openPath(file);
    }

    @Override
    public void openConsole() {
        if (c.buildOutputToolWindow != null) {
            c.toolWindows.open(c.buildOutputToolWindow, true);
        }
    }

    @Override
    public com.editora.command.KeymapManager keymap() {
        return c.keymap;
    }

    @Override
    public boolean confirmArchetype(com.editora.maven.MavenArchetype archetype) {
        return consentFor(archetype) != MavenProjectCoordinator.Consent.CANCEL;
    }

    @Override
    public MavenProjectCoordinator.Consent consentFor(com.editora.maven.MavenArchetype archetype) {
        return NewMavenProjectSession.confirmArchetype(c.stage, archetype);
    }

    @Override
    public void refreshProjectTree() {
        if (c.projectPanel != null) {
            c.projectPanel.refreshTree();
        }
    }

    @Override
    public void showActionsAt(java.nio.file.@Nullable Path root) {
        BuildCoordinator maven = c.buildCoordinator(BuildTool.MAVEN);
        if (maven != null) {
            maven.showActionsAt(root);
        }
    }

    @Override
    public void runCustomAt(java.nio.file.@Nullable Path root) {
        BuildCoordinator maven = c.buildCoordinator(BuildTool.MAVEN);
        if (maven != null) {
            maven.runCustomAt(root);
        }
    }
}
