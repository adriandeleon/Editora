package com.editora.ui;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * The Project tree's per-file context-menu actions (Local History, Git history, compare, annotate, stage,
 * revert, .gitignore), each a thin delegation to the window's coordinators. Built with the Project panel
 * on its first open (see {@code MainController.projectPanel()}).
 */
final class ProjectTreeFileActions implements ProjectPanel.FileActions {

    private final GitCoordinator git;
    private final GitWindowCoordinator gitWindows;
    private final DiffCoordinator diff;
    private final HistoryCoordinator history;
    private final FileWorkflowCoordinator fileWorkflows;
    private final BooleanSupplier localHistoryEnabled;

    ProjectTreeFileActions(
            GitCoordinator git,
            GitWindowCoordinator gitWindows,
            DiffCoordinator diff,
            HistoryCoordinator history,
            FileWorkflowCoordinator fileWorkflows,
            BooleanSupplier localHistoryEnabled) {
        this.git = git;
        this.gitWindows = gitWindows;
        this.diff = diff;
        this.history = history;
        this.fileWorkflows = fileWorkflows;
        this.localHistoryEnabled = localHistoryEnabled;
    }

    @Override
    public void encrypt(Path file) {
        fileWorkflows.encryption().encryptFile(file);
    }

    @Override
    public boolean localHistoryEnabled() {
        return localHistoryEnabled.getAsBoolean();
    }

    @Override
    public void showLocalHistory(Path file) {
        history.showForPath(file);
    }

    @Override
    public GitPathScope gitScope(Path path) {
        return git.scopeOf(path);
    }

    @Override
    public void gitShowFileHistory(Path file) {
        git.activatingRepositoryOf(file, () -> gitWindows.gitFileHistoryForPath(file));
    }

    @Override
    public void gitCompareWithHead(Path file) {
        git.withRepositoryOf(file, () -> diff.diffPathVsHead(file));
    }

    @Override
    public void gitCompareWithBranch(Path file) {
        git.withRepositoryOf(file, () -> diff.diffPathVsBranch(file));
    }

    @Override
    public void gitCompareWithTag(Path file) {
        git.withRepositoryOf(file, () -> diff.diffPathVsTag(file));
    }

    @Override
    public void gitCompareWithRevision(Path file) {
        git.withRepositoryOf(file, () -> diff.diffPathVsCommit(file));
    }

    @Override
    public void gitAnnotate(Path file) {
        git.ifEnabled(() -> {
            fileWorkflows.openPath(file);
            git.annotateActive();
        });
    }

    @Override
    public void gitStage(Path file) {
        git.ifEnabled(() -> git.gitStagePath(file));
    }

    @Override
    public void gitUnstage(Path file) {
        git.ifEnabled(() -> git.gitUnstagePath(file));
    }

    @Override
    public void gitRevert(Path file) {
        git.ifEnabled(() -> git.gitRevertPath(file));
    }

    @Override
    public void gitAddToGitignore(Path file) {
        git.ifEnabled(() -> git.addToGitignore(file));
    }
}
