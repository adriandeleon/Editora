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

    /** Branch, remote and work-tree management — the dropdown's row actions and the {@code git.*} commands. */
    final GitBranchCoordinator branches;

    GitWindowCoordinator(Host host) {
        this.host = host;
        this.branches = new GitBranchCoordinator(host);
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

    /**
     * Whether the log lists every branch, remote and tag rather than the checked-out branch. View state of
     * this window, kept for as long as it is open (not a setting).
     */
    boolean gitLogAllBranches;

    /** The history search the log shows the result of ({@link com.editora.git.GitLogQuery}); "" for none. */
    String gitLogSearch = "";

    /** A next-page request is in flight; a second one is not sent on top of it. */
    private boolean gitLogLoadingMore;

    /** A full (first-page) load is in flight; the next page waits for it. */
    private boolean gitLogLoading;

    /** What the rows on screen were listed for: a reload of the same listing keeps the depth loaded so far. */
    private Object gitLogListing;

    /**
     * Asks which parent a merge is reverted against: given the merge and its parents, the 1-based mainline,
     * or null to cancel. A field so a test can answer without a dialog.
     */
    java.util.function.BiFunction<String, List<String>, Integer> mainlineChooser = this::chooseMainline;

    /** Confirms deleting a tag (tag, repository); a field for the same reason. */
    java.util.function.BiPredicate<String, String> tagDeleteConfirmer = this::confirmTagDelete;

    private boolean confirmTagDelete(String tag, String root) {
        return host.git()
                .confirmDestructive(
                        tr("dialog.deleteTag.title"),
                        tr("dialog.deleteTag.content", tag, root),
                        tr("dialog.deleteTag.action"));
    }

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

    /** {@code git add -A} — the palette/menu twin of the Commit window's Stage All button. */
    void stageAll() {
        if (!host.git().reportIfNoRepo()) {
            host.git().gitOp(tr("status.git.stagedAll"), "add", "-A");
        }
    }

    /** The active buffer's file, or null (after reporting it) when the active tab has none. */
    private Path activeFileOrReport() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null) {
            host.setStatus(tr("status.noGitFile"));
        }
        return file;
    }

    /** Adds the active file to the repository's {@code .gitignore} (the tab/Project-tree menu action). */
    void addActiveFileToGitignore() {
        Path file = activeFileOrReport();
        if (file != null) {
            host.git().addToGitignore(file);
        }
    }

    /** Diffs the active file against a branch picked from the repository (the Project-tree menu action). */
    void compareActiveWithBranch() {
        Path file = activeFileOrReport();
        if (file != null) {
            host.diffCoordinator().diffPathVsBranch(file);
        }
    }

    /** Diffs the active file against a tag picked from the repository (the Project-tree menu action). */
    void compareActiveWithTag() {
        Path file = activeFileOrReport();
        if (file != null) {
            host.diffCoordinator().diffPathVsTag(file);
        }
    }

    /** How many commits one page of the Git Log holds; the next page is loaded on scroll or on request. */
    static final int LOG_PAGE = 200;

    /**
     * The most rows a reload brings back in one go. A reload asks again for as many commits as were loaded
     * (so the list does not shrink under the user after every Git command); past this it falls back to the
     * newest part — one {@code git log} of this size is a few megabytes and tens of milliseconds.
     */
    static final int LOG_RELOAD_LIMIT = 5_000;

    /** The page size in use; a field so a test can page through a handful of commits. */
    int logPageSize = LOG_PAGE;

    /** How many commits a reload of a listing with {@code loaded} rows asks for. Pure. */
    static int reloadSize(int loaded, boolean sameListing, int page) {
        return sameListing ? Math.clamp(loaded, page, Math.max(page, LOG_RELOAD_LIMIT)) : page;
    }

    /**
     * Subscribes to the Git engine: repository/branch changes re-target the log, and every completed
     * mutation (commit, pull, fetch, stash, checkout, …) reloads an open one.
     */
    void listenTo(GitCoordinator git) {
        git.onRepositoryChanged(this::repositoryChanged);
        git.onMutation(this::gitMutated);
        git.pushHandler = branches::push; // every push answers a non-fast-forward rejection with a choice
    }

    /**
     * A Git command finished. History may have moved (a commit, a pull) or only its decorations (a fetch
     * moves {@code origin/…}, a push too), so an open log is reloaded; the panel keeps its selection when the
     * same commits come back. A closed log loads on its next open.
     */
    void gitMutated() {
        if (gitLogRoot != null
                && host.gitLogToolWindow() != null
                && host.toolWindows().isOpen(host.gitLogToolWindow())) {
            loadGitLog(gitLogFilter);
        }
    }

    /** {@code git fetch --all --prune}: see {@link #FETCH_ARGS}. */
    void fetch() {
        host.git().gitSync(tr("gitlabel.fetch"), FETCH_ARGS);
    }

    /** {@code git pull} in the configured "Pull mode" (fast-forward only by default). */
    void pull() {
        host.git().gitPull();
    }

    /**
     * Editora's fetch prunes: without it a remote-tracking branch deleted on the server lives on locally
     * forever, and the "gone" marker the branch dropdown draws for a local branch whose upstream was deleted
     * never appears. {@code --prune} only removes stale {@code refs/remotes/…}; tags follow the user's own
     * {@code fetch.pruneTags}, and nothing is written to their configuration.
     */
    static final String[] FETCH_ARGS = {"fetch", "--all", "--prune"};

    /**
     * The local branch a remote-tracking branch would be checked out as: {@code origin/feature/x} →
     * {@code feature/x}. Empty when {@code remoteBranch} has no remote prefix.
     */
    static String localNameOf(String remoteBranch) {
        int slash = remoteBranch == null ? -1 : remoteBranch.indexOf('/');
        return slash < 0 ? "" : remoteBranch.substring(slash + 1);
    }

    /**
     * What choosing a remote branch in the dropdown does: switch to the local branch of that name when there
     * is one ({@code git checkout --track origin/x} fails with "a branch named 'x' already exists"), else
     * create the tracking branch. Returns the local branch to switch to, or {@code null} to create one.
     */
    static String existingLocalFor(String remoteBranch, List<com.editora.git.GitService.BranchInfo> local) {
        String name = localNameOf(remoteBranch);
        if (name.isEmpty()) {
            return null;
        }
        for (com.editora.git.GitService.BranchInfo b : local) {
            if (b.name().equals(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Runs a branch-dropdown action only while the repository the dropdown was opened for is still the
     * active one. The dropdown lists the branches of one repository; every action it offers reaches the
     * engine's <em>active</em> repository, so after a switch (another window's tab, a worktree) the action
     * would run — a checkout, a pull — somewhere the list never described. It is refused instead.
     */
    private Runnable inRoot(Path root, Runnable action) {
        return () -> {
            if (!java.util.Objects.equals(root, host.git().repoRoot())) {
                host.setStatus(tr("status.git.repoChanged"));
                return;
            }
            action.run();
        };
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
        // The repository this request is for. The branch list arrives asynchronously and the dropdown then
        // stays up for as long as the user reads it; everything below is tied to this root.
        Path root = host.git().repoRoot();
        if (root == null) {
            // Not under version control: the dropdown offers only "Clone Git repository…".
            branchPopup.showNoVcs(host.stage(), host.statusBar().gitSegmentNode(), host.git()::cloneRepo);
            return;
        }
        host.git().service().branches(root, branches -> {
            if (!java.util.Objects.equals(root, host.git().repoRoot())) {
                return; // another repository became active while the branches were being listed
            }
            List<BranchPopup.MenuAction> actions = List.of(
                    // Each row names its command so the popup takes the VCS menu's own glyph (and chord) for it.
                    new BranchPopup.MenuAction(
                            tr("branch.newBranch"), "git.newBranch", inRoot(root, host.git()::newBranch)),
                    new BranchPopup.MenuAction(tr("branch.pull"), "git.pull", inRoot(root, this::pull)),
                    new BranchPopup.MenuAction(tr("branch.fetch"), "git.fetch", inRoot(root, this::fetch)),
                    new BranchPopup.MenuAction(tr("branch.push"), "git.push", inRoot(root, host.git()::gitPush)),
                    new BranchPopup.MenuAction(tr("branch.stash"), "git.stash", inRoot(root, host.git()::gitStash)),
                    new BranchPopup.MenuAction(
                            tr("branch.unstash"), "git.unstash", inRoot(root, host.git()::gitUnstash)),
                    new BranchPopup.MenuAction(
                            tr("branch.commit"), "git.commit", inRoot(root, host.git()::gitCommitFocus)));
            String current = host.git().branchName();
            branchPopup.show(
                    host.stage(),
                    host.statusBar().gitSegmentNode(),
                    current,
                    branches.local(),
                    branches.remote(),
                    branches.remoteNames(),
                    branches.remoteUrl(),
                    actions,
                    name -> inRoot(root, () -> host.git().checkoutBranch(name)).run(),
                    remote -> inRoot(root, () -> {
                                String existing = existingLocalFor(remote, branches.local());
                                if (existing != null) {
                                    host.git().checkoutBranch(existing);
                                } else {
                                    host.git().checkoutRemoteBranch(remote);
                                }
                            })
                            .run(),
                    // Each row's secondary menu (rename, merge, delete…), under the same root guard.
                    row -> this.branches.rowActions(root, current, branches, row, run -> inRoot(root, run)));
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

    /** Whether the GitHub panel's busy indicator follows {@code GitHubCoordinator.callsInFlightProperty()} yet. */
    private boolean githubBusyBound;

    /** Re-fetches the GitHub tool window's current segment (PRs, Issues, or Runs). */
    void reloadGithubPanel() {
        host.githubPanel().showLoading();
        fetchGithub(host.githubPanel().mode());
    }

    /** Fetches one segment of the GitHub tool window; a failure is shown in the list, not as an empty one. */
    void fetchGithub(GitHubPanel.Mode mode) {
        GitHubPanel panel = host.githubPanel();
        if (!githubBusyBound) {
            // The toolbar spinner follows the user's gh calls queued or running (not the background polls).
            githubBusyBound = true;
            javafx.beans.property.ReadOnlyIntegerProperty calls = host.github().callsInFlightProperty();
            calls.addListener((o, was, now) -> panel.setBusy(now.intValue() > 0));
            panel.setBusy(calls.get() > 0);
        }
        java.util.function.Consumer<String> failed = message -> panel.showError(mode, message);
        com.editora.github.GitHubListQuery query = panel.query(mode); // state / mine / how many rows
        switch (mode) {
            case PRS -> host.github().fetchPrs(query, page -> panel.setPrs(page.items(), page.more()), failed);
            case ISSUES -> host.github().fetchIssues(query, page -> panel.setIssues(page.items(), page.more()), failed);
            case RUNS -> host.github().fetchRuns(query, page -> panel.setRuns(page.items(), page.more()), failed);
        }
        host.github().panelContext(panel::setContext); // whose rows these are (the repository is cached)
    }

    /** The {@link GitLogPanel.Actions} the Git Log tool window routes user actions through. */
    /**
     * The working-tree file a commit's {@code repoRel} blob is compared with. A file history lists <em>every</em>
     * file of the selected commit, so the history file is the working side only for its own row; any other row
     * is compared with its own working copy — never written into the history file.
     */
    static Path historyWorkingFile(Path root, Path historyFile, String repoRel) {
        return historyWorkingFile(root, historyFile, repoRel, null);
    }

    /**
     * As above, for a history that follows renames: {@code pathInCommit} is what the history file was called
     * in the selected commit. That row is the history file under its old name — it is compared with the file
     * as it is called now, not with a path that no longer exists.
     */
    static Path historyWorkingFile(Path root, Path historyFile, String repoRel, String pathInCommit) {
        if (historyFile != null
                && !Files.isDirectory(historyFile)
                && (repoRel.equals(pathInCommit)
                        || repoRel.equals(com.editora.git.GitService.repoRelative(root, historyFile)))) {
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
                            host.gitLogPanel().setCommitFiles(hash, files);
                        }
                    });
                    host.git().service().commitDetails(root, hash, details -> {
                        if (generation == gitLogGeneration) {
                            host.gitLogPanel().setCommitDetails(details);
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
                        .diffCommitFileVsWorking(
                                root,
                                hash,
                                repoRel,
                                historyWorkingFile(
                                        root,
                                        gitLogFilter,
                                        repoRel,
                                        host.gitLogPanel().followedPath(hash)));
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
                checkoutCommitIn(gitLogRoot, hash);
            }

            @Override
            public void reset(String hash, String mode) {
                resetIn(gitLogRoot, gitLogBranch, hash, mode);
            }

            @Override
            public void revert(String hash) {
                revertIn(gitLogRoot, hash);
            }

            @Override
            public void loadMore() {
                loadMoreGitLog();
            }

            @Override
            public void toggleAllBranches() {
                GitWindowCoordinator.this.toggleAllBranches();
            }

            @Override
            public void searchHistory(String query) {
                searchGitLog(query);
            }

            @Override
            public void reviewCommit(String hash) {
                reviewCommitIn(gitLogRoot, hash);
            }

            @Override
            public void compareCommits(String olderHash, String newerHash) {
                compareCommitsIn(gitLogRoot, olderHash, newerHash);
            }

            @Override
            public void newTag(String hash) {
                createTagIn(gitLogRoot, hash);
            }

            @Override
            public void checkoutTag(String tag) {
                checkoutTagIn(gitLogRoot, tag);
            }

            @Override
            public void pushTag(String tag) {
                pushTagIn(gitLogRoot, tag);
            }

            @Override
            public void deleteTag(String tag) {
                deleteTagIn(gitLogRoot, tag);
            }

            @Override
            public void commitNotLoaded(String hash) {
                host.setStatus(tr("status.git.log.notLoaded", com.editora.git.GitFormat.shortHash(hash)));
            }

            @Override
            public void createPatch(String hash) {
                host.git().patches().createCommitPatch(gitLogRoot, hash);
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

    /** What a {@code git.log.*} palette command does, given whether the log is on screen and what it selects. */
    enum LogCommand {
        /** The log is hidden: show and focus it, and ask for a commit. Nothing runs. */
        OPEN_AND_ASK,
        /** The log is on screen but selects nothing: focus it and ask. */
        ASK,
        /** The log is on screen with a commit selected: run on that commit. */
        RUN
    }

    /**
     * A {@code git.log.*} command (revert, cherry-pick, checkout, reset…) acts on "the selected commit". A
     * closed Git Log still remembers its last selection, so running then would revert or reset to a commit
     * the user cannot see — it never runs on a hidden selection.
     */
    static LogCommand logCommand(boolean logOpen, String selectedHash) {
        if (!logOpen) {
            return LogCommand.OPEN_AND_ASK;
        }
        return selectedHash == null || selectedHash.isBlank() ? LogCommand.ASK : LogCommand.RUN;
    }

    /**
     * Runs {@code op} on the commit selected in the <em>visible</em> Git Log; otherwise opens and focuses the
     * log and asks for a commit to be chosen there first (see {@link #logCommand}). Git-gated.
     */
    void withSelectedCommit(java.util.function.Consumer<String> op) {
        host.git().ifEnabled(() -> {
            if (host.git().reportIfNoRepo()) {
                return;
            }
            String hash = host.gitLogPanel().selectedHash();
            switch (logCommand(host.toolWindows().isOpen(host.gitLogToolWindow()), hash)) {
                case OPEN_AND_ASK -> {
                    openGitLog(gitLogFilter);
                    host.setStatus(tr("status.git.log.chooseCommit"));
                }
                case ASK -> {
                    host.toolWindows().open(host.gitLogToolWindow(), true);
                    host.setStatus(tr("status.git.noCommitSelected"));
                }
                case RUN -> op.accept(hash);
            }
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
     * by the caller before any dialog. Git is first asked what the reset takes off the branch
     * ({@link GitHeadMoveWarning}): Hard is always confirmed, with the dialog a file discard uses, naming the
     * uncommitted work and the commits that will be lost and the repository and branch they are lost from;
     * Soft and Mixed are confirmed when commits would be left on no other branch or tag.
     */
    private void resetIn(Path root, String branch, String hash, String mode) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String shortHash = com.editora.git.GitFormat.shortHash(hash);
        host.git().service().commitsLeftBehind(root, hash, true, left -> {
            if (GitHeadMoveWarning.resetNeedsConfirmation(mode, left)
                    && !host.git()
                            .confirmDestructive(
                                    tr("dialog.gitReset.title"),
                                    GitHeadMoveWarning.resetPrompt(mode, shortHash, branch, root, left),
                                    tr("hard".equals(mode) ? "dialog.gitReset.hard" : "dialog.gitReset.title"))) {
                return;
            }
            gitMutateIn(root, tr("status.git.reset", mode, shortHash), "reset", "--" + mode, hash);
        });
    }

    /**
     * {@code git checkout <hash>} in {@code root}: HEAD becomes detached, which the result says. Leaving a
     * detached HEAD that has commits no branch or tag reaches abandons them, so that is confirmed first.
     */
    private void checkoutCommitIn(Path root, String hash) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String shortHash = com.editora.git.GitFormat.shortHash(hash);
        host.git().service().commitsLeftBehind(root, hash, false, left -> {
            if (GitHeadMoveWarning.checkoutNeedsConfirmation(left)
                    && !host.git()
                            .confirmDestructive(
                                    tr("dialog.gitCheckout.title"),
                                    GitHeadMoveWarning.checkoutPrompt(shortHash, left),
                                    tr("dialog.gitCheckout.action"))) {
                return;
            }
            gitMutateIn(root, tr("status.git.checkedOutDetached", shortHash), "checkout", hash);
        });
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

    /**
     * Loads the first page of the log — the checked-out branch's history (or every branch's) when
     * {@code file} is null, else that file's, narrowed by the active history search. A reload of the listing
     * already on screen (after a Git command) asks again for as many commits as were loaded, so the list
     * keeps its depth, selection and scroll position.
     */
    void loadGitLog(Path file) {
        gitLogFilter = file;
        Path root = host.git().repoRoot();
        long generation = ++gitLogGeneration;
        gitLogLoading = false; // the requests in flight are now stale: their callbacks do nothing
        gitLogLoadingMore = false;
        if (!java.util.Objects.equals(root, gitLogRoot)) {
            // Rows of another repository must not stay on screen while this one's history is fetched.
            host.gitLogPanel().setLog(List.of(), null);
            gitLogListing = null;
        }
        gitLogRoot = root;
        String branch = host.git().branchName();
        gitLogBranch = branch;
        if (root == null) {
            host.git().service().cancelHistoryRead(); // a search still running is for a repository that is gone
            host.gitLogPanel().setLog(List.of(), null);
            gitLogListing = null;
            host.git().reportIfNoRepo(); // echoes "not a repo" / "git not installed"
            return;
        }
        com.editora.git.GitLogQuery query = com.editora.git.GitLogQuery.parse(gitLogSearch);
        boolean all = gitLogAllBranches;
        Object listing = List.of(root, file == null ? "" : file, all, gitLogSearch);
        int size = reloadSize(host.gitLogPanel().loadedCount(), listing.equals(gitLogListing), logPageSize);
        com.editora.git.GitLog.Request request = new com.editora.git.GitLog.Request(all, file, query, 0, size);
        GitLogPanel.View view = new GitLogPanel.View(
                file != null ? file.getFileName().toString() : null, branch, all, gitLogSearch, request.graphable());
        gitLogLoading = true;
        host.git().service().logPage(root, request, page -> {
            if (generation == gitLogGeneration) {
                gitLogLoading = false;
                gitLogListing = listing;
                host.gitLogPanel().setLog(page, view);
            }
        });
    }

    /**
     * Loads the page after the rows on screen (scrolling near the end, or "Load more").
     *
     * <p>A page is addressed by how many commits to skip, which is only right while the history above it
     * has not moved — and a commit made in a terminal moves it without Editora hearing of it. So the request
     * overlaps the loaded rows by one: the page must begin with the last loaded commit. When it does not, the
     * listing is reloaded to the new depth instead of appending rows that would repeat or skip a commit.
     */
    void loadMoreGitLog() {
        GitLogPanel panel = host.gitLogPanel();
        Path root = gitLogRoot;
        String anchor = panel.lastLoadedHash();
        if (root == null || anchor == null || gitLogLoading || gitLogLoadingMore || !panel.hasMore()) {
            return;
        }
        long generation = gitLogGeneration;
        int loaded = panel.loadedCount();
        Path file = gitLogFilter;
        gitLogLoadingMore = true;
        com.editora.git.GitLog.Request request = new com.editora.git.GitLog.Request(
                gitLogAllBranches, file, com.editora.git.GitLogQuery.parse(gitLogSearch), loaded - 1, logPageSize + 1);
        host.git().service().logPage(root, request, page -> {
            if (generation != gitLogGeneration) {
                return; // a full load took over (it reset the flag)
            }
            gitLogLoadingMore = false;
            if (continues(page, anchor)) {
                panel.appendLog(page.drop(1));
            } else {
                loadGitLog(file); // history moved: reload to the depth already on screen
            }
        });
    }

    /** Whether {@code page}, requested with a one-commit overlap, begins with the last loaded commit. Pure. */
    static boolean continues(com.editora.git.GitLog.Page page, String lastLoadedHash) {
        return page.error().isEmpty()
                && !page.entries().isEmpty()
                && page.entries().get(0).hash().equals(lastLoadedHash);
    }

    /** Switches the log between the checked-out branch and every branch, remote and tag, and reloads it. */
    void toggleAllBranches() {
        gitLogAllBranches = !gitLogAllBranches;
        host.setStatus(tr(gitLogAllBranches ? "status.git.log.allBranches" : "status.git.log.currentBranch"));
        showOrReloadGitLog();
    }

    /** Runs {@code query} over the whole history and lists the result; a blank query ends the search. */
    void searchGitLog(String query) {
        gitLogSearch = query == null ? "" : query.strip();
        showOrReloadGitLog();
    }

    /** Opens the log when it is hidden (which loads it); reloads it when it is on screen. */
    private void showOrReloadGitLog() {
        if (host.toolWindows().isOpen(host.gitLogToolWindow())) {
            loadGitLog(gitLogFilter);
        } else {
            host.toolWindows().open(host.gitLogToolWindow());
        }
    }

    /** {@code git.log.search}: shows the log with the caret in its filter box, where Enter searches history. */
    void focusGitLogSearch() {
        if (host.git().reportIfNoRepo()) {
            return;
        }
        host.toolWindows().open(host.gitLogToolWindow(), true);
        host.gitLogPanel().focusSearch();
        host.setStatus(tr("status.git.log.searchHint"));
    }

    /** {@code git.log.loadMore}: the next page of an open log. */
    void loadMoreCommand() {
        if (!host.toolWindows().isOpen(host.gitLogToolWindow())) {
            openGitLog(gitLogFilter);
        } else if (!host.gitLogPanel().hasMore()) {
            host.setStatus(tr("status.git.log.noMore"));
        } else {
            loadMoreGitLog();
        }
    }

    // --- whole-commit review and two-commit compare ------------------------------------------------

    /** Opens every file {@code hash} changed against its first parent as one multi-file review tab. */
    void reviewCommitIn(Path root, String hash) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String shortHash = com.editora.git.GitFormat.shortHash(hash);
        // Against the first parent — the pair the changed-files list and the single-file diff show. A root
        // commit has no parent; all of its files are additions, whose left side is empty without a lookup.
        host.git().service().commitFiles(root, hash, files -> {
            if (files.isEmpty()) {
                host.setStatus(tr("status.git.log.noFiles", shortHash));
                return;
            }
            host.diffCoordinator()
                    .reviewRevisions(
                            root,
                            tr("diff.title.commitReview", shortHash),
                            hash + "^1",
                            tr("diff.side.parent"),
                            hash,
                            tr("diff.title.vsCommitShort", shortHash),
                            files,
                            false);
        });
    }

    /** Opens the files that differ between two commits (older on the left) as one review tab. */
    void compareCommitsIn(Path root, String olderHash, String newerHash) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String older = com.editora.git.GitFormat.shortHash(olderHash);
        String newer = com.editora.git.GitFormat.shortHash(newerHash);
        host.git().service().diffFiles(root, olderHash, newerHash, result -> {
            if (!result.ok()) {
                host.setStatus(tr("status.git.log.compareFailed", result.error()));
                return;
            }
            if (result.files().isEmpty()) {
                host.setStatus(tr("status.git.log.identical", older, newer));
                return;
            }
            host.diffCoordinator()
                    .reviewRevisions(
                            root,
                            tr("diff.title.commitCompare", older, newer),
                            olderHash,
                            tr("diff.title.vsCommitShort", older),
                            newerHash,
                            tr("diff.title.vsCommitShort", newer),
                            result.files(),
                            result.truncated());
        });
    }

    /** {@code git.log.compareSelected}: needs exactly two commits selected in the visible log. */
    void compareSelectedCommits() {
        withSelectedCommit(hash -> {
            List<String> selected = host.gitLogPanel().selectedHashes();
            if (selected.size() != 2) {
                host.setStatus(tr("status.git.log.selectTwo"));
                return;
            }
            compareCommitsIn(gitLogRoot, selected.get(1), selected.get(0));
        });
    }

    // --- revert (a merge needs a mainline) ---------------------------------------------------------

    /**
     * {@code git revert --no-edit} arguments. A merge has no single change to undo: git must be told which
     * parent is the mainline ({@code -m}), and without it fails with "is a merge but no -m option was given".
     */
    static String[] revertArgs(String hash, int mainline) {
        return mainline > 0
                ? new String[] {"revert", "--no-edit", "-m", String.valueOf(mainline), hash}
                : new String[] {"revert", "--no-edit", hash};
    }

    /** Reverts {@code hash} in {@code root}; for a merge commit the mainline parent is asked for first. */
    void revertIn(Path root, String hash) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String done = tr("status.git.reverted", com.editora.git.GitFormat.shortHash(hash));
        com.editora.git.GitLog.Entry row = host.gitLogPanel().entry(hash);
        if (row != null) {
            revertWithParents(root, hash, row.parents(), done);
            return;
        }
        // Not a loaded row (the command can name any commit): ask git for its parents.
        host.git()
                .service()
                .commitDetails(
                        root,
                        hash,
                        details ->
                                revertWithParents(root, hash, details == null ? List.of() : details.parents(), done));
    }

    private void revertWithParents(Path root, String hash, List<String> parents, String done) {
        int mainline = 0;
        if (parents.size() > 1) {
            Integer chosen = mainlineChooser.apply(hash, parents);
            if (chosen == null) {
                host.setStatus(tr("status.git.revertMergeCancelled"));
                return;
            }
            mainline = chosen;
        }
        gitMutateIn(root, done, revertArgs(hash, mainline));
    }

    /** The mainline dialog: one choice per parent, named by its short hash and, when loaded, its subject. */
    private Integer chooseMainline(String hash, List<String> parents) {
        List<String> choices = new java.util.ArrayList<>();
        for (int i = 0; i < parents.size(); i++) {
            com.editora.git.GitLog.Entry parent = host.gitLogPanel().entry(parents.get(i));
            choices.add(tr(
                    "dialog.revertMerge.parent",
                    i + 1,
                    com.editora.git.GitFormat.shortHash(parents.get(i)),
                    parent == null ? "" : parent.subject()));
        }
        javafx.scene.control.ChoiceDialog<String> d = new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
        d.initOwner(host.stage());
        d.setTitle(tr("dialog.revertMerge.title"));
        d.setHeaderText(null);
        d.setContentText(tr("dialog.revertMerge.content", com.editora.git.GitFormat.shortHash(hash)));
        Dialogs.styled(d);
        return d.showAndWait().map(choice -> choices.indexOf(choice) + 1).orElse(null);
    }

    // --- tags --------------------------------------------------------------------------------------

    /**
     * {@code git tag} arguments for a new tag at {@code revision} (null: {@code HEAD}): lightweight, or
     * annotated when a message is given. The name has passed {@link com.editora.git.GitRefNames#isValidTag}, so
     * it cannot be an option; the message follows {@code -m} as its value whatever it begins with.
     */
    static String[] tagArgs(String name, String message, String revision) {
        List<String> args = new java.util.ArrayList<>(List.of("tag"));
        if (message != null && !message.isBlank()) {
            args.addAll(List.of("-a", "-m", message.strip()));
        }
        args.add(name);
        if (revision != null) {
            args.add(revision);
        }
        return args.toArray(String[]::new);
    }

    /** {@code git.tag.create}: at the commit selected in the visible log, else at {@code HEAD}. */
    void createTagCommand() {
        if (host.git().reportIfNoRepo()) {
            return;
        }
        boolean logOpen = host.toolWindows().isOpen(host.gitLogToolWindow());
        String selected = logOpen ? host.gitLogPanel().selectedHash() : null;
        createTagIn(selected != null ? gitLogRoot : host.git().repoRoot(), selected);
    }

    /** Asks for a tag name, then a message (blank: a lightweight tag), and creates the tag at {@code hash}. */
    void createTagIn(Path root, String hash) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        String at = hash == null ? "HEAD" : com.editora.git.GitFormat.shortHash(hash);
        host.promptText(tr("dialog.newTag.title"), tr("dialog.newTag.content", at), "", input -> {
            String name = input.strip();
            if (name.isEmpty()) {
                return;
            }
            if (!com.editora.git.GitRefNames.isValidTag(name)) {
                host.setStatus(tr("status.git.tag.invalidName", name));
                return;
            }
            // The first prompt is still closing: open the second one after it.
            javafx.application.Platform.runLater(() -> host.promptText(
                    tr("dialog.newTag.title"),
                    tr("dialog.newTag.message", name),
                    "",
                    message -> createTag(root, name, message, hash)));
        });
    }

    /** Creates the tag (validated here too: this is the one place the name reaches a command line). */
    void createTag(Path root, String name, String message, String hash) {
        if (!com.editora.git.GitRefNames.isValidTag(name)
                || (hash != null && !com.editora.git.GitSafety.isSafeRevision(hash))) {
            host.setStatus(tr("status.git.tag.invalidName", name));
            return;
        }
        gitMutateIn(root, tr("status.git.tag.created", name), tagArgs(name, message, hash));
    }

    /** Checks out a tag (detached HEAD). {@code refs/tags/…} so a branch of the same name is not meant. */
    void checkoutTagIn(Path root, String tag) {
        if (validTag(tag)) {
            gitMutateIn(root, tr("status.git.checkedOut", tag), "checkout", "refs/tags/" + tag);
        }
    }

    /** Deletes a local tag after confirmation; the tag on a remote is not touched. */
    void deleteTagIn(Path root, String tag) {
        if (root != null && validTag(tag) && tagDeleteConfirmer.test(tag, root.toString())) {
            gitMutateIn(root, tr("status.git.tag.deleted", tag), "tag", "-d", tag);
        }
    }

    /**
     * Pushes one tag to the branch's push remote. A push runs in the active repository, so it is refused
     * when that is no longer the one the tag was listed in.
     */
    void pushTagIn(Path root, String tag) {
        if (root == null || !validTag(tag)) {
            return;
        }
        inRoot(
                        root,
                        () -> host.git()
                                .gitSync(
                                        tr("gitlabel.pushTag", tag),
                                        "push",
                                        com.editora.git.GitService.PUSH_REMOTE,
                                        "refs/tags/" + tag))
                .run();
    }

    /** A tag name comes from repository data; one git itself would refuse to create is not passed on. */
    private boolean validTag(String tag) {
        if (com.editora.git.GitRefNames.isValidTag(tag)) {
            return true;
        }
        host.setStatus(tr("status.git.unsafeRef", String.valueOf(tag)));
        return false;
    }

    /** {@code git.tag.delete} / {@code git.tag.push} / {@code git.tag.checkout}: pick a tag, then act on it. */
    void pickTagThen(String titleKey, java.util.function.BiConsumer<Path, String> action) {
        if (host.git().reportIfNoRepo()) {
            return;
        }
        Path root = host.git().repoRoot(); // the repository the picker lists, not whichever is active on choose
        host.diffCoordinator().pickTag(root, tr(titleKey), tag -> action.accept(root, tag));
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
        if (!sameRoot && branchPopup.isShown()) {
            branchPopup.hide(); // it lists the branches of the repository that just stopped being active
        }
        gitLogBranch = branch;
        if (!sameRoot) {
            gitLogRoot = root;
            gitLogGeneration++;
            gitLogLoading = false;
            gitLogLoadingMore = false;
            if (gitLogFilter != null && (root == null || !gitLogFilter.startsWith(root))) {
                gitLogFilter = null; // a file history of the previous repository
            }
            gitLogSearch = ""; // a search of the previous repository's history
            gitLogListing = null;
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

    /**
     * Runs the mutation in {@code root}, reports and refreshes. The log reloads through {@link #gitMutated},
     * as it does after every other Git command.
     */
    private void gitMutateIn(Path root, String successMessage, String... args) {
        if (root == null) {
            host.git().reportIfNoRepo();
            return;
        }
        host.git().mutateWorkingTree(root, successMessage, null, args);
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
