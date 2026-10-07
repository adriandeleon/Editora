package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the Commit tool window's buttons, rows and menus do: each {@link GitPanel.Actions} call is handed to
 * the Git engine ({@link GitCoordinator}), the diff viewer ({@link DiffCoordinator}) or the file workflows.
 * The panel itself never runs git. (Moved out of {@code MainController}, which only constructs it.)
 */
final class GitPanelActions implements GitPanel.Actions {

    private final GitCoordinator git;
    private final DiffCoordinator diffs;
    private final FileWorkflowCoordinator files;

    GitPanelActions(GitCoordinator git, DiffCoordinator diffs, FileWorkflowCoordinator files) {
        this.git = git;
        this.diffs = diffs;
        this.files = files;
    }

    @Override
    public void open(String path) {
        if (git.repoRoot() != null) {
            files.openPath(git.repoRoot().resolve(path));
        }
    }

    @Override
    public void stage(List<String> paths) {
        git.gitStagePaths(paths);
    }

    @Override
    public void unstage(List<String> paths) {
        git.gitUnstagePaths(paths);
    }

    @Override
    public void discard(List<String> tracked, List<String> untracked) {
        git.discardChanges(tracked, untracked);
    }

    @Override
    public void stageAll() {
        git.gitStageAll();
    }

    @Override
    public void commit(String message, Consumer<Boolean> onDone) {
        git.gitCommit(message, onDone);
    }

    @Override
    public void commit(GitPanel.CommitRequest request, Consumer<Boolean> onDone) {
        git.commits().commit(request, onDone);
    }

    @Override
    public void attached(GitPanel panel) {
        git.commits().attach(panel);
    }

    @Override
    public void amendTarget(Consumer<com.editora.git.GitService.HeadCommit> onResult) {
        git.commits().amendTarget(onResult);
    }

    @Override
    public boolean signOff() {
        return git.commits().signOff();
    }

    @Override
    public void setSignOff(boolean signOff) {
        git.commits().setSignOff(signOff);
    }

    @Override
    public List<String> messageHistory() {
        return git.commits().messageHistory();
    }

    @Override
    public void unstageAll() {
        git.gitUnstageAll();
    }

    @Override
    public void push() {
        git.gitPush();
    }

    @Override
    public void refresh() {
        git.invalidateCaches();
        git.afterMutation();
    }

    @Override
    public void review(boolean staged) {
        diffs.reviewGitChanges(staged);
    }

    @Override
    public void diff(String path, boolean staged) {
        diffs.diffGitPanelFile(path, staged);
    }

    /**
     * Opens the three-way resolver for a conflicted path: the file is opened (or its tab selected) and, once
     * its text is in the buffer, {@code merge.resolve} runs on it — the same entry point as the command.
     */
    @Override
    public void resolve(String path) {
        Path root = git.repoRoot();
        if (root != null) {
            files.openThen(root.resolve(path), diffs::resolveConflicts);
        }
    }

    @Override
    public void acceptSide(List<String> paths, boolean ours) {
        git.acceptConflictSide(paths, ours);
    }

    @Override
    public void continueOperation() {
        git.continueOperation();
    }

    @Override
    public void skipOperation() {
        git.skipOperation();
    }

    @Override
    public void abortOperation() {
        git.abortOperation();
    }
}
