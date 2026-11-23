package com.editora.ui;

import java.nio.file.Path;

import com.editora.git.GitService.LeftBehind;

import static com.editora.i18n.Messages.tr;

/**
 * What the Git Log says before it moves HEAD off commits. A reset takes every commit after its target off
 * the branch, and a checkout made from a detached HEAD abandons the commits made there; neither said so —
 * a hard reset spoke only of uncommitted changes, soft and mixed asked nothing. The decisions and texts are
 * here, pure, so they can be tested without a dialog.
 */
final class GitHeadMoveWarning {

    private GitHeadMoveWarning() {}

    /**
     * Whether a reset must be confirmed. Hard always is (it also discards uncommitted changes). Soft and
     * mixed keep the files, so they ask only when commits would be left on no other branch or tag — reachable
     * through the reflog alone — and not for the everyday "undo my last, already pushed, commit". When Git
     * could not be asked ({@link LeftBehind#known()} false) nobody knows that nothing is stranded, so it asks.
     */
    static boolean resetNeedsConfirmation(String mode, LeftBehind left) {
        return "hard".equals(mode) || !left.known() || left.unreferenced() > 0;
    }

    /**
     * Whether checking out a commit must be confirmed: when it abandons commits no ref reaches, or when Git
     * could not say whether it does.
     */
    static boolean checkoutNeedsConfirmation(LeftBehind left) {
        return !left.known() || left.unreferenced() > 0;
    }

    static String resetPrompt(String mode, String shortHash, String branch, Path root, LeftBehind left) {
        String lead = "hard".equals(mode)
                ? tr("dialog.gitReset.hardConfirm", shortHash, branch, root)
                : tr("dialog.gitReset.confirm", shortHash, branch, root, mode);
        String commits = commitsLeaving(left, branch);
        return commits.isEmpty() ? lead : lead + "\n\n" + commits;
    }

    static String checkoutPrompt(String shortHash, LeftBehind left) {
        return left.known()
                ? tr("dialog.gitCheckout.strandConfirm", shortHash, left.unreferenced())
                : tr("dialog.gitCheckout.strandUnknown", shortHash);
    }

    /**
     * How many commits leave {@code branch}, how many of those its upstream does not have, and — when there
     * are any — how many no other branch or tag has. Empty when nothing leaves; when Git could not be asked,
     * a sentence that says so without claiming a count.
     */
    static String commitsLeaving(LeftBehind left, String branch) {
        if (!left.known()) {
            return tr("dialog.gitReset.commitsUnknown", branch);
        }
        if (left.leaving() <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(tr("dialog.gitReset.commitsLeaving", left.leaving(), branch));
        text.append(' ')
                .append(
                        left.hasUpstream()
                                ? tr("dialog.gitReset.commitsNotUpstream", left.notOnUpstream())
                                : tr("dialog.gitReset.commitsNoUpstream"));
        if (left.unreferenced() > 0) {
            text.append(' ').append(tr("dialog.gitReset.commitsUnreferenced", left.unreferenced()));
        }
        return text.toString();
    }
}
