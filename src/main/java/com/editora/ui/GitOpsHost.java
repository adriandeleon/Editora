package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link GitCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class GitOpsHost implements GitCoordinator.WindowOps {

    private final MainController mc;

    GitOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void setStatusBarGitEnabled(boolean enabled) {
        mc.statusBar.setGitEnabled(enabled);
    }

    @Override
    public void setStatusBarBranch(@Nullable String branch, int ahead, int behind) {
        mc.statusBar.setGitBranch(branch, ahead, behind);
    }

    @Override
    public void setCommitWindowAvailable(boolean available) {
        mc.toolWindows.setAvailable(
                mc.commitToolWindow, available && mc.git.windowsAllowed(mc.editorArea.selectedTab()));
        // Git's answer to "are we in a repo" has just landed, and it arrives asynchronously well after
        // the window (and its menu) were built — this is the signal that ungreys the Git menu.
        mc.refreshMenuEnablement();
    }

    @Override
    public void setGitLogWindowAvailable(boolean available) {
        // Entering or leaving a repo also decides whether the shared Output console is reachable — Git
        // writes its command transcripts there. Hooked here because this runs on every applyGitState
        // (tab switch / focus / save / mutation), which is exactly when the repo context can change.
        mc.refreshBuildOutputAvailability();
        mc.toolWindows.setAvailable(
                mc.gitLogToolWindow, available && mc.git.windowsAllowed(mc.editorArea.selectedTab()));
    }

    @Override
    public void setGitPanelStatus(com.editora.git.@Nullable GitStatus status) {
        mc.lastGitStatus = status; // a panel not built yet shows it when it is
        if (mc.gitPanel != null) {
            mc.gitPanel.setStatus(status);
        }
    }

    @Override
    public void setGitOperation(com.editora.git.GitOperation operation, int conflicts) {
        mc.statusBar.setGitOperation(operation);
        mc.lastGitOperation = operation;
        if (mc.gitPanel != null) {
            mc.gitPanel.setOperation(operation);
        }
    }

    @Override
    public void setGitRefusal(String reason) {
        mc.statusBar.setGitRefusal(reason);
        mc.lastGitRefusal = reason;
        if (mc.gitPanel != null) {
            mc.gitPanel.setRefusal(reason);
        }
    }

    @Override
    public boolean commitFromPanel() {
        return mc.gitPanel().commitNow();
    }

    @Override
    public void setProjectGitStatus(java.util.Map<java.nio.file.Path, com.editora.git.GitFileStatus> byPath) {
        mc.projectGitStatus = byPath; // a panel not built yet shows it when it is
        if (mc.projectPanel != null) {
            mc.projectPanel.setGitStatus(byPath);
        }
        TabGitStatus.apply(mc.editorArea.tabs(), byPath); // tab titles take the same status colours
    }

    @Override
    public void refreshOpenDiffs() {
        mc.diffCoordinator.refreshOpenDiffs();
    }

    @Override
    public @Nullable Path projectRoot() {
        // This window's project, not the ProjectManager's active one (the last-focused window's): with no
        // tab open, the Git context is the project root, and a window left behind another project's window
        // used to declare its own repository "not a Git repository".
        return mc.windowProjectRoot();
    }

    @Override
    public void invalidatePendingWrite(Path file) {
        mc.fileWorkflows.invalidatePendingWrite(file);
    }

    @Override
    public void invalidatePendingWritesInOtherWindows(Path root, List<String> pathspecs) {
        if (mc.windowManager != null) mc.windowManager.invalidatePendingGitWrites(mc, root, pathspecs);
    }

    @Override
    public boolean errorInAnotherWindow(String message) { // a git failure after this window closed (A204)
        return mc.windowManager != null && mc.windowManager.errorInAnotherWindow(mc, message);
    }

    @Override
    public void reloadAllFromDiskSilently() {
        if (mc.windowManager != null) mc.windowManager.reloadAllFromDiskSilentlyAcrossWindows();
        else mc.reloadAllFromDiskSilently();
    }

    @Override
    public void captureBeforeDelete(List<Path> files, java.util.function.Consumer<Boolean> completion) {
        mc.historyCoordinator.captureAllBeforeDelete(files, completion);
    }

    @Override
    public boolean saveBeforeGit(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer) && !buffer.isDirty();
    }

    @Override
    public void openCommitWindow() {
        mc.toolWindows.open(mc.commitToolWindow);
    }

    @Override
    public void focusCommitMessage() {
        Platform.runLater(() -> mc.gitPanel().focusCommitMessage());
    }

    @Override
    public com.editora.command.KeymapManager keymap() {
        return mc.keymap;
    }

    @Override
    public void syncBlameCheck() {
        mc.settingsWindow.syncGitBlameCheck();
    }

    @Override
    public void openCommitFileDiff(String hash, String repoRel, @Nullable String origRepoRel) {
        mc.diffCoordinator.diffCommitFile(hash, repoRel, origRepoRel);
    }

    @Override
    public void openPath(@Nullable Path file) {
        mc.fileWorkflows.openPath(file);
    }
}
