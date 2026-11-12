package com.editora.ui;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

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
    private final java.util.function.Consumer<Path> findInFolder;
    /** The window's link choke point; nothing until {@link #of} sets it. */
    private java.util.function.Consumer<String> openUrl = url -> {};
    /** The window's file finder's choice: a file opened, or a new one started. Nothing until {@link #of}. */
    private java.util.function.Consumer<Path> openPicked = file -> {};
    /** The window's Go to Symbol choice, a library's source included. Nothing until {@link #of}. */
    private java.util.function.BiConsumer<Path, com.editora.lsp.LspManager.WorkspaceSymbolMatch> openSymbol =
            (anchor, match) -> {};
    /** The window's Find in Files, for the Map's text search. Nothing until {@link #of}. */
    private @Nullable SearchCoordinator search;

    ProjectTreeFileActions(
            GitCoordinator git,
            GitWindowCoordinator gitWindows,
            DiffCoordinator diff,
            HistoryCoordinator history,
            FileWorkflowCoordinator fileWorkflows,
            BooleanSupplier localHistoryEnabled) {
        this(git, gitWindows, diff, history, fileWorkflows, localHistoryEnabled, folder -> {});
    }

    /** The window's actions, its Find in Files popup included (Project Map review 3 E10: Find in Folder…). */
    static ProjectTreeFileActions of(MainController window, BooleanSupplier localHistoryEnabled) {
        ProjectTreeFileActions actions = new ProjectTreeFileActions(
                window.git,
                window.gitWindows,
                window.diffCoordinator,
                window.historyCoordinator,
                window.fileWorkflows,
                localHistoryEnabled,
                window.searchCoordinator::findInFolder);
        actions.openUrl = window::openExternalUrl;
        actions.openPicked = window.navigation::findFileChosen;
        actions.openSymbol = window.lspCoordinator.navigation::openWorkspaceSymbol;
        actions.search = window.searchCoordinator;
        return actions;
    }

    @Override
    public void searchText(
            com.editora.search.SearchQuery query,
            Path folder,
            java.util.function.Consumer<com.editora.search.SearchService.@Nullable Outcome> answer) {
        SearchCoordinator coordinator = search;
        if (coordinator == null) {
            answer.accept(null);
        } else {
            coordinator.mapTextSearch(query, folder, answer);
        }
    }

    @Override
    public void openExternalUrl(String url) {
        openUrl.accept(url);
    }

    @Override
    public void openPicked(Path file) {
        openPicked.accept(file);
    }

    @Override
    public void openWorkspaceSymbol(Path anchor, com.editora.lsp.LspManager.WorkspaceSymbolMatch match) {
        openSymbol.accept(anchor, match);
    }

    /** {@code findInFolder}: a folder's Find in Folder…, the Find in Files popup set to that folder. */
    ProjectTreeFileActions(
            GitCoordinator git,
            GitWindowCoordinator gitWindows,
            DiffCoordinator diff,
            HistoryCoordinator history,
            FileWorkflowCoordinator fileWorkflows,
            BooleanSupplier localHistoryEnabled,
            java.util.function.Consumer<Path> findInFolder) {
        this.git = git;
        this.gitWindows = gitWindows;
        this.diff = diff;
        this.history = history;
        this.fileWorkflows = fileWorkflows;
        this.localHistoryEnabled = localHistoryEnabled;
        this.findInFolder = findInFolder;
    }

    @Override
    public void findInFolder(Path folder) {
        findInFolder.accept(folder);
    }

    @Override
    public void gitShowFolderHistory(Path folder) {
        git.ifEnabled(() -> gitWindows.gitFolderHistoryForPath(folder));
    }

    @Override
    public void followCode(Path file, int line, int column, @Nullable String name, String commandId) {
        fileWorkflows.openThenRun(file, line, column, name, commandId);
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

    @Override
    public void gitLastChange(
            Path file,
            int startLine,
            int endLine,
            java.util.function.Consumer<com.editora.git.GitService.@Nullable LastChange> found) {
        if (git.isEnabled()) {
            git.service().lastChange(file, startLine, endLine, found);
        } else {
            found.accept(null);
        }
    }

    @Override
    public void gitLineHistory(
            Path file,
            int startLine,
            int endLine,
            int max,
            java.util.function.Consumer<com.editora.git.LineHistory.Result> found) {
        if (git.isEnabled()) {
            git.service().lineHistory(file, startLine, endLine, max, found);
        } else {
            found.accept(com.editora.git.LineHistory.Result.unavailable());
        }
    }

    @Override
    public void gitPermalink(
            Path target,
            int startLine,
            int endLine,
            java.util.function.Consumer<com.editora.git.GitService.Permalink> found) {
        if (git.isEnabled()) {
            git.service().permalink(target, startLine, endLine, found);
        } else {
            found.accept(new com.editora.git.GitService.Permalink(
                    com.editora.git.GitService.Permalink.Status.NO_REPOSITORY, "", false));
        }
    }

    @Override
    public void gitBlame(
            Path root,
            Path file,
            java.util.function.Consumer<java.util.List<com.editora.git.BlameParser.BlameLine>> found) {
        if (git.isEnabled()) {
            git.service().blame(root, file, found);
        } else {
            found.accept(java.util.List.of());
        }
    }

    @Override
    public void gitWorkingChanges(Path folder, java.util.function.Consumer<com.editora.git.GitChanges.Result> found) {
        if (git.isEnabled()) {
            git.service().workingChanges(folder, found);
        } else {
            found.accept(com.editora.git.GitChanges.Result.UNAVAILABLE);
        }
    }

    @Override
    public void gitWorkingChanges(
            Path folder,
            com.editora.git.GitService.ChangesBase base,
            java.util.function.Consumer<com.editora.git.GitChanges.Result> found) {
        if (git.isEnabled()) {
            git.service().workingChanges(folder, base, found);
        } else {
            found.accept(com.editora.git.GitChanges.Result.UNAVAILABLE);
        }
    }

    @Override
    public void gitReviewRefs(Path folder, java.util.function.Consumer<com.editora.git.GitService.ReviewRefs> found) {
        if (git.isEnabled()) {
            git.service().reviewRefs(folder, found);
        } else {
            found.accept(com.editora.git.GitService.ReviewRefs.NONE);
        }
    }

    @Override
    public void gitCompareWithRef(Path file, String ref, String label) {
        git.withRepositoryOf(file, () -> diff.diffPathVsRef(file, ref, label));
    }

    @Override
    public void gitOpenCommitFile(Path root, String hash, String path, @Nullable String previousPath) {
        git.ifEnabled(() -> diff.diffCommitFile(root, hash, path, previousPath));
    }
}
