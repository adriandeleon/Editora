package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;

import com.editora.command.TextInputKeymap;
import com.editora.git.GitService;
import com.editora.git.StashOptions;
import com.editora.git.StashOutcome;
import com.editora.git.StashParser.StashEntry;
import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * The stash feature: the stash list ({@code git.stashes} — every stash with its files, and Show / Apply /
 * Pop / Drop / Branch / Copy per stash), the {@code git.stash} form with its options, the older pick-one
 * commands, and what the user is told when applying a stash stops on a conflict.
 *
 * <p>Owned by {@link GitCoordinator}. Every command that can rewrite files runs inside
 * {@link GitCoordinator#aroundWorkingTreeMutation}, so pending saves are superseded, the Git UI refreshes and
 * open buffers reload. A stash is always acted on in the repository it was listed from, and by a ref that is
 * checked against the listed commit ({@link GitService#runStashMutation}).
 */
final class GitStashCoordinator {

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private DiffCoordinator diff;
    private GitStashPopup popup;

    GitStashCoordinator(CoordinatorHost host, GitCoordinator git) {
        this.host = host;
        this.git = git;
    }

    void attach(DiffCoordinator diff) {
        this.diff = diff;
    }

    /** The list card, once it has been shown (for tests). */
    GitStashPopup popup() {
        return popup;
    }

    // --- the list ----------------------------------------------------------------------------------

    /** {@code git.stashes}: the stashes of the active repository, each with its files and actions. */
    void showList() {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
        git.service().stashList(root, stashes -> {
            if (stashes.isEmpty()) {
                host.setStatus(tr("stash.empty"));
                return;
            }
            if (popup == null) {
                popup = new GitStashPopup();
            }
            popup.show(host.overlayHost(), stashes, new GitStashPopup.Actions() {
                @Override
                public void files(StashEntry entry, Consumer<List<GitService.CommitFile>> onFiles) {
                    git.service().stashFiles(root, id(entry), onFiles);
                }

                @Override
                public void run(GitStashPopup.Action action, StashEntry entry) {
                    switch (action) {
                        case SHOW -> show(root, entry);
                        case APPLY -> apply(root, entry, false);
                        case POP -> apply(root, entry, true);
                        case DROP -> drop(root, entry);
                        case BRANCH -> promptBranch(root, entry);
                        case COPY -> copyName(entry);
                    }
                }
            });
        });
    }

    /** The name a stash is <em>read</em> by: its commit, which does not move when the list changes. */
    private static String id(StashEntry entry) {
        return entry.hash().isBlank() ? entry.ref() : entry.hash();
    }

    /**
     * Opens the stash's changes in the multi-file review tab: each tracked file against the commit the stash
     * was made on (the stash commit's first parent), and each untracked file of an
     * {@code --include-untracked} stash, read from the third parent that holds them, as an addition.
     */
    void show(Path root, StashEntry entry) {
        if (root == null || diff == null) {
            return;
        }
        String stash = id(entry);
        git.service().stashFiles(root, stash, files -> {
            if (files.isEmpty()) {
                host.setStatus(tr("stash.noFiles", entry.ref()));
                return;
            }
            List<DiffCoordinator.BlobReviewTarget> targets = new ArrayList<>();
            for (GitService.CommitFile file : files) {
                String base = file.origPath() == null || file.origPath().isBlank() ? file.path() : file.origPath();
                String left = stash + "^1:" + base;
                String right = stash + ":" + file.path();
                targets.add(
                        switch (file.status()) {
                            case '?' ->
                                new DiffCoordinator.BlobReviewTarget(
                                        file.path(), '?', null, stash + "^3:" + file.path());
                            case 'A' -> new DiffCoordinator.BlobReviewTarget(file.path(), 'A', null, right);
                            case 'D' -> new DiffCoordinator.BlobReviewTarget(file.path(), 'D', left, null);
                            default -> new DiffCoordinator.BlobReviewTarget(file.path(), file.status(), left, right);
                        });
            }
            diff.openBlobReview(
                    tr("stash.review.title", entry.ref(), targets.size()),
                    tr("stash.side.base"),
                    tr("stash.side.stash"),
                    root,
                    targets);
        });
    }

    /** Copies the stash's {@code stash@{N}} name. */
    void copyName(StashEntry entry) {
        ClipboardContent content = new ClipboardContent();
        content.putString(entry.ref());
        Clipboard.getSystemClipboard().setContent(content);
        host.setStatus(tr("status.git.copiedHash", entry.ref()));
    }

    // --- stash push --------------------------------------------------------------------------------

    /**
     * {@code git.stash}: a form for the message and the three options — include untracked files, staged
     * changes only, keep the index. Staged-only excludes the other two (git refuses the first combination,
     * and after a staged-only stash there is no index left to keep).
     */
    void promptStash() {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot(); // the repository the form was opened for, not whichever is active on accept
        TextField message = new TextField();
        message.setPromptText(tr("stash.prompt.label"));
        message.setPrefColumnCount(34);
        TextInputKeymap.installShared(message);
        CheckBox untracked = new CheckBox(tr("stash.option.untracked"));
        CheckBox staged = new CheckBox(tr("stash.option.staged"));
        CheckBox keepIndex = new CheckBox(tr("stash.option.keepIndex"));
        staged.selectedProperty().addListener((o, was, on) -> {
            untracked.setDisable(on);
            keepIndex.setDisable(on);
        });
        VBox body = new VBox(8, new Label(tr("stash.prompt.label")), message, untracked, staged, keepIndex);
        OverlayInput.show(
                host.overlayHost(),
                tr("stash.prompt.title"),
                body,
                message,
                tr("stash.prompt.button"),
                null,
                () -> stash(
                        root,
                        new StashOptions(
                                message.getText(),
                                untracked.isSelected(),
                                staged.isSelected(),
                                keepIndex.isSelected())),
                null,
                false);
    }

    /**
     * Stashes with {@code options} in {@code root}. Unsaved buffers of the repository are saved first: git
     * stashes the files on disk, so the edits still in an editor would otherwise stay behind in the buffer
     * while the file under them is reset.
     */
    void stash(Path root, StashOptions options) {
        if (root == null || !git.saveUnsaved(root, List.of())) {
            return;
        }
        String[] args = options.args();
        git.aroundWorkingTreeMutation(
                root,
                done -> git.service().stashPush(root, options, git.running(args, done)),
                result -> {
                    if (GitService.STASH_STAGED_UNSUPPORTED.equals(result.err())) {
                        host.setError(tr("stash.stagedUnsupported"));
                    } else if (!result.ok()) {
                        git.gitError(tr("status.git.opFailed"), result.message());
                    } else if (nothingStashed(result)) {
                        host.setStatus(tr("stash.nothing"));
                    } else {
                        host.setStatus(tr("stash.pushed"));
                    }
                },
                null);
    }

    /** Git exits 0 with "No local changes to save" (or "No staged changes") when there was nothing to stash. */
    static boolean nothingStashed(ProcessRunner.Result result) {
        String text = (result.out() + "\n" + result.err()).toLowerCase(java.util.Locale.ROOT);
        return text.contains("no local changes to save") || text.contains("no staged changes");
    }

    // --- apply / pop -------------------------------------------------------------------------------

    /** {@code git.stashPop}: pops the newest stash. */
    void popLatest() {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
        git.service().stashList(root, stashes -> {
            if (stashes.isEmpty()) {
                host.setStatus(tr("stash.empty"));
            } else {
                apply(root, stashes.get(0), true);
            }
        });
    }

    /** {@code git.unstash}: a picker over the stash list to apply one entry. */
    void pickToApply() {
        chooseStash(tr("stash.picker.applyTitle"), (root, entry) -> apply(root, entry, false));
    }

    /**
     * {@code git.stashDrop}: a picker over the stash list to drop one entry. Dropping deletes the stashed
     * changes for good — there is no undo short of digging the commit out of the object store — so it is
     * confirmed with the same danger-styled dialog a file discard uses.
     */
    void pickToDrop() {
        chooseStash(tr("stash.picker.dropTitle"), this::drop);
    }

    /**
     * Applies {@code entry} ({@code pop}: and drops it when that succeeds). The buffers of the files the
     * stash touches are saved first, as for a stage: git merges the stash into the files on disk.
     */
    void apply(Path root, StashEntry entry, boolean pop) {
        if (root == null) {
            return;
        }
        git.service().stashFiles(root, id(entry), files -> {
            List<String> paths = new ArrayList<>();
            for (GitService.CommitFile file : files) {
                paths.add(file.path());
            }
            // An empty pathspec list means "every buffer of the repository": with no file list, save none.
            if (!paths.isEmpty() && !git.saveUnsaved(root, paths)) {
                return;
            }
            String[] args = {"stash", pop ? "pop" : "apply", entry.ref()};
            git.aroundWorkingTreeMutation(
                    root,
                    done -> git.service().runStashMutation(root, entry, git.running(args, done), args),
                    result -> reportApplied(result, pop),
                    null);
        });
    }

    /**
     * Tells the user what an apply or pop did. A conflict is not a failure to explain in an error dialog:
     * the changes are in the working tree with markers, the stash is still in the list (git does not drop a
     * popped stash that conflicted), and the refresh that follows lists the conflicted files.
     */
    private void reportApplied(ProcessRunner.Result result, boolean pop) {
        if (GitService.STASH_MOVED.equals(result.err())) {
            host.setError(tr("stash.moved"));
            return;
        }
        switch (StashOutcome.classify(result.ok(), result.out(), result.err())) {
            case APPLIED -> host.setStatus(tr(pop ? "stash.popped" : "stash.applied"));
            case CONFLICT -> host.setError(tr(pop ? "stash.conflict.pop" : "stash.conflict.apply"));
            case WOULD_OVERWRITE -> git.gitError(tr("stash.wouldOverwrite"), result.message());
            case UNTRACKED_EXISTS -> git.gitError(tr("stash.untrackedExists"), result.message());
            case EMPTY -> host.setStatus(tr("stash.empty"));
            case FAILED -> git.gitError(tr("status.git.opFailed"), result.message());
        }
    }

    // --- drop / branch -----------------------------------------------------------------------------

    /** Confirms, then drops {@code entry} from the repository at {@code root} (the one it was listed from). */
    void drop(Path root, StashEntry entry) {
        if (root == null) {
            return;
        }
        String described = entry.subject().isBlank() ? entry.ref() : entry.ref() + " — " + entry.subject();
        if (!git.confirmDestructive(
                tr("stash.picker.dropTitle"), tr("dialog.stashDrop.confirm", described), tr("dialog.stashDrop"))) {
            return;
        }
        String[] args = {"stash", "drop", entry.ref()};
        git.service()
                .runStashMutation(
                        root,
                        entry,
                        git.running(args, result -> {
                            if (GitService.STASH_MOVED.equals(result.err())) {
                                host.setError(tr("stash.moved"));
                            } else if (result.ok()) {
                                host.setStatus(tr("stash.dropped"));
                            } else {
                                git.gitError(tr("status.git.opFailed"), result.message());
                            }
                            git.afterMutation();
                        }),
                        args);
    }

    /** Asks for a branch name, then {@link #branch}. */
    void promptBranch(Path root, StashEntry entry) {
        host.promptText(tr("stash.branch.title"), tr("dialog.newBranch.content"), "", input -> {
            String name = input.strip();
            if (!name.isEmpty() && !git.rejectUnsafeRevision(name)) {
                branch(root, entry, name);
            }
        });
    }

    /**
     * {@code git stash branch}: creates {@code name} at the commit the stash was made on, switches to it,
     * applies the stash there and drops it — the way out when a stash no longer applies to its branch.
     */
    void branch(Path root, StashEntry entry, String name) {
        if (root == null) {
            return;
        }
        String[] args = {"stash", "branch", name, entry.ref()};
        git.aroundWorkingTreeMutation(
                root,
                done -> git.service().runStashMutation(root, entry, git.running(args, done), args),
                result -> {
                    if (GitService.STASH_MOVED.equals(result.err())) {
                        host.setError(tr("stash.moved"));
                    } else if (result.ok()) {
                        host.setStatus(tr("stash.branched", name));
                    } else {
                        git.gitError(tr("status.git.createBranchFailed", name), result.message());
                    }
                },
                null);
    }

    // --- the plain pickers -------------------------------------------------------------------------

    /**
     * Lists the stashes of the repository that is active <em>now</em> and hands the pick back together with
     * that root: {@code stash@{0}} names a different stash in every repository, so the mutation must not
     * re-read {@code repoRoot} after the picker (or a confirmation) has been on screen.
     */
    private void chooseStash(String title, java.util.function.BiConsumer<Path, StashEntry> onPick) {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
        git.service().stashList(root, stashes -> {
            if (stashes.isEmpty()) {
                host.setStatus(tr("stash.empty"));
                return;
            }
            QuickOpen<StashEntry> picker = new QuickOpen<>(
                    title,
                    tr("stash.picker.prompt"),
                    () -> stashes,
                    e -> e.ref() + "  " + e.subject(),
                    e -> e.branch(),
                    e -> e.ref() + " " + e.subject() + " " + e.branch(),
                    entry -> onPick.accept(root, entry));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }
}
