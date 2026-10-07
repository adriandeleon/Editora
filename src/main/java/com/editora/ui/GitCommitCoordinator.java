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
    private GitPanel panel;

    /** The repository whose template the panel holds; null forces the next refresh to read it again. */
    private Path templateRoot;
    /** Whether {@link #templateRoot} has a commit template (its comment lines are then stripped at commit). */
    private boolean templateConfigured;
    /** Raised per status applied: a line-count read for an older status is dropped. */
    private long countsGeneration;

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
            if (panel.isOnScreen()) {
                refreshLineCounts();
            }
        };
        panel.sceneProperty().addListener((o, was, now) -> shown.run());
        panel.visibleProperty().addListener((o, was, now) -> shown.run());
    }

    private static Path key(Path root) {
        return root.toAbsolutePath().normalize();
    }

    // --- per refresh ---------------------------------------------------------------------------------

    /** A refresh was applied: {@code root} is the active repository now (null outside one). */
    void repositoryApplied(Path root) {
        countsGeneration++;
        if (panel == null) {
            return;
        }
        if (root == null) {
            templateRoot = null;
            return;
        }
        if (!root.equals(templateRoot)) {
            templateRoot = root;
            templateConfigured = false;
            git.service().commitTemplate(root, template -> {
                if (root.equals(templateRoot) && root.equals(git.repoRoot())) {
                    templateConfigured = template.configured();
                    panel.setCommitTemplate(template);
                }
            });
        }
        refreshLineCounts();
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
            if (generation == countsGeneration && root.equals(git.repoRoot())) {
                panel.setLineCounts(counts);
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
        if (root == null || !git.saveUnsaved(root, List.of())) {
            onDone.accept(false);
            return;
        }
        String[] args = commitArgs(request, templateConfigured && root.equals(templateRoot));
        git.service()
                .runWorktreeMutation(
                        root,
                        git.running(args, result -> {
                            onDone.accept(result.ok());
                            if (result.ok()) {
                                remember(root, request.message());
                                host.setStatus(tr(request.amend() ? "status.git.amended" : "status.committed"));
                            } else {
                                git.gitError(tr("status.git.commitFailed"), result.message());
                            }
                            git.afterMutation();
                            if (result.ok() && request.push() && root.equals(git.repoRoot())) {
                                git.gitPush();
                            }
                        }),
                        args);
    }

    /** {@code git.commitAndPush}: the Commit window's Commit and Push, from anywhere. */
    void commitAndPush() {
        if (git.reportIfNoRepo()) {
            return;
        }
        ops.openCommitWindow();
        if (panel == null || !panel.commitAndPushNow()) {
            host.setStatus(tr("status.git.nothingToCommit"));
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

    /** Why the last commit cannot be undone, or null when it can. Pure. */
    static String undoRefusal(GitService.HeadCommit head) {
        if (head == null) {
            return "status.git.undoCommit.none";
        }
        if (head.parents().isEmpty()) {
            return "status.git.undoCommit.root";
        }
        return head.parents().size() > 1 ? "status.git.undoCommit.merge" : null;
    }

    /**
     * {@code git.undoLastCommit}: {@code git reset --soft HEAD~1} — the commit is taken off the branch, its
     * changes stay staged, and its message goes back into the commit box. Confirmed first, in stronger words
     * when the commit is already on the upstream (the branch then has to be force-pushed). Refused for a
     * merge commit (a soft reset cannot bring the merge back), for a commit without a parent, and while an
     * operation is in progress.
     */
    void undoLastCommit() {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
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
            String message = head.pushed()
                    ? tr("dialog.undoCommit.pushed", head.shortHash(), head.subject(), head.upstream())
                    : tr("dialog.undoCommit.message", head.shortHash(), head.subject());
            if (!git.confirmDestructive(tr("dialog.undoCommit.title"), message, tr("dialog.undoCommit.action"))) {
                return;
            }
            String[] args = {"reset", "--soft", "HEAD~1"};
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
                                } else {
                                    git.gitError(tr("status.git.opFailed"), result.message());
                                }
                                git.afterMutation();
                            }),
                            args);
        });
    }
}
