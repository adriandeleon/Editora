package com.editora.ui;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.editora.git.GitFormat;
import com.editora.git.GitOutcome;
import com.editora.git.GitRefNames;
import com.editora.git.GitRemotes;
import com.editora.git.GitSafety;
import com.editora.git.GitService;
import com.editora.git.GitWorktrees;
import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * Branch, remote and work-tree management for a window: delete / rename / merge / rebase / new-branch-from /
 * upstream / compare / checkout-by-name, the push variants (force-with-lease, push to another remote or
 * name, tags, and the answer to a rejected push), the remotes manager and the work-trees manager.
 *
 * <p>Rules it owns:
 * <ul>
 *   <li>Every command runs in the repository root captured when the user asked — before any picker, prompt
 *       or confirmation, during which another repository can become the active one.
 *   <li>Every name that reaches git is checked first ({@link GitSafety#isSafeRevision}, and
 *       {@link GitRefNames} for a name the user typed) and is placed after {@code --end-of-options}
 *       ({@link GitService#guarded}); {@code checkout} takes its revision before a closing {@code --} instead,
 *       which every supported git reads the same way.
 *   <li>A command that may rewrite working-tree files (merge, rebase, checkout, pull, removing a work tree)
 *       goes through {@link GitCoordinator#aroundWorkingTreeMutation}, so pending saves are superseded and
 *       clean buffers reload, exactly as a branch switch does. The rest only move refs or configuration.
 *   <li>A merge, rebase or pull that stops on conflicts is not a failure ({@link GitOutcome#CONFLICT}): git's
 *       own message is shown with "resolve them and continue", never the "command failed" dialog.
 *   <li>Nothing is destroyed without the danger-styled confirmation naming what is lost
 *       ({@link GitCoordinator#confirmDestructive}); a force push is always {@code --force-with-lease}.
 * </ul>
 *
 * <p>Built from {@link GitWindowCoordinator}'s host; everything else it needs comes from the Git engine
 * ({@link GitCoordinator}) and its {@link CoordinatorHost}, resolved when a command runs.
 */
final class GitBranchCoordinator {

    /** What a ref offered in a picker is. */
    enum RefKind {
        LOCAL,
        REMOTE,
        TAG
    }

    /** A ref offered in a picker. */
    record Ref(String name, RefKind kind) {}

    private final GitWindowCoordinator.Host windowHost;

    /** Opens a folder as a project in its own window; installed by the command registrar. */
    private Consumer<Path> windowOpener = folder -> {};

    GitBranchCoordinator(GitWindowCoordinator.Host windowHost) {
        this.windowHost = windowHost;
    }

    void setWindowOpener(Consumer<Path> opener) {
        windowOpener = opener == null ? folder -> {} : opener;
    }

    private GitCoordinator git() {
        return windowHost.git();
    }

    private CoordinatorHost ui() {
        return git().host();
    }

    private GitService service() {
        return git().service();
    }

    /** The active repository root, or null after reporting that there is none. */
    private Path rootOrReport() {
        return git().reportIfNoRepo() ? null : git().repoRoot();
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }

    // --- running -----------------------------------------------------------------------------------

    /** Shows {@code git <subcommand>…} in the background-task segment until the command reports back. */
    private Consumer<ProcessRunner.Result> tracked(String[] args, Consumer<ProcessRunner.Result> onResult) {
        AutoCloseable task =
                ui().startBackgroundTask(tr("status.gitRunning", "git " + GitCoordinator.subcommand(args)));
        return result -> {
            try {
                task.close();
            } catch (Exception ignored) {
                // the indicator handle has nothing to fail with; never let it swallow the result
            }
            onResult.accept(result);
        };
    }

    /** A command that moves refs or configuration only (branch -d/-m, remote add, worktree add…). */
    private void runLocal(Path root, String[] args, Consumer<ProcessRunner.Result> report) {
        service()
                .runWorktreeMutation(
                        root,
                        tracked(args, result -> {
                            report.accept(result);
                            git().afterMutation();
                        }),
                        args);
    }

    /** A command that may rewrite working-tree files: the same boundary a branch switch goes through. */
    private void runTree(Path root, String[] args, Consumer<ProcessRunner.Result> report) {
        git().aroundWorkingTreeMutation(
                        root, done -> service().runWorktreeMutation(root, tracked(args, done), args), report, null);
    }

    /** A command that talks to a remote and leaves the working tree alone (push, fetch, remote prune). */
    private void runRemote(Path root, String[] args, Consumer<ProcessRunner.Result> report) {
        service()
                .runNetwork(
                        root,
                        tracked(args, result -> {
                            report.accept(result);
                            git().afterMutation();
                        }),
                        args);
    }

    /** Refuses (after reporting) a name git would read as an option or that holds a control character. */
    private boolean unsafe(String name) {
        if (GitSafety.isSafeRevision(name)) {
            return false;
        }
        ui().setError(tr("status.git.unsafeRef", nz(name)));
        return true;
    }

    /** Refuses (after reporting) a typed name that is not a valid branch name. */
    private boolean invalidBranchName(String name) {
        if (GitRefNames.isValidBranch(name)) {
            return false;
        }
        ui().setError(tr("status.git.invalidBranchName", nz(name)));
        return true;
    }

    /**
     * The error dialog, after the refresh that follows the command: shown from inside the report it would
     * hold the status bar and the Commit window on the state from before the command until it is dismissed.
     */
    private void fail(String summary, ProcessRunner.Result result) {
        Platform.runLater(() -> git().gitError(summary, result.message()));
    }

    /**
     * A merge, rebase or pull stopped for the user to resolve conflicts. That is the operation working as
     * designed, with the repository left mid-operation: say so, with git's own account of which files.
     */
    private void conflictStop(String operation, ProcessRunner.Result result) {
        // No dialog: the Commit window's operation banner and Conflicts group say what happened and offer
        // Continue / Abort, so it is opened and the status bar names the command that stopped.
        git().conflictsNeedAttention(tr("status.git.opStoppedOnConflicts", operation));
    }

    /** The usual three-way report of a working-tree command: done, stopped on conflicts, or failed. */
    private void report(ProcessRunner.Result result, String operation, String success, String failure) {
        switch (GitOutcome.of(result)) {
            case OK -> ui().setStatus(success);
            case CONFLICT -> conflictStop(operation, result);
            default -> fail(failure, result);
        }
    }

    // --- pickers -----------------------------------------------------------------------------------

    /**
     * Lists the refs of {@code kinds} in {@code root} and hands the picked one to {@code onPick}. The branch
     * named {@code exclude} (the current one, for commands that cannot act on it) is left out.
     */
    private void pickRef(Path root, String title, Set<RefKind> kinds, String exclude, Consumer<Ref> onPick) {
        service().branches(root, branches -> {
            List<Ref> refs = new ArrayList<>();
            if (kinds.contains(RefKind.LOCAL)) {
                for (GitService.BranchInfo branch : branches.local()) {
                    if (!branch.name().equals(exclude)) {
                        refs.add(new Ref(branch.name(), RefKind.LOCAL));
                    }
                }
            }
            if (kinds.contains(RefKind.REMOTE)) {
                branches.remote().forEach(name -> refs.add(new Ref(name, RefKind.REMOTE)));
            }
            if (!kinds.contains(RefKind.TAG)) {
                showRefPicker(title, refs, onPick);
                return;
            }
            service().tags(root, tags -> {
                tags.forEach(tag -> refs.add(new Ref(tag, RefKind.TAG)));
                showRefPicker(title, refs, onPick);
            });
        });
    }

    private void showRefPicker(String title, List<Ref> refs, Consumer<Ref> onPick) {
        if (refs.isEmpty()) {
            ui().setStatus(tr("status.git.noRefsToPick"));
            return;
        }
        QuickOpen<Ref> picker = new QuickOpen<>(
                title,
                tr("diff.branchPickerPrompt"),
                () -> refs,
                Ref::name,
                ref -> switch (ref.kind()) {
                    case LOCAL -> tr("diff.branch.local");
                    case REMOTE -> tr("diff.branch.remote");
                    case TAG -> tr("diff.tag");
                },
                onPick);
        picker.setOverlayHost(ui().overlayHost());
        picker.show(ui().window());
    }

    /**
     * Hands a remote of {@code root} to {@code onPick}: the only one directly, one of several through a
     * picker. With none there is nothing to push to or fetch from, and that is reported.
     */
    private void withRemote(Path root, String title, Consumer<String> onPick) {
        service().remotes(root, remotes -> {
            if (remotes.isEmpty()) {
                ui().setStatus(tr("status.git.noRemotes"));
            } else if (remotes.size() == 1) {
                onPick.accept(remotes.get(0).name());
            } else {
                QuickOpen<GitRemotes.Remote> picker = new QuickOpen<>(
                        title,
                        tr("remotes.pickPrompt"),
                        () -> remotes,
                        GitRemotes.Remote::name,
                        GitBranchCoordinator::remoteDetail,
                        remote -> onPick.accept(remote.name()));
                picker.setOverlayHost(ui().overlayHost());
                picker.show(ui().window());
            }
        });
    }

    // --- delete ------------------------------------------------------------------------------------

    /** {@code git.deleteBranch}: picks a local branch other than the current one and deletes it. */
    void deleteBranch() {
        Path root = rootOrReport();
        if (root != null) {
            String current = git().branchName();
            pickRef(
                    root,
                    tr("branch.pick.delete"),
                    EnumSet.of(RefKind.LOCAL),
                    current,
                    ref -> deleteBranch(root, current, ref.name()));
        }
    }

    /**
     * Deletes local branch {@code name} with the safe {@code branch -d}. When git refuses because the branch
     * has unmerged commits, a danger-styled confirmation names how many would be left without a branch
     * before {@code -D} runs. The checked-out branch is never deleted.
     */
    void deleteBranch(Path root, String current, String name) {
        if (root == null || name == null || unsafe(name)) {
            return;
        }
        if (name.equals(current)) {
            ui().setStatus(tr("status.git.deleteCurrentBranch", name));
            return;
        }
        runLocal(root, GitService.guarded(List.of("branch", "-d"), name), result -> {
            switch (GitOutcome.of(result)) {
                case OK -> ui().setStatus(tr("status.git.deletedBranch", name));
                case NOT_FULLY_MERGED -> confirmForceDelete(root, name);
                default -> fail(tr("status.git.deleteBranchFailed", name), result);
            }
        });
    }

    private void confirmForceDelete(Path root, String name) {
        service().unmergedCount(root, name, count -> {
            String message = count > 0
                    ? tr(
                            count == 1 ? "dialog.deleteBranch.unmerged.one" : "dialog.deleteBranch.unmerged.many",
                            name,
                            count)
                    : tr("dialog.deleteBranch.unmerged.unknown", name);
            if (!git().confirmDestructive(tr("dialog.deleteBranch.title"), message, tr("dialog.deleteBranch.force"))) {
                ui().setStatus(tr("status.git.branchKept", name));
                return;
            }
            runLocal(root, GitService.guarded(List.of("branch", "-D"), name), result -> {
                if (result.ok()) {
                    ui().setStatus(tr("status.git.deletedBranch", name));
                } else {
                    fail(tr("status.git.deleteBranchFailed", name), result);
                }
            });
        });
    }

    /** {@code git.deleteRemoteBranch}: picks a remote branch and, after confirmation, deletes it there. */
    void deleteRemoteBranch() {
        Path root = rootOrReport();
        if (root == null) {
            return;
        }
        service().branches(root, branches -> {
            List<Ref> refs = branches.remote().stream()
                    .map(name -> new Ref(name, RefKind.REMOTE))
                    .toList();
            showRefPicker(
                    tr("branch.pick.deleteRemote"),
                    refs,
                    ref -> deleteRemoteBranch(root, ref.name(), branches.remoteNames()));
        });
    }

    /**
     * {@code git push <remote> --delete <branch>} for a remote-tracking branch such as {@code origin/topic}.
     * It removes the branch for everyone who uses that remote, so it is always confirmed.
     */
    void deleteRemoteBranch(Path root, String remoteBranch, List<String> remoteNames) {
        GitRemotes.RemoteBranch target = GitRemotes.split(remoteBranch, remoteNames);
        String[] args =
                target == null ? new String[0] : GitService.deleteRemoteBranchArgs(target.remote(), target.branch());
        if (root == null || args.length == 0) {
            ui().setError(tr("status.git.unsafeRef", nz(remoteBranch)));
            return;
        }
        if (!git().confirmDestructive(
                        tr("dialog.deleteRemoteBranch.title"),
                        tr("dialog.deleteRemoteBranch.confirm", target.branch(), target.remote()),
                        tr("dialog.deleteRemoteBranch"))) {
            return;
        }
        ui().setStatus(tr("status.gitRunning", tr("gitlabel.push")));
        runRemote(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.deletedRemoteBranch", target.branch(), target.remote()));
            } else {
                fail(tr("status.git.deleteBranchFailed", remoteBranch), result);
            }
        });
    }

    // --- rename ------------------------------------------------------------------------------------

    /** {@code git.renameBranch}: picks a local branch (the current one is listed first) and asks its new name. */
    void renameBranch() {
        Path root = rootOrReport();
        if (root != null) {
            pickRef(
                    root,
                    tr("branch.pick.rename"),
                    EnumSet.of(RefKind.LOCAL),
                    null,
                    ref -> promptRename(root, ref.name()));
        }
    }

    void promptRename(Path root, String old) {
        ui().promptText(
                        tr("dialog.renameBranch.title"),
                        tr("dialog.renameBranch.content", old),
                        old,
                        input -> renameBranch(root, old, input.strip()));
    }

    /** {@code git branch -m <old> <name>}; the upstream configuration moves with the branch. */
    void renameBranch(Path root, String old, String name) {
        if (root == null || name.isEmpty() || name.equals(old) || invalidBranchName(name) || unsafe(old)) {
            return;
        }
        runLocal(root, GitService.guarded(List.of("branch", "-m"), old, name), result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.renamedBranch", old, name));
            } else {
                fail(tr("status.git.renameBranchFailed", old), result);
            }
        });
    }

    // --- merge / rebase ----------------------------------------------------------------------------

    /** {@code git.mergeBranch}: picks a branch and merges it into the current one. */
    void mergeBranch() {
        Path root = rootOrReport();
        if (root != null) {
            String current = git().branchName();
            pickRef(
                    root,
                    tr("branch.pick.merge", current),
                    EnumSet.of(RefKind.LOCAL, RefKind.REMOTE),
                    current,
                    ref -> merge(root, current, ref.name()));
        }
    }

    /**
     * {@code git merge --no-edit <name>} into the checked-out branch ({@code --no-edit}: there is no terminal
     * for git to open an editor in). A stop on conflicts leaves the merge in progress and is reported as such.
     */
    void merge(Path root, String current, String name) {
        if (root == null || unsafe(name)) {
            return;
        }
        runTree(
                root,
                GitService.guarded(List.of("merge", "--no-edit"), name),
                result -> report(
                        result,
                        tr("gitlabel.merge"),
                        nz(result.out()).contains("Already up to date")
                                ? tr("status.git.alreadyUpToDate", name)
                                : tr("status.git.merged", name, nz(current)),
                        tr("status.git.mergeFailed", name)));
    }

    /** {@code git.rebaseOnto}: picks a branch and rebases the current one onto it. */
    void rebaseOnto() {
        Path root = rootOrReport();
        if (root != null) {
            String current = git().branchName();
            pickRef(
                    root,
                    tr("branch.pick.rebase", current),
                    EnumSet.of(RefKind.LOCAL, RefKind.REMOTE),
                    current,
                    ref -> rebase(root, current, ref.name()));
        }
    }

    /** {@code git rebase <name>}: replays the current branch's own commits on top of {@code name}. */
    void rebase(Path root, String current, String name) {
        if (root == null || unsafe(name)) {
            return;
        }
        runTree(
                root,
                GitService.guarded(List.of("rebase"), name),
                result -> report(
                        result,
                        tr("gitlabel.rebase"),
                        nz(result.out()).contains("is up to date")
                                ? tr("status.git.alreadyUpToDate", name)
                                : tr("status.git.rebased", nz(current), name),
                        tr("status.git.rebaseFailed", name)));
    }

    // --- new branch from / checkout by name --------------------------------------------------------

    /** {@code git.newBranchFrom}: picks any branch or tag, then asks the new branch's name. */
    void newBranchFrom() {
        Path root = rootOrReport();
        if (root != null) {
            pickRef(
                    root,
                    tr("branch.pick.newFrom"),
                    EnumSet.allOf(RefKind.class),
                    null,
                    ref -> promptNewBranchFrom(root, ref.name()));
        }
    }

    /** The name form of a new branch: its name, and whether to switch to it (on by default). */
    void promptNewBranchFrom(Path root, String start) {
        TextField name = new TextField();
        name.setPrefColumnCount(32);
        com.editora.command.TextInputKeymap.installShared(name);
        CheckBox switchTo = new CheckBox(tr("dialog.newBranchFrom.switch"));
        switchTo.setSelected(true);
        VBox body = new VBox(6, new Label(tr("dialog.newBranchFrom.content", start)), name, switchTo);
        OverlayInput.show(
                ui().overlayHost(),
                tr("dialog.newBranchFrom.title"),
                body,
                name,
                tr("dialog.ok"),
                null,
                () -> newBranchFrom(root, start, name.getText().strip(), switchTo.isSelected()),
                null,
                false);
    }

    /**
     * Creates branch {@code name} at {@code start}: {@code checkout -b} when switching (a working-tree
     * command), else {@code branch} — the working tree and the current branch stay as they are.
     */
    void newBranchFrom(Path root, String start, String name, boolean switchTo) {
        if (root == null || name.isEmpty() || invalidBranchName(name) || unsafe(start)) {
            return;
        }
        Consumer<ProcessRunner.Result> report = result -> {
            if (result.ok()) {
                ui().setStatus(tr(switchTo ? "status.createdBranch" : "status.git.createdBranchFrom", name, start));
            } else {
                fail(tr("status.git.createBranchFailed", name), result);
            }
        };
        if (switchTo) {
            runTree(root, new String[] {"checkout", "-b", name, start, "--"}, report);
        } else {
            runLocal(root, GitService.guarded(List.of("branch"), name, start), report);
        }
    }

    /** {@code git.checkoutRevision}: asks for a tag, branch or commit by name and checks it out. */
    void checkoutRevision() {
        Path root = rootOrReport();
        if (root != null) {
            ui().promptText(
                            tr("dialog.checkoutRevision.title"),
                            tr("dialog.checkoutRevision.content"),
                            "",
                            input -> checkoutRevision(root, input.strip()));
        }
    }

    /**
     * {@code git checkout <revision> --}. The closing {@code --} makes the argument a revision even when a
     * file of the same name exists; a tag or a commit leaves HEAD detached, which the status bar then shows.
     */
    void checkoutRevision(Path root, String revision) {
        if (root == null || revision.isEmpty() || unsafe(revision)) {
            return;
        }
        runTree(root, new String[] {"checkout", revision, "--"}, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.checkedOut", revision));
            } else {
                fail(tr("status.git.checkoutFailed", revision), result);
            }
        });
    }

    // --- upstream ----------------------------------------------------------------------------------

    /** {@code git.setUpstream}: picks the remote branch the current branch should track. */
    void setUpstream() {
        Path root = rootOrReport();
        if (root == null) {
            return;
        }
        String current = git().branchName();
        if (GitService.isDetached(current)) {
            ui().setStatus(tr("status.git.detachedNoBranch"));
            return;
        }
        promptUpstream(root, current);
    }

    void promptUpstream(Path root, String branch) {
        pickRef(
                root,
                tr("branch.pick.upstream", branch),
                EnumSet.of(RefKind.REMOTE),
                null,
                ref -> setUpstream(root, branch, ref.name()));
    }

    /** {@code git branch --set-upstream-to=<upstream> <branch>}. */
    void setUpstream(Path root, String branch, String upstream) {
        if (root == null || unsafe(branch) || unsafe(upstream)) {
            return;
        }
        runLocal(root, GitService.guarded(List.of("branch", "--set-upstream-to=" + upstream), branch), result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.upstreamSet", branch, upstream));
            } else {
                fail(tr("status.git.upstreamFailed", branch), result);
            }
        });
    }

    /** {@code git.unsetUpstream}: the current branch stops tracking its upstream. */
    void unsetUpstream() {
        Path root = rootOrReport();
        if (root == null) {
            return;
        }
        String current = git().branchName();
        if (GitService.isDetached(current)) {
            ui().setStatus(tr("status.git.detachedNoBranch"));
            return;
        }
        unsetUpstream(root, current);
    }

    /** {@code git branch --unset-upstream <branch>}. */
    void unsetUpstream(Path root, String branch) {
        if (root == null || unsafe(branch)) {
            return;
        }
        runLocal(root, GitService.guarded(List.of("branch", "--unset-upstream"), branch), result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.upstreamUnset", branch));
            } else {
                fail(tr("status.git.upstreamFailed", branch), result);
            }
        });
    }

    // --- compare -----------------------------------------------------------------------------------

    /** {@code git.compareBranch}: picks a branch or tag and reviews the files that differ from the current one. */
    void compareWithCurrent() {
        Path root = rootOrReport();
        if (root != null) {
            String current = git().branchName();
            pickRef(
                    root,
                    tr("branch.pick.compare", current),
                    EnumSet.allOf(RefKind.class),
                    current,
                    ref -> compare(root, current, ref.name()));
        }
    }

    /** The changed-files review between {@code other} (left) and the checked-out commit (right). */
    void compare(Path root, String current, String other) {
        if (root == null || unsafe(other)) {
            return;
        }
        windowHost.diffCoordinator().compareRefs(root, other, GitService.isDetached(current) ? "HEAD" : current);
    }

    // --- push --------------------------------------------------------------------------------------

    /**
     * {@code git.push}: the ordinary push ({@link GitService#pushArgs}). A rejection because the remote has
     * commits this branch lacks is answered with a choice — pull then push, force with lease, or leave it —
     * instead of git's error alone.
     */
    void push() {
        Path root = rootOrReport();
        if (root != null) {
            String branch = git().branchName();
            String upstream = nz(git().upstream());
            push(root, branch, upstream, GitService.pushArgs(branch, upstream), true);
        }
    }

    private void push(Path root, String branch, String upstream, String[] args, boolean offerOnRejection) {
        String label = tr("gitlabel.push");
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, args, result -> {
            GitOutcome outcome = GitOutcome.of(result);
            if (outcome == GitOutcome.OK) {
                ui().setStatus(tr("status.gitDone", label));
            } else if (outcome == GitOutcome.NON_FAST_FORWARD && offerOnRejection && !GitService.isDetached(branch)) {
                ui().setStatus(tr("status.git.pushRejected"));
                Platform.runLater(() -> offerAfterRejection(root, branch, upstream));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
        });
    }

    /** The answers to a rejected push. */
    enum RejectedPushChoice {
        PULL_THEN_PUSH,
        FORCE_WITH_LEASE,
        CANCEL
    }

    private void offerAfterRejection(Path root, String branch, String upstream) {
        ButtonType pull = new ButtonType(tr("dialog.pushRejected.pull"), ButtonBar.ButtonData.YES);
        ButtonType force = new ButtonType(tr("dialog.pushRejected.force"), ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(
                Alert.AlertType.WARNING,
                tr(
                        "dialog.pushRejected.content",
                        branch,
                        upstream.isBlank() ? tr("dialog.pushRejected.remote") : upstream),
                pull,
                force,
                ButtonType.CANCEL);
        alert.initOwner(ui().window());
        alert.setTitle(tr("dialog.pushRejected.title"));
        alert.setHeaderText(null);
        alert.getDialogPane().lookupButton(force).getStyleClass().add("danger");
        ButtonType answer = alert.showAndWait().orElse(ButtonType.CANCEL);
        answerRejectedPush(
                root,
                branch,
                upstream,
                answer == pull
                        ? RejectedPushChoice.PULL_THEN_PUSH
                        : answer == force ? RejectedPushChoice.FORCE_WITH_LEASE : RejectedPushChoice.CANCEL);
    }

    void answerRejectedPush(Path root, String branch, String upstream, RejectedPushChoice choice) {
        switch (choice) {
            case PULL_THEN_PUSH -> pullThenPush(root, branch, upstream);
            case FORCE_WITH_LEASE -> forcePush(root, branch, upstream);
            case CANCEL -> ui().setStatus(tr("status.git.pushRejected"));
        }
    }

    /**
     * The pull a rejected push needs (a rebase when that is the configured pull mode, else a merge) — a fast-forward-only pull cannot succeed here, the branch having
     * commits of its own — then the push again. A pull that stops on conflicts ends there: the push waits
     * for the user to resolve them.
     */
    private void pullThenPush(Path root, String branch, String upstream) {
        // The configured pull mode, except fast-forward only, which cannot succeed on a diverged branch.
        String[] pull = (git().pullMode() == com.editora.git.GitPullMode.REBASE
                        ? com.editora.git.GitPullMode.REBASE
                        : com.editora.git.GitPullMode.MERGE)
                .args();
        String label = tr("gitlabel.pull");
        ui().setStatus(tr("status.gitRunning", label));
        git().aroundWorkingTreeMutation(
                        root,
                        done -> service().runNetworkWorktreeMutation(root, tracked(pull, done), pull),
                        result -> {
                            switch (GitOutcome.of(result)) {
                                // No second offer: a push rejected again right after the pull is reported.
                                case OK -> push(root, branch, upstream, GitService.pushArgs(branch, upstream), false);
                                case CONFLICT -> conflictStop(label, result);
                                default -> fail(tr("status.git.syncFailed", label), result);
                            }
                        },
                        null);
    }

    /**
     * {@code git.pushForce}: replaces the remote branch with the local one — refused on a detached HEAD,
     * confirmed with the branch and the remote named, and always with a lease.
     */
    void pushForce() {
        Path root = rootOrReport();
        if (root == null) {
            return;
        }
        String branch = git().branchName();
        String upstream = nz(git().upstream());
        if (GitService.isDetached(branch)) {
            ui().setStatus(tr("status.git.detachedNoBranch"));
            return;
        }
        service().pushRemoteOf(root, branch, remote -> {
            String target = upstream.isBlank() ? remote + "/" + branch : upstream;
            if (git().confirmDestructive(
                            tr("dialog.pushForce.title"),
                            tr("dialog.pushForce.confirm", branch, target),
                            tr("dialog.pushForce"))) {
                forcePush(root, branch, upstream);
            }
        });
    }

    /** The forced push itself ({@link GitService#forcePushArgs}); the caller has confirmed it. */
    void forcePush(Path root, String branch, String upstream) {
        String[] args = GitService.forcePushArgs(branch, upstream);
        if (root == null || args.length == 0) {
            ui().setStatus(tr("status.git.detachedNoBranch"));
            return;
        }
        String label = tr("gitlabel.pushForce");
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.gitDone", label));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
        });
    }

    /** {@code git.pushTo}: picks a remote, asks the branch's name there, and pushes the current branch to it. */
    void pushTo() {
        Path root = rootOrReport();
        if (root == null) {
            return;
        }
        String branch = git().branchName();
        boolean untracked = nz(git().upstream()).isBlank();
        if (GitService.isDetached(branch)) {
            ui().setStatus(tr("status.git.detachedNoBranch"));
            return;
        }
        withRemote(
                root,
                tr("remotes.pick.push"),
                remote -> ui().promptText(
                                tr("dialog.pushTo.title", remote),
                                tr("dialog.pushTo.content"),
                                branch,
                                input -> pushTo(root, branch, remote, input.strip(), untracked)));
    }

    /**
     * Pushes {@code branch} to {@code remoteBranch} on {@code remote} (the same name when left blank).
     * {@code setUpstream} — the branch tracked nothing yet — makes that its upstream.
     */
    void pushTo(Path root, String branch, String remote, String remoteBranch, boolean setUpstream) {
        String target = remoteBranch.isEmpty() ? branch : remoteBranch;
        String[] args = GitService.pushToArgs(remote, branch, target, setUpstream);
        if (root == null || args.length == 0) {
            ui().setError(tr("status.git.invalidBranchName", target));
            return;
        }
        String label = tr("gitlabel.push");
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.pushedTo", branch, remote + "/" + target));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
        });
    }

    /** {@code git.pushTags}: pushes every local tag the (picked) remote lacks. */
    void pushTags() {
        Path root = rootOrReport();
        if (root != null) {
            withRemote(root, tr("remotes.pick.push"), remote -> pushTags(root, remote));
        }
    }

    void pushTags(Path root, String remote) {
        String[] args = GitService.pushTagsArgs(remote);
        if (root == null || args.length == 0) {
            ui().setError(tr("status.git.unsafeRef", nz(remote)));
            return;
        }
        String label = tr("gitlabel.pushTags");
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.pushedTags", remote));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
        });
    }

    // --- remotes -----------------------------------------------------------------------------------

    /** A remote's URL fit for the screen; the push URL too when it differs. Never the stored credentials. */
    static String remoteDetail(GitRemotes.Remote remote) {
        String fetch = GitFormat.displayRemoteUrl(remote.fetchUrl());
        String push = GitFormat.displayRemoteUrl(remote.pushUrl());
        return push.isEmpty() || push.equals(fetch) ? fetch : tr("remotes.fetchPush", fetch, push);
    }

    /** {@code git.fetchRemote}: fetches (and prunes) one remote — the only one, or a picked one. */
    void fetchRemote() {
        Path root = rootOrReport();
        if (root != null) {
            withRemote(root, tr("remotes.pick.fetch"), remote -> fetchRemote(root, remote, () -> {}));
        }
    }

    /** {@code git fetch --prune <remote>}; {@code after} runs once it has finished, whatever the outcome. */
    void fetchRemote(Path root, String remote, Runnable after) {
        if (root == null || unsafe(remote)) {
            return;
        }
        String label = tr("gitlabel.fetchRemote", remote);
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, GitService.guarded(List.of("fetch", "--prune"), remote), result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.gitDone", label));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
            after.run();
        });
    }

    /** The manager that is up, for the tests that drive it. */
    GitManagerOverlay<GitRemotes.Remote> remotesManager;

    GitManagerOverlay<GitWorktrees.Worktree> worktreesManager;

    /** {@code git.remotes}: the remotes manager — list with URL, add, rename, set URL, fetch, prune, remove. */
    void manageRemotes() {
        Path root = rootOrReport();
        if (root != null) {
            showRemotes(root);
        }
    }

    private void showRemotes(Path root) {
        Runnable reopen = () -> showRemotes(root);
        service().remotes(root, remotes -> {
            remotesManager = new GitManagerOverlay<>(
                    tr("remotes.title"),
                    tr("remotes.empty"),
                    GitRemotes.Remote::name,
                    GitBranchCoordinator::remoteDetail,
                    List.of(
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.add"), false, false, none -> promptAddRemote(root, reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.rename"),
                                    true,
                                    false,
                                    remote -> promptRenameRemote(root, remote.name(), reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.setUrl"), true, false, remote -> promptRemoteUrl(root, remote, reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.fetch"),
                                    true,
                                    false,
                                    remote -> fetchRemote(root, remote.name(), reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.prune"),
                                    true,
                                    false,
                                    remote -> pruneRemote(root, remote.name(), reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("remotes.remove"),
                                    true,
                                    true,
                                    remote -> removeRemote(root, remote.name(), reopen))));
            remotesManager.show(ui().overlayHost(), remotes);
        });
    }

    /** Runs a {@code git remote …} configuration command and reports it; {@code after} runs on success. */
    private void remoteCommand(Path root, String[] args, String success, Runnable after) {
        runLocal(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(success);
                after.run();
            } else {
                fail(tr("status.git.remoteFailed"), result);
            }
        });
    }

    private void promptAddRemote(Path root, Runnable after) {
        TextField name = new TextField("origin");
        TextField url = new TextField();
        url.setPrefColumnCount(40);
        com.editora.command.TextInputKeymap.installShared(name);
        com.editora.command.TextInputKeymap.installShared(url);
        VBox body = new VBox(6, new Label(tr("remotes.form.name")), name, new Label(tr("remotes.form.url")), url);
        OverlayInput.show(
                ui().overlayHost(),
                tr("remotes.add"),
                body,
                name,
                tr("dialog.ok"),
                null,
                () -> addRemote(root, name.getText().strip(), url.getText().strip(), after),
                null,
                false);
    }

    /** {@code git remote add <name> <url>}. */
    void addRemote(Path root, String name, String url, Runnable after) {
        if (root == null || invalidRemote(name) || invalidUrl(url)) {
            return;
        }
        remoteCommand(
                root,
                GitService.guarded(List.of("remote", "add"), name, url),
                tr("status.git.remoteAdded", name),
                after);
    }

    private void promptRenameRemote(Path root, String old, Runnable after) {
        ui().promptText(
                        tr("remotes.rename"),
                        tr("remotes.form.newName", old),
                        old,
                        input -> renameRemote(root, old, input.strip(), after));
    }

    /** {@code git remote rename <old> <name>}: the remote-tracking branches and upstreams follow. */
    void renameRemote(Path root, String old, String name, Runnable after) {
        if (root == null || name.equals(old) || invalidRemote(name) || unsafe(old)) {
            return;
        }
        remoteCommand(
                root,
                GitService.guarded(List.of("remote", "rename"), old, name),
                tr("status.git.remoteRenamed", old, name),
                after);
    }

    private void promptRemoteUrl(Path root, GitRemotes.Remote remote, Runnable after) {
        // The field starts from the displayable URL: the stored one may carry a token. Accepting it
        // unchanged therefore changes nothing — it must not strip the credentials from the configuration.
        String shown = GitFormat.displayRemoteUrl(remote.fetchUrl());
        ui().promptText(tr("remotes.setUrl"), tr("remotes.form.urlOf", remote.name()), shown, input -> {
            String url = input.strip();
            if (url.equals(shown)) {
                ui().setStatus(tr("status.git.remoteUnchanged", remote.name()));
                after.run();
            } else {
                setRemoteUrl(root, remote.name(), url, after);
            }
        });
    }

    /** {@code git remote set-url <name> <url>}. */
    void setRemoteUrl(Path root, String name, String url, Runnable after) {
        if (root == null || unsafe(name) || invalidUrl(url)) {
            return;
        }
        remoteCommand(
                root,
                GitService.guarded(List.of("remote", "set-url"), name, url),
                tr("status.git.remoteUrlSet", name),
                after);
    }

    /**
     * {@code git remote remove <name>}, confirmed: its remote-tracking branches go with it, and every local
     * branch that tracked one of them is left without an upstream.
     */
    void removeRemote(Path root, String name, Runnable after) {
        if (root == null || unsafe(name)) {
            return;
        }
        if (!git().confirmDestructive(
                        tr("remotes.remove"), tr("dialog.remoteRemove.confirm", name), tr("dialog.remoteRemove"))) {
            after.run();
            return;
        }
        remoteCommand(
                root,
                GitService.guarded(List.of("remote", "remove"), name),
                tr("status.git.remoteRemoved", name),
                after);
    }

    /** {@code git remote prune <name>}: drops the remote-tracking branches deleted on the remote. */
    void pruneRemote(Path root, String name, Runnable after) {
        if (root == null || unsafe(name)) {
            return;
        }
        String label = tr("gitlabel.pruneRemote", name);
        ui().setStatus(tr("status.gitRunning", label));
        runRemote(root, GitService.guarded(List.of("remote", "prune"), name), result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.gitDone", label));
            } else {
                fail(tr("status.git.syncFailed", label), result);
            }
            after.run();
        });
    }

    private boolean invalidRemote(String name) {
        if (GitRefNames.isValidRemote(name)) {
            return false;
        }
        ui().setError(tr("status.git.invalidRemoteName", nz(name)));
        return true;
    }

    private boolean invalidUrl(String url) {
        if (GitRefNames.isUsableUrl(url)) {
            return false;
        }
        ui().setError(tr("status.git.invalidRemoteUrl"));
        return true;
    }

    // --- work trees --------------------------------------------------------------------------------

    /** What a work tree is on, for its row: the branch, or the detached commit, plus its flags. */
    static String worktreeDetail(GitWorktrees.Worktree tree) {
        List<String> parts = new ArrayList<>();
        if (tree.bare()) {
            parts.add(tr("worktrees.bare"));
        } else if (tree.branch().isEmpty()) {
            parts.add(tr("worktrees.detached", GitFormat.shortHash(tree.head())));
        } else {
            parts.add(tree.branch());
        }
        if (tree.main()) {
            parts.add(tr("worktrees.main"));
        }
        if (tree.locked()) {
            parts.add(tr("worktrees.locked"));
        }
        if (tree.prunable()) {
            parts.add(tr("worktrees.prunable"));
        }
        return String.join(" · ", parts);
    }

    /** {@code git.worktrees}: the work-trees manager — list, add, open in a new window, remove, prune. */
    void manageWorktrees() {
        Path root = rootOrReport();
        if (root != null) {
            showWorktrees(root);
        }
    }

    private void showWorktrees(Path root) {
        Runnable reopen = () -> showWorktrees(root);
        service().worktrees(root, trees -> {
            worktreesManager = new GitManagerOverlay<>(
                    tr("worktrees.title"),
                    tr("worktrees.empty"),
                    GitWorktrees.Worktree::path,
                    GitBranchCoordinator::worktreeDetail,
                    List.of(
                            new GitManagerOverlay.Action<>(
                                    tr("worktrees.add"), false, false, none -> promptAddWorktree(root, reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("worktrees.open"), true, false, tree -> openWorktree(tree.path())),
                            new GitManagerOverlay.Action<>(
                                    tr("worktrees.prune"), false, false, none -> pruneWorktrees(root, reopen)),
                            new GitManagerOverlay.Action<>(
                                    tr("worktrees.remove"), true, true, tree -> removeWorktree(root, tree, reopen))));
            worktreesManager.show(ui().overlayHost(), trees);
        });
    }

    /** Opens the work tree's folder as a project in its own window. */
    void openWorktree(String path) {
        try {
            windowOpener.accept(Path.of(path));
        } catch (InvalidPathException bad) {
            ui().setError(tr("status.git.worktreeBadPath", path));
        }
    }

    /**
     * Where a typed work-tree path lands: as typed when absolute, else beside the repository — never inside
     * it, where git would resolve a relative path and the new tree would show up as untracked files of the
     * old one. {@code null} for text that is no path. Pure.
     */
    static Path worktreeTarget(Path root, String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(typed.strip());
            Path base = root.toAbsolutePath().getParent();
            return (path.isAbsolute() || base == null ? path.toAbsolutePath() : base.resolve(path)).normalize();
        } catch (InvalidPathException bad) {
            return null;
        }
    }

    private void promptAddWorktree(Path root, Runnable after) {
        TextField path = new TextField(root.toAbsolutePath().normalize() + "-");
        path.setPrefColumnCount(44);
        TextField branch = new TextField();
        CheckBox create = new CheckBox(tr("worktrees.form.create"));
        create.setSelected(true);
        com.editora.command.TextInputKeymap.installShared(path);
        com.editora.command.TextInputKeymap.installShared(branch);
        VBox body = new VBox(
                6, new Label(tr("worktrees.form.path")), path, new Label(tr("worktrees.form.branch")), branch, create);
        OverlayInput.show(
                ui().overlayHost(),
                tr("worktrees.add"),
                body,
                path,
                tr("dialog.ok"),
                null,
                () -> addWorktree(root, path.getText(), branch.getText().strip(), create.isSelected(), after),
                null,
                false);
    }

    /**
     * {@code git worktree add [-b <branch>] <path> [<branch>]}: a new work tree at {@code typedPath} on a new
     * branch ({@code createBranch}) or on an existing branch or commit.
     */
    void addWorktree(Path root, String typedPath, String branch, boolean createBranch, Runnable after) {
        Path target = root == null ? null : worktreeTarget(root, typedPath);
        if (target == null) {
            ui().setError(tr("status.git.worktreeBadPath", nz(typedPath)));
            return;
        }
        String[] args;
        if (createBranch) {
            if (invalidBranchName(branch)) {
                return;
            }
            args = GitService.guarded(List.of("worktree", "add", "-b", branch), target.toString());
        } else {
            if (unsafe(branch)) {
                return;
            }
            args = GitService.guarded(List.of("worktree", "add"), target.toString(), branch);
        }
        runLocal(root, args, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.worktreeAdded", target));
                after.run();
            } else {
                fail(tr("status.git.worktreeFailed"), result);
            }
        });
    }

    /**
     * Removes a linked work tree after confirmation. Git refuses one with modified or untracked files; that
     * is answered with a second confirmation, naming that those files are deleted, before {@code --force}.
     * The main work tree and the one this window is in are never removed.
     */
    void removeWorktree(Path root, GitWorktrees.Worktree tree, Runnable after) {
        if (root == null || tree == null) {
            return;
        }
        if (tree.main()) {
            ui().setStatus(tr("status.git.worktreeMain"));
            return;
        }
        if (GitService.repoRelative(Path.of(tree.path()), root) != null) {
            ui().setStatus(tr("status.git.worktreeCurrent"));
            return;
        }
        if (git().confirmDestructive(
                        tr("worktrees.remove"),
                        tr("dialog.worktreeRemove.confirm", tree.path()),
                        tr("dialog.worktreeRemove"))) {
            removeWorktree(root, tree.path(), false, after);
        } else {
            after.run();
        }
    }

    private void removeWorktree(Path root, String path, boolean force, Runnable after) {
        String[] args = GitService.guarded(
                force ? List.of("worktree", "remove", "--force") : List.of("worktree", "remove"), path);
        // The boundary is the work tree being removed: its open buffers must not write into it afterwards.
        git().aroundWorkingTreeMutation(
                        Path.of(path),
                        done -> service().runWorktreeMutation(root, tracked(args, done), args),
                        result -> {
                            switch (GitOutcome.of(result)) {
                                case OK -> {
                                    ui().setStatus(tr("status.git.worktreeRemoved", path));
                                    after.run();
                                }
                                case DIRTY_WORKTREE ->
                                    Platform.runLater(() -> {
                                        if (!force
                                                && git().confirmDestructive(
                                                                tr("worktrees.remove"),
                                                                tr("dialog.worktreeRemove.dirty", path),
                                                                tr("dialog.worktreeRemove.force"))) {
                                            removeWorktree(root, path, true, after);
                                        } else {
                                            ui().setStatus(tr("status.git.worktreeKept", path));
                                            after.run();
                                        }
                                    });
                                default -> fail(tr("status.git.worktreeFailed"), result);
                            }
                        },
                        null);
    }

    /** {@code git worktree prune}: forgets work trees whose folders were deleted by hand. */
    void pruneWorktrees(Path root, Runnable after) {
        if (root == null) {
            return;
        }
        runLocal(root, new String[] {"worktree", "prune"}, result -> {
            if (result.ok()) {
                ui().setStatus(tr("status.git.worktreesPruned"));
                after.run();
            } else {
                fail(tr("status.git.worktreeFailed"), result);
            }
        });
    }

    // --- branch dropdown rows ----------------------------------------------------------------------

    /** The separator in {@link #rowActionIds}. */
    static final String ROW_SEPARATOR = "-";

    /**
     * Which secondary actions a branch row of the dropdown offers, in menu order. The current branch cannot
     * be merged into itself, compared with itself or deleted; a remote branch has no upstream of its own and
     * is deleted on its remote; "unset upstream" appears only where there is one. Pure.
     */
    static List<String> rowActionIds(BranchPopup.BranchRef branch) {
        List<String> ids = new ArrayList<>();
        ids.add("newFrom");
        if (!branch.current()) {
            ids.addAll(List.of("compare", ROW_SEPARATOR, "merge", "rebase"));
        }
        ids.add(ROW_SEPARATOR);
        if (branch.remote()) {
            ids.add("deleteRemote");
            return ids;
        }
        ids.addAll(List.of("rename", "setUpstream"));
        if (!nz(branch.upstream()).isEmpty()) {
            ids.add("unsetUpstream");
        }
        if (!branch.current()) {
            ids.addAll(List.of(ROW_SEPARATOR, "delete"));
        }
        return ids;
    }

    /**
     * The secondary menu of one branch row. {@code guard} wraps each action so it runs only while the
     * repository the dropdown listed is still the active one (see {@code GitWindowCoordinator.inRoot}).
     */
    List<BranchPopup.RowAction> rowActions(
            Path root,
            String current,
            GitService.Branches branches,
            BranchPopup.BranchRef branch,
            UnaryOperator<Runnable> guard) {
        String name = branch.name();
        List<BranchPopup.RowAction> actions = new ArrayList<>();
        for (String id : rowActionIds(branch)) {
            if (id.equals(ROW_SEPARATOR)) {
                actions.add(BranchPopup.RowAction.SEPARATOR);
                continue;
            }
            Runnable run =
                    switch (id) {
                        case "newFrom" -> () -> promptNewBranchFrom(root, name);
                        case "compare" -> () -> compare(root, current, name);
                        case "merge" -> () -> merge(root, current, name);
                        case "rebase" -> () -> rebase(root, current, name);
                        case "rename" -> () -> promptRename(root, name);
                        case "setUpstream" -> () -> promptUpstream(root, name);
                        case "unsetUpstream" -> () -> unsetUpstream(root, name);
                        case "delete" -> () -> deleteBranch(root, current, name);
                        default -> () -> deleteRemoteBranch(root, name, branches.remoteNames());
                    };
            boolean danger = id.equals("delete") || id.equals("deleteRemote");
            actions.add(new BranchPopup.RowAction(tr("branch.row." + id, name, nz(current)), danger, guard.apply(run)));
        }
        return actions;
    }
}
