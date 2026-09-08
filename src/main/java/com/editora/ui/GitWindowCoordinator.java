package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
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
                    // Each row names its command so the popup takes the VCS menu's own glyph for it.
                    new BranchPopup.MenuAction(tr("branch.newBranch"), "", "git.newBranch", host.git()::newBranch),
                    new BranchPopup.MenuAction(
                            tr("branch.pull"),
                            "",
                            "git.pull",
                            () -> host.git().gitSync(tr("gitlabel.pull"), "pull", "--ff-only")),
                    new BranchPopup.MenuAction(
                            tr("branch.fetch"),
                            "",
                            "git.fetch",
                            () -> host.git().gitSync(tr("gitlabel.fetch"), "fetch", "--all")),
                    new BranchPopup.MenuAction(tr("branch.push"), "", "git.push", host.git()::gitPush),
                    new BranchPopup.MenuAction(tr("branch.stash"), "", "git.stash", host.git()::gitStash),
                    new BranchPopup.MenuAction(tr("branch.unstash"), "", "git.unstash", host.git()::gitUnstash),
                    new BranchPopup.MenuAction(tr("branch.commit"), "C-x g", "git.commit", host.git()::gitCommitFocus));
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
                host.github().fetchPrs(host.githubPanel()::setPrs);
            }

            @Override
            public void showIssues() {
                host.github().fetchIssues(host.githubPanel()::setIssues);
            }

            @Override
            public void showRuns() {
                host.github().fetchRuns(host.githubPanel()::setRuns);
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
        switch (host.githubPanel().mode()) {
            case PRS -> host.github().fetchPrs(host.githubPanel()::setPrs);
            case ISSUES -> host.github().fetchIssues(host.githubPanel()::setIssues);
            case RUNS -> host.github().fetchRuns(host.githubPanel()::setRuns);
        }
    }

    /** The {@link GitLogPanel.Actions} the Git Log tool window routes user actions through. */
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
                if (host.git().repoRoot() != null) {
                    host.git().service().commitFiles(host.git().repoRoot(), hash, host.gitLogPanel()::setCommitFiles);
                }
            }

            @Override
            public void openFileDiff(String hash, String repoRel, String origRepoRel) {
                host.diffCoordinator().diffCommitFile(hash, repoRel, origRepoRel);
            }

            @Override
            public void openFile(String repoRel) {
                Path root = host.git().repoRoot();
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
                Path root = host.git().repoRoot();
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
                gitMutate(
                        tr("status.git.reset", mode, com.editora.git.GitFormat.shortHash(hash)),
                        "reset",
                        "--" + mode,
                        hash);
                Platform.runLater(host.fileWorkflows()::checkExternalChanges);
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
                host.promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
                    String name = input.strip();
                    if (!name.isEmpty()) {
                        gitMutate(tr("status.createdBranch", name), "checkout", "-b", name, hash);
                        Platform.runLater(host::reloadAllFromDiskSilently);
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
        javafx.scene.control.ChoiceDialog<String> d =
                new javafx.scene.control.ChoiceDialog<>("mixed", java.util.List.of("soft", "mixed", "hard"));
        d.initOwner(host.stage());
        d.setTitle(tr("dialog.gitReset.title"));
        d.setHeaderText(null);
        d.setContentText(tr("dialog.gitReset.content"));
        d.showAndWait().ifPresent(mode -> host.gitLogOps().reset(hash, mode));
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
        if (host.git().repoRoot() == null) {
            host.gitLogPanel().setLog(List.of(), null);
            host.git().reportIfNoRepo(); // echoes "not a repo" / "git not installed"
            return;
        }
        String name = file != null ? file.getFileName().toString() : null;
        host.git()
                .service()
                .log(
                        host.git().repoRoot(),
                        file,
                        200,
                        commits -> host.gitLogPanel().setLog(commits, name));
    }

    /** A history mutation (checkout/reset/revert/cherry-pick/branch): run, report, refresh + reload log. */
    void gitMutate(String successMessage, String... args) {
        if (host.git().reportIfNoRepo()) {
            return;
        }
        host.git()
                .service()
                .run(
                        host.git().repoRoot(),
                        r -> {
                            if (r.ok()) {
                                host.setStatus(successMessage);
                            } else {
                                host.git().gitError(tr("status.git.opFailed"), r.message());
                            }
                            host.git().afterMutation();
                            loadGitLog(gitLogFilter); // HEAD/refs moved → refresh the log
                        },
                        args);
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
