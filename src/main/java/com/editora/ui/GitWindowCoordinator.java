package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** Coordinates Git window actions and file navigation. */
final class GitWindowCoordinator {
    interface Host {
        FileWorkflowCoordinator fileWorkflows();

        Stage stage();

        StatusBar statusBar();

        ToolWindowManager toolWindows();

        GitPanel gitPanel();

        ToolWindow commitToolWindow();

        GitLogPanel gitLogPanel();

        GitLogPanel.Actions gitLogOps();

        ToolWindow gitLogToolWindow();

        GitHubPanel githubPanel();

        GitCoordinator git();

        DiffCoordinator diffCoordinator();

        GitHubCoordinator github();

        EditorBuffer openBufferFor(Path target);

        void reloadAllFromDiskSilently();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);
    }

    private final Host host;

    GitWindowCoordinator(Host host) {
        this.host = host;
    }

    /** The path the Git Log is currently filtered to (file history), or null for the whole repo. */
    Path gitLogFilter;

    /**
     * The repository (and its branch) whose commits the Git Log lists — or is about to list, the rows having
     * been cleared. Every action on a row runs here, never in whichever repository is active at click time:
     * commit hashes are shared between the worktrees and clones of a project, so a reset or checkout chosen
     * from one repository's history would otherwise succeed in another.
     */
    private Path gitLogRoot;

    private String gitLogBranch = "";
    /** Bumped per load and per repository change; a log result for an earlier request is dropped. */
    private long gitLogGeneration;

    /** IntelliJ-style branch dropdown (actions + Local/Remote branches), anchored to the status bar. */
    final BranchPopup branchPopup = new BranchPopup();

    /**
     * Stages (or unstages) the Commit window's selected rows — the palette twins of its context menu, so
     * the whole flow works without the mouse. Opens the window first, since acting on a selection the user
     * can't see would be a surprise, and echoes when the selection holds nothing to act on.
     */
    void stageSelectedInCommitWindow(boolean stage) {
        host.toolWindows().open(host.commitToolWindow(), true);
        boolean acted =
                stage ? host.gitPanel().stageSelected() : host.gitPanel().unstageSelected();
        if (!acted) {
            host.setStatus(tr(stage ? "status.git.nothingToStage" : "status.git.nothingToUnstage"));
        }
    }

    /** Toggles the IntelliJ-style branch dropdown, fetching local + remote branches off-thread first. */
    void chooseBranch() {
        // Toggle: a second click on the git status segment closes the open dropdown. (autoHide fires on
        // the same click, hiding it, so also treat a just-now hide as "was open" and leave it closed.)
        if (branchPopup.isShown()) {
            branchPopup.hide();
            return;
        }
        if (branchPopup.justHidden()) {
            return;
        }
        if (host.git().repoRoot() == null) {
            // Not under version control: the dropdown offers only "Clone Git repository…".
            branchPopup.showNoVcs(host.stage(), host.statusBar().gitSegmentNode(), host.git()::cloneRepo);
            return;
        }
        host.git().service().branches(host.git().repoRoot(), branches -> {
            List<BranchPopup.MenuAction> actions = List.of(
                    // Each row names its command so the popup takes the VCS menu's own glyph (and chord) for it.
                    new BranchPopup.MenuAction(tr("branch.newBranch"), "git.newBranch", host.git()::newBranch),
                    new BranchPopup.MenuAction(
                            tr("branch.pull"),
                            "git.pull",
                            () -> host.git().gitSync(tr("gitlabel.pull"), "pull", "--ff-only")),
                    new BranchPopup.MenuAction(
                            tr("branch.fetch"),
                            "git.fetch",
                            () -> host.git().gitSync(tr("gitlabel.fetch"), "fetch", "--all")),
                    new BranchPopup.MenuAction(tr("branch.push"), "git.push", host.git()::gitPush),
                    new BranchPopup.MenuAction(tr("branch.stash"), "git.stash", host.git()::gitStash),
                    new BranchPopup.MenuAction(tr("branch.unstash"), "git.unstash", host.git()::gitUnstash),
                    new BranchPopup.MenuAction(tr("branch.commit"), "git.commit", host.git()::gitCommitFocus));
            branchPopup.show(
                    host.stage(),
                    host.statusBar().gitSegmentNode(),
                    host.git().branchName(),
                    branches.local(),
                    branches.remote(),
                    branches.remoteUrl(),
                    actions,
                    host.git()::checkoutBranch,
                    host.git()::checkoutRemoteBranch);
        });
    }

    /** The {@link GitHubPanel.Actions} the GitHub tool window routes user actions through. */
    GitHubPanel.Actions githubActions() {
        return new GitHubPanel.Actions() {
            @Override
            public void refresh() {
                reloadGithubPanel();
            }

            @Override
            public void createPr() {
                host.github().createPr();
            }

            @Override
            public void showPrs() {
                fetchGithub(GitHubPanel.Mode.PRS);
            }

            @Override
            public void showIssues() {
                fetchGithub(GitHubPanel.Mode.ISSUES);
            }

            @Override
            public void showRuns() {
                fetchGithub(GitHubPanel.Mode.RUNS);
            }

            @Override
            public void viewRunLog(long runId, String workflowName) {
                host.github().viewRunLog(runId, workflowName);
            }

            @Override
            public void rerunRun(long runId, boolean failedOnly) {
                host.github().rerunRun(runId, failedOnly);
            }

            @Override
            public void cancelRun(long runId) {
                host.github().cancelRun(runId);
            }

            @Override
            public void checkoutPr(int number) {
                host.github().checkoutNumber(number);
            }

            @Override
            public void reviewPr(int number) {
                host.github().reviewPrNumber(number);
            }

            @Override
            public void openUrl(String url) {
                host.github().openUrl(url);
            }

            @Override
            public void copyUrl(String url) {
                host.github().copyUrl(url);
            }
        };
    }

    /** Re-fetches the GitHub tool window's current segment (PRs, Issues, or Runs). */
    void reloadGithubPanel() {
        host.githubPanel().showLoading();
        fetchGithub(host.githubPanel().mode());
    }

    /** Fetches one segment of the GitHub tool window; a failure is shown in the list, not as an empty one. */
    void fetchGithub(GitHubPanel.Mode mode) {
        GitHubPanel panel = host.githubPanel();
        java.util.function.Consumer<String> failed = message -> panel.showError(mode, message);
        switch (mode) {
            case PRS -> host.github().fetchPrs(panel::setPrs, failed);
            case ISSUES -> host.github().fetchIssues(panel::setIssues, failed);
            case RUNS -> host.github().fetchRuns(panel::setRuns, failed);
        }
    }

    /** The {@link GitLogPanel.Actions} the Git Log tool window routes user actions through. */
    /**
     * The working-tree file a commit's {@code repoRel} blob is compared with. A file history lists <em>every</em>
     * file of the selected commit, so the history file is the working side only for its own row; any other row
     * is compared with its own working copy — never written into the history file.
     */
    static Path historyWorkingFile(Path root, Path historyFile, String repoRel) {
        if (historyFile != null
                && !Files.isDirectory(historyFile)
                && repoRel.equals(com.editora.git.GitService.repoRelative(root, historyFile))) {
            return historyFile;
        }
        return root.resolve(repoRel);
    }

    GitLogPanel.Actions gitLogActions() {
        return new GitLogPanel.Actions() {
            @Override
            public void refresh() {
                loadGitLog(gitLogFilter);
            }

            @Override
            public void showAll() {
                loadGitLog(null);
            }

            @Override
            public void selected(String hash) {
                Path root = gitLogRoot;
                long generation = gitLogGeneration;
                if (root != null) {
                    host.git().service().commitFiles(root, hash, files -> {
                        if (generation == gitLogGeneration) {
                            host.gitLogPanel().setCommitFiles(files);
                        }
                    });
                }
            }

            @Override
            public void openFileDiff(String hash, String repoRel, String origRepoRel) {
                host.diffCoordinator().diffCommitFile(gitLogRoot, hash, repoRel, origRepoRel);
            }

            @Override
            public void compareFileWithWorking(String hash, String repoRel) {
                Path root = gitLogRoot;
                if (root == null) {
                    return;
                }
                host.diffCoordinator()
                        .diffCommitFileVsWorking(root, hash, repoRel, historyWorkingFile(root, gitLogFilter, repoRel));
            }

            @Override
            public void openFile(String repoRel) {
                Path root = gitLogRoot;
                if (root == null) {
                    return;
                }
                Path file = root.resolve(repoRel);
                if (Files.exists(file)) {
                    host.fileWorkflows().openPath(file);
                } else {
                    host.setStatus(tr("status.git.fileGone", repoRel));
                }
            }

            @Override
            public void showFileHistory(String repoRel) {
                Path root = gitLogRoot;
                if (root != null) {
                    openGitLog(root.resolve(repoRel));
                }
            }

            @Override
            public void copyPath(String repoRel) {
                ClipboardContent content = new ClipboardContent();
                content.putString(repoRel);
                Clipboard.getSystemClipboard().setContent(content);
                host.setStatus(tr("status.copiedPath"));
            }

            @Override
            public void copyHash(String hash) {
                ClipboardContent content = new ClipboardContent();
                content.putString(hash);
                Clipboard.getSystemClipboard().setContent(content);
                host.setStatus(tr("status.git.copiedHash", com.editora.git.GitFormat.shortHash(hash)));
            }

            @Override
            public void checkout(String hash) {
                gitMutate(tr("status.git.checkedOut", com.editora.git.GitFormat.shortHash(hash)), "checkout", hash);
            }

            @Override
            public void reset(String hash, String mode) {
                resetIn(gitLogRoot, gitLogBranch, hash, mode);
            }

            @Override
            public void revert(String hash) {
                gitMutate(
                        tr("status.git.reverted", com.editora.git.GitFormat.shortHash(hash)),
                        "revert",
                        "--no-edit",
                        hash);
            }

            @Override
            public void cherryPick(String hash) {
                gitMutate(
                        tr("status.git.cherryPicked", com.editora.git.GitFormat.shortHash(hash)), "cherry-pick", hash);
            }

            @Override
            public void newBranch(String hash) {
                Path root = gitLogRoot; // before the prompt: the active repository can change while it is up
                host.promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
                    String name = input.strip();
                    if (!name.isEmpty()) {
                        gitMutateIn(root, tr("status.createdBranch", name), "checkout", "-b", name, hash);
                    }
                });
            }
        };
    }

    /** Runs {@code op} on the Git Log panel's selected commit, or reports that none is selected. Git-gated. */
    void withSelectedCommit(java.util.function.Consumer<String> op) {
        host.git().ifEnabled(() -> {
            String hash = host.gitLogPanel().selectedHash();
            if (hash == null || hash.isBlank()) {
                host.setStatus(tr("status.git.noCommitSelected"));
                return;
            }
            op.accept(hash);
        });
    }

    /** Asks for the reset mode (soft/mixed/hard) then resets the selected commit (the {@code git.log.reset} command). */
    void promptGitReset(String hash) {
        // Both dialogs below run a nested event loop in which the active repository can change; the commit
        // was selected in the log's repository, so that is captured first.
        Path root = gitLogRoot;
        String branch = gitLogBranch;
        javafx.scene.control.ChoiceDialog<String> d =
                new javafx.scene.control.ChoiceDialog<>("mixed", java.util.List.of("soft", "mixed", "hard"));
        d.initOwner(host.stage());
        d.setTitle(tr("dialog.gitReset.title"));
        d.setHeaderText(null);
        d.setContentText(tr("dialog.gitReset.content"));
        d.showAndWait().ifPresent(mode -> resetIn(root, branch, hash, mode));
    }

    /**
     * {@code git reset --<mode> <hash>} in {@code root} — the repository the commit was listed from, captured
     * by the caller before any dialog. Hard is the one mode that throws work away: it sits directly under Soft
     * and Mixed in the menu, so it is confirmed with the dialog a file discard uses, naming what will be lost
     * and the repository and branch it will be lost from.
     */
    private void resetIn(Path root, String branch, String hash, String mode) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String shortHash = com.editora.git.GitFormat.shortHash(hash);
        if ("hard".equals(mode)
                && !host.git()
                        .confirmDestructive(
                                tr("dialog.gitReset.title"),
                                tr("dialog.gitReset.hardConfirm", shortHash, branch, root),
                                tr("dialog.gitReset.hard"))) {
            return;
        }
        gitMutateIn(root, tr("status.git.reset", mode, shortHash), "reset", "--" + mode, hash);
    }

    /**
     * Opens the Git Log tool window with the given filter (null = whole repo). A fresh open triggers the
     * load via the tool-window state listener; if the window is already open (no state change, so no
     * listener), the log is refreshed explicitly — so opening always shows current history.
     */
    void openGitLog(Path filter) {
        gitLogFilter = filter;
        boolean wasOpen = host.toolWindows().isOpen(host.gitLogToolWindow());
        host.toolWindows().open(host.gitLogToolWindow());
        if (wasOpen) {
            loadGitLog(filter);
        }
    }

    /** Opens the Git Log tool window showing the whole-repo history. */
    void showGitLog() {
        openGitLog(null);
    }

    /** Opens the Git Log filtered to the active file's history. */
    void showFileHistory() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        openGitLog(b.getPath());
    }

    /** Loads up to 200 commits (whole-repo when {@code file} is null, else that file's history). */
    void loadGitLog(Path file) {
        gitLogFilter = file;
        Path root = host.git().repoRoot();
        long generation = ++gitLogGeneration;
        if (!java.util.Objects.equals(root, gitLogRoot)) {
            // Rows of another repository must not stay on screen while this one's history is fetched.
            host.gitLogPanel().setLog(List.of(), null);
        }
        gitLogRoot = root;
        gitLogBranch = host.git().branchName();
        if (root == null) {
            host.gitLogPanel().setLog(List.of(), null);
            host.git().reportIfNoRepo(); // echoes "not a repo" / "git not installed"
            return;
        }
        String name = file != null ? file.getFileName().toString() : null;
        host.git().service().log(root, file, 200, commits -> {
            if (generation == gitLogGeneration) {
                host.gitLogPanel().setLog(commits, name);
            }
        });
    }

    /**
     * The active repository or its branch changed (a tab of another repository or worktree was activated, a
     * branch was switched, the folder is no longer a repository). The log must not keep offering the commits
     * of a repository its actions no longer run in: its rows are dropped at once, and the new repository's
     * history is loaded when the window is open (a closed window loads on its next open).
     */
    void repositoryChanged(Path root, String branch) {
        if (host.github() != null) {
            host.github().repositoryChanged(root, branch); // re-gate the GitHub window, drop stale CI checks
        }
        boolean sameRoot = java.util.Objects.equals(root, gitLogRoot);
        if (sameRoot && java.util.Objects.equals(branch, gitLogBranch)) {
            return;
        }
        gitLogBranch = branch;
        if (!sameRoot) {
            gitLogRoot = root;
            gitLogGeneration++;
            if (gitLogFilter != null && (root == null || !gitLogFilter.startsWith(root))) {
                gitLogFilter = null; // a file history of the previous repository
            }
            if (host.gitLogPanel() != null) {
                host.gitLogPanel().setLog(List.of(), null);
            }
        }
        if (root != null
                && host.gitLogToolWindow() != null
                && host.toolWindows().isOpen(host.gitLogToolWindow())) {
            loadGitLog(gitLogFilter);
        }
    }

    /** A history mutation (checkout/reset/revert/cherry-pick/branch) in the repository the log lists. */
    void gitMutate(String successMessage, String... args) {
        gitMutateIn(gitLogRoot, successMessage, args);
    }

    /** Runs the mutation in {@code root}, reports, refreshes and reloads the log. */
    private void gitMutateIn(Path root, String successMessage, String... args) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        host.git().mutateWorkingTree(root, successMessage, () -> loadGitLog(gitLogFilter), args);
    }

    /** Project-tree Git ▸ Show File History for {@code file}: loads that file's Git log + opens the window. */
    void gitFileHistoryForPath(Path file) {
        if (file == null || Files.isDirectory(file)) {
            host.setStatus(tr("status.diff.noFile"));
            return;
        }
        openGitLog(file);
    }

    /** The current text of {@code file}: the open buffer's live text when open, else the on-disk content. */
    String currentTextOf(Path file) {
        EditorBuffer open = host.openBufferFor(file);
        if (open != null) {
            return open.getContent();
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }
}
