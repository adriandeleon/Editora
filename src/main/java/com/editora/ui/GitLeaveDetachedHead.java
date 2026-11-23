package com.editora.ui;

import java.nio.file.Path;
import java.util.Objects;

import com.editora.git.GitService;

import static com.editora.i18n.Messages.tr;

/**
 * The one question every command that moves HEAD to another branch, tag or commit asks first: from a detached
 * HEAD, the commits made there that no branch or tag reaches are left to the reflog. The Git Log's commit
 * checkout asked it; a branch switched from the dropdown, a remote branch checked out, Check Out Revision, a tag
 * checkout and New Branch From with Switch on did not. All of them go through {@link #thenMove} now, with the
 * decision and words of {@link GitHeadMoveWarning}.
 */
final class GitLeaveDetachedHead {

    private GitLeaveDetachedHead() {}

    /**
     * Runs {@code move} — a command that moves HEAD of {@code root} to {@code target} — once nothing would be
     * abandoned, or once the user has agreed to abandon it. On a branch of the active repository nothing can be:
     * the branch keeps its commits, and git is not asked. Elsewhere git counts the commits first (read-only); on
     * Cancel the status line says nothing moved.
     */
    static void thenMove(GitCoordinator git, Path root, String target, Runnable move) {
        if (onBranch(git, root)) {
            move.run();
            return;
        }
        git.service().commitsLeftBehind(root, target, false, left -> {
            if (GitHeadMoveWarning.checkoutNeedsConfirmation(left) && !git.confirmDestructive(question(target, left))) {
                git.host().setStatus(tr("status.git.headKept"));
                return;
            }
            move.run();
        });
    }

    /** Whether HEAD of {@code root} is known to be on a branch: the active repository, with a branch checked out. */
    static boolean onBranch(GitCoordinator git, Path root) {
        return Objects.equals(root, git.repoRoot()) && !GitService.isDetached(git.branchName());
    }

    /** The words of the question before HEAD moves to {@code target} and leaves commits ({@code left}) behind. */
    static Confirm.Question question(String target, GitService.LeftBehind left) {
        return new Confirm.Question(
                tr("dialog.gitLeaveDetached.title"),
                null,
                GitHeadMoveWarning.checkoutPrompt(shown(target), left),
                tr("dialog.gitCheckout.action"));
    }

    /** A full commit hash shortened as the log shows it; a branch, tag or typed revision as it is. Pure. */
    static String shown(String target) {
        return target != null && target.length() >= 40 && target.chars().allMatch(c -> Character.digit(c, 16) >= 0)
                ? com.editora.git.GitFormat.shortHash(target)
                : String.valueOf(target);
    }
}
