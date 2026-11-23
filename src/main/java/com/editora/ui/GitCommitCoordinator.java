package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.editora.git.CommitMessages;
import com.editora.git.GitNumstat;
import com.editora.git.GitService;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commit options of the Commit window, on top of {@link GitCoordinator}'s engine: amend, sign-off,
 * commit-and-push, undoing the last commit, the commit template, the recent-messages list and the per-file
 * line counts. The {@link GitPanel} is the view; it asks through {@link GitPanelActions} and is told through
 * the few setters used here. Nothing in this class starts a process — every read and command goes through
 * {@link GitService}.
 *
 * <p>Two things are remembered per repository <em>for the session</em>, across every window: whether its
 * commits are signed off, and its last {@link CommitMessages#HISTORY_MAX} commit messages. Neither is
 * written to disk.
 */
final class GitCommitCoordinator {

    /** Above this many changed paths the line counts are not read: two whole-tree diffs for a list nobody scans. */
    static final int LINE_COUNT_MAX_ENTRIES = 500;

    private static final Map<Path, Boolean> SIGN_OFF = new ConcurrentHashMap<>();
    private static final Map<Path, List<String>> HISTORY = new ConcurrentHashMap<>();

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final GitCoordinator.WindowOps ops;
    private @Nullable GitPanel panel;

    /** The repository whose template the panel holds; null forces the next refresh to read it again. */
    private @Nullable Path templateRoot;
    /** Whether {@link #templateRoot} has a commit template (its comment lines are then stripped at commit). */
    private boolean templateConfigured;
    /** Raised per status applied: a line-count read for an older status is dropped. */
    private long countsGeneration;
    /** The last repository applied (never null once one was): a switch away from it moves its draft aside. */
    private @Nullable Path appliedRoot;
    /** The message typed in the box for each repository the window left, put back when it returns. */
    private final Map<Path, String> drafts = new java.util.HashMap<>();

    GitCommitCoordinator(CoordinatorHost host, GitCoordinator git, GitCoordinator.WindowOps ops) {
        this.host = host;
        this.git = git;
        this.ops = ops;
    }

    /** The Commit window's panel, once it exists ({@link GitPanel.Actions#attached}). */
    void attach(GitPanel commitPanel) {
        panel = commitPanel;
        // Line counts are read only for a list that is on screen; when it comes on screen, read them.
        Runnable shown = () -> {
            GitPanel p = panel;
            if (p != null && p.isOnScreen()) {
                refreshLineCounts();
            }
        };
        commitPanel.sceneProperty().addListener((o, was, now) -> shown.run());
        commitPanel.visibleProperty().addListener((o, was, now) -> shown.run());
    }

    private static Path key(Path root) {
        return root.toAbsolutePath().normalize();
    }

    // --- per refresh ---------------------------------------------------------------------------------

    /** A refresh was applied: {@code root} is the active repository now (null outside one). */
    void repositoryApplied(@Nullable Path root) {
        countsGeneration++;
        if (panel == null) {
            return;
        }
        Path previous = appliedRoot;
        if (root != null) {
            appliedRoot = root;
            if (previous != null && !key(previous).equals(key(root))) {
                switchedRepository(panel, previous, root);
            }
        }
        if (root == null) {
            templateRoot = null;
            return;
        }
        if (!root.equals(templateRoot)) {
            templateRoot = root;
            templateConfigured = false;
            git.service().commitTemplate(root, template -> {
                GitPanel p = panel;
                if (p != null && root.equals(templateRoot) && root.equals(git.repoRoot())) {
                    templateConfigured = template.configured();
                    p.setCommitTemplate(template);
                }
            });
        }
        refreshLineCounts();
    }

    /**
     * The window moved from repository {@code from} to {@code to}. An Amend armed for {@code from} would rewrite
     * {@code to}'s last commit with {@code from}'s message: it is switched off, and the user told. The message being
     * written belongs to {@code from}: it is kept for when the window comes back, and {@code to}'s own is put in
     * the box — a message is not carried into another repository.
     */
    private void switchedRepository(GitPanel p, Path from, Path to) {
        if (p.isAmending()) {
            p.setAmending(false);
            host.setStatus(tr("status.git.amendOffRepository"));
        }
        String draft = p.draft();
        if (draft.isEmpty()) {
            drafts.remove(key(from));
        } else {
            drafts.put(key(from), draft);
        }
        p.replaceDraft(drafts.remove(key(to)));
    }

    /** The template is configuration: a manual refresh reads it again. */
    void invalidate() {
        templateRoot = null;
    }

    private void refreshLineCounts() {
        Path root = git.repoRoot();
        if (panel == null || root == null || !panel.isOnScreen()) {
            return;
        }
        int entries = git.status().files().size();
        if (entries == 0 || entries > LINE_COUNT_MAX_ENTRIES) {
            panel.setLineCounts(GitNumstat.Changes.NONE);
            return;
        }
        long generation = countsGeneration;
        git.service().lineCounts(root, counts -> {
            GitPanel p = panel;
            if (p != null && generation == countsGeneration && root.equals(git.repoRoot())) {
                p.setLineCounts(counts);
            }
        });
    }

    // --- sign-off and recent messages ----------------------------------------------------------------

    boolean signOff() {
        Path root = git.repoRoot();
        return root != null && SIGN_OFF.getOrDefault(key(root), false);
    }

    void setSignOff(boolean signOff) {
        Path root = git.repoRoot();
        if (root != null) {
            SIGN_OFF.put(key(root), signOff);
        }
    }

    /** The messages recently committed in the active repository, newest first. */
    List<String> messageHistory() {
        Path root = git.repoRoot();
        return root == null ? List.of() : HISTORY.getOrDefault(key(root), List.of());
    }

    static void remember(Path root, String message) {
        HISTORY.compute(
                key(root), (k, history) -> CommitMessages.remember(history, message, CommitMessages.HISTORY_MAX));
    }

    /** Forgets what the session remembered (tests). */
    static void clearSessionForTest() {
        SIGN_OFF.clear();
        HISTORY.clear();
    }

    /** {@code git.commitMessageHistory}: picks a recent message and puts it in the commit box. */
    void pickRecentMessage() {
        if (git.reportIfNoRepo()) {
            return;
        }
        List<String> history = messageHistory();
        if (history.isEmpty()) {
            host.setStatus(tr("status.git.noMessageHistory"));
            return;
        }
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.git.commitMessageHistory"),
                tr("gitpanel.historyPrompt"),
                () -> history,
                CommitMessages::subject,
                entry -> {
                    long lines = entry.lines().count();
                    return lines > 1 ? tr("gitpanel.historyLines", lines) : "";
                },
                entry -> {
                    ops.openCommitWindow();
                    if (panel != null) {
                        panel.setCommitMessage(entry);
                    }
                    ops.focusCommitMessage();
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    // --- commit --------------------------------------------------------------------------------------

    /**
     * The argv of a commit. The message travels as one {@code -m} argument (the engine's cancellable runner
     * has no stdin, and a commit waiting in a hook must stay cancellable). In a repository with a commit
     * template the message may still hold the template's comment lines, so git is asked to strip them
     * ({@code --cleanup=strip}); without one the message is recorded as typed, as it always was — a subject
     * that starts with {@code #} stays a subject. Pure.
     */
    static String[] commitArgs(GitPanel.CommitRequest request, boolean template) {
        List<String> args = new ArrayList<>(List.of("commit"));
        if (request.amend()) {
            args.add("--amend");
        }
        if (request.signOff()) {
            args.add("-s");
        }
        if (template) {
            args.add("--cleanup=strip");
        }
        args.add("-m");
        args.add(request.message());
        return args.toArray(String[]::new);
    }

    /**
     * Commits the index as {@code request} asks. {@code onDone} is told — on the FX thread, exactly once —
     * whether a commit was made. A push that was asked for follows only then, through the ordinary push
     * ({@link GitCoordinator#gitPush}): the remote of a first push is resolved and a rejected push is
     * answered with the same choice as ever — which is what an amended, already-pushed commit runs into.
     */
    void commit(GitPanel.CommitRequest request, Consumer<Boolean> onDone) {
        Path root = git.repoRoot();
        if (root == null) {
            onDone.accept(false);
            return;
        }
        // Only the files the commit records are saved first (a hook may read them): the staged ones. Saving every
        // unsaved buffer of the repository wrote files the user was still editing and had not chosen to commit.
        List<String> staged = stagedPaths(git.status());
        if (staged.isEmpty()) {
            commitSaved(root, request, onDone);
            return;
        }
        // Saved first, off the FX thread; a save that fails says why and commits nothing (PERF-01, Pauta 6.2.0).
        git.saveUnsaved(root, staged, () -> commitSaved(root, request, onDone), () -> onDone.accept(false));
    }

    /** The paths a commit of {@code status}'s index records: its staged files. Pure. */
    static List<String> stagedPaths(com.editora.git.@Nullable GitStatus status) {
        if (status == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (com.editora.git.GitStatus.FileEntry entry : status.files()) {
            if (entry.staged() && entry.path() != null) {
                paths.add(entry.path());
            }
        }
        return List.copyOf(paths);
    }

    private void commitSaved(Path root, GitPanel.CommitRequest request, Consumer<Boolean> onDone) {
        String[] args = commitArgs(request, templateConfigured && root.equals(templateRoot));
        // A commit runs the repository's hooks, and a pre-commit hook may rewrite files (a formatter): like every
        // other working-tree mutation, saves queued meanwhile are superseded and clean buffers reload afterwards.
        boolean[] committed = {false};
        git.aroundWorkingTreeMutation(
                root,
                done -> git.service().runWorktreeMutation(root, git.running(args, done), args),
                result -> {
                    committed[0] = result.ok();
                    onDone.accept(result.ok());
                    if (result.ok()) {
                        remember(root, request.message());
                        host.setStatus(tr(request.amend() ? "status.git.amended" : "status.committed"));
                    } else {
                        git.gitError(tr("status.git.commitFailed"), result.message());
                    }
                },
                () -> {
                    if (!request.push() || !committed[0]) {
                        return;
                    }
                    // Push only the repository that was committed; another one active by now is not pushed.
                    if (root.equals(git.repoRoot())) {
                        git.gitPush();
                    } else {
                        host.setStatus(tr("status.git.pushSkippedRepoChanged", root.getFileName()));
                    }
                });
    }

    /** {@code git.commitAndPush}: the Commit window's Commit and Push, from anywhere. */
    void commitAndPush() {
        if (git.reportIfNoRepo()) {
            return;
        }
        ops.openCommitWindow();
        if (panel == null || !panel.commitAndPushNow()) {
            // Why not: a message to type is a different thing to do than a change to stage.
            String why = panel == null ? "" : panel.commitRefusal();
            host.setStatus(why.isEmpty() ? tr("status.git.nothingToCommit") : why);
            ops.focusCommitMessage();
        }
    }

    /** {@code git.commitAmend}: opens the Commit window and switches Amend on (or off again). */
    void toggleAmend() {
        if (git.reportIfNoRepo() || panel == null) {
            return;
        }
        ops.openCommitWindow();
        panel.setAmending(!panel.isAmending());
        ops.focusCommitMessage();
    }

    /**
     * The commit an amend would replace, for the panel: {@code null}, with the reason in the status bar, when
     * there is nothing to amend — no repository, an operation that is using HEAD, a branch with no commit.
     */
    void amendTarget(Consumer<GitService.HeadCommit> onResult) {
        Path root = git.repoRoot();
        if (root == null) {
            onResult.accept(null);
            return;
        }
        if (git.operation().inProgress()) {
            host.setStatus(tr(
                    "status.git.amendDuringOperation",
                    GitCoordinator.operationName(git.operation().kind())));
            onResult.accept(null);
            return;
        }
        git.service().headCommit(root, head -> {
            if (!root.equals(git.repoRoot())) {
                onResult.accept(null);
                return;
            }
            if (head == null) {
                host.setStatus(tr("status.git.nothingToAmend"));
            }
            onResult.accept(head);
        });
    }

    // --- undo last commit ----------------------------------------------------------------------------

    /**
     * Whether {@code update-ref} refused because the ref is no longer at {@code expected}: git says "is at X but
     * expected Y" (or names the expected value as missing). Pure.
     */
    static boolean movedFrom(com.editora.process.ProcessRunner.Result result, String expected) {
        String text = (result.err() + "\n" + result.out());
        return !result.ok() && text.contains(expected) && (text.contains("but expected") || text.contains("is at"));
    }

    /** Why the last commit cannot be undone, or null when it can. Pure. */
    static @Nullable String undoRefusal(GitService.HeadCommit head) {
        if (head == null) {
            return "status.git.undoCommit.none";
        }
        if (head.parents().isEmpty()) {
            return "status.git.undoCommit.root";
        }
        return head.parents().size() > 1 ? "status.git.undoCommit.merge" : null;
    }

    /**
     * The words of the question before the last commit ({@code shortHash}, {@code subject}) is undone, in stronger
     * words when it is already {@code pushed} to {@code upstream}.
     */
    static Confirm.Question undoCommitQuestion(boolean pushed, String shortHash, String subject, String upstream) {
        return new Confirm.Question(
                tr("dialog.undoCommit.title"),
                null,
                pushed
                        ? tr("dialog.undoCommit.pushed", shortHash, subject, upstream)
                        : tr("dialog.undoCommit.message", shortHash, subject),
                tr("dialog.undoCommit.action"));
    }

    /**
     * {@code git.undoLastCommit}: what {@code git reset --soft HEAD~1} does — the commit is taken off the branch, its
     * changes stay staged, and its message goes back into the commit box. It is the commit the question named that
     * is undone, or nothing: should HEAD have moved meanwhile, the user is told and nothing changes. Confirmed first, in stronger words
     * when the commit is already on the upstream (the branch then has to be force-pushed). Refused for a
     * merge commit (a soft reset cannot bring the merge back), for a commit without a parent, and while an
     * operation is in progress.
     */
    void undoLastCommit() {
        Path root = git.repoRootOrReport();
        if (root == null) {
            return;
        }
        if (git.operation().inProgress()) {
            host.setStatus(tr(
                    "status.git.undoCommit.operation",
                    GitCoordinator.operationName(git.operation().kind())));
            return;
        }
        git.service().headCommit(root, head -> {
            String refusal = undoRefusal(head);
            if (refusal != null) {
                host.setStatus(tr(refusal));
                return;
            }
            if (!git.confirmDestructive(
                    undoCommitQuestion(head.pushed(), head.shortHash(), head.subject(), head.upstream()))) {
                return;
            }
            // The commit confirmed is the one taken off: HEAD is moved from it to its parent only if HEAD is still
            // it (update-ref checks the old value under the ref's lock) — a commit made meanwhile, in a terminal or
            // another window, is never the one undone. This is what "reset --soft" does to the branch.
            String parent = head.parents().get(0);
            String[] args = {
                "update-ref", "-m", "reset: moving to " + head.shortHash() + "~1", "HEAD", parent, head.hash()
            };
            git.service()
                    .runWorktreeMutation(
                            root,
                            git.running(args, result -> {
                                if (result.ok()) {
                                    remember(root, head.message());
                                    ops.openCommitWindow();
                                    boolean restored = panel != null
                                            && root.equals(git.repoRoot())
                                            && panel.restoreCommitMessage(head.message());
                                    host.setStatus(tr(
                                            restored ? "status.git.undoCommit.done" : "status.git.undoCommit.doneKept",
                                            head.shortHash()));
                                } else if (movedFrom(result, head.hash())) {
                                    host.setStatus(tr("status.git.undoCommit.moved", head.shortHash()));
                                } else {
                                    git.gitError(tr("status.git.opFailed"), result.message());
                                }
                                git.afterMutation(root);
                            }),
                            args);
        });
    }
}
