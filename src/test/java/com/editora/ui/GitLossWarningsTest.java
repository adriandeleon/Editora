package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.git.GitService.LeftBehind;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure halves of the Git confirmations that name what is lost: {@link GitUntrackedDelete}, {@link GitHeadMoveWarning}. */
class GitLossWarningsTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    // --- untracked folders (V5) ---------------------------------------------------------------------------

    @Test
    void anUntrackedFolderRowIsNamedAFolderWithItsFileCount() {
        assertTrue(GitUntrackedDelete.isFolder("newdir/"));
        assertFalse(GitUntrackedDelete.isFolder("newdir"));
        assertFalse(GitUntrackedDelete.anyFolder(List.of("a.txt", "b.txt")));

        assertEquals(
                "Delete untracked folder \"newdir/\" and the 3 files in it? This cannot be undone.",
                GitUntrackedDelete.prompt(0, List.of("newdir/"), List.of("newdir/a", "newdir/b", "newdir/s/c"), true));
        assertEquals(
                "Delete untracked folder \"one/\" and the 1 file in it? This cannot be undone.",
                GitUntrackedDelete.prompt(0, List.of("one/"), List.of("one/a"), true));
        assertEquals(
                "Delete 4 untracked files, including everything in 2 untracked folders? This cannot be undone.",
                GitUntrackedDelete.prompt(
                        0, List.of("a/", "b/", "c.txt"), List.of("a/1", "a/2", "b/1", "c.txt"), true));
        assertEquals(
                "Discard changes to 2 tracked files and delete 3 untracked files, including everything in 1 untracked"
                        + " folder? This cannot be undone.",
                GitUntrackedDelete.prompt(2, List.of("a/", "c.txt"), List.of("a/1", "a/2", "c.txt"), true));
    }

    @Test
    void theHistoryCopyIsBoundedAndThePromptSaysWhenItIs() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < GitUntrackedDelete.MAX_HISTORY_CAPTURES + 5; i++) {
            many.add("gen/f" + i + ".txt");
        }
        Path root = Path.of("/repo");
        List<Path> captured = GitUntrackedDelete.captures(root, many);
        assertEquals(GitUntrackedDelete.MAX_HISTORY_CAPTURES, captured.size());
        assertEquals(root.resolve("gen/f0.txt"), captured.get(0));

        String limit = tr("dialog.discard.historyLimit", GitUntrackedDelete.MAX_HISTORY_CAPTURES);
        assertTrue(GitUntrackedDelete.prompt(0, List.of("gen/"), many, true).endsWith("\n\n" + limit));
        assertFalse(
                GitUntrackedDelete.prompt(0, List.of("gen/"), many, false).contains(limit),
                "nothing is kept with Local History off, so no promise about it is made");
        assertFalse(GitUntrackedDelete.prompt(0, List.of("gen/"), many.subList(0, 3), true)
                .contains(limit));
    }

    // --- reset / commit checkout (V9) -------------------------------------------------------------------

    @Test
    void hardAlwaysAsksSoftAndMixedOnlyWhenCommitsWouldBeStranded() {
        LeftBehind nothing = new LeftBehind(true, 0, false, 0, 0);
        LeftBehind pushedElsewhere = new LeftBehind(true, 3, true, 0, 0);
        LeftBehind stranded = new LeftBehind(true, 3, true, 2, 1);
        for (LeftBehind left : List.of(nothing, pushedElsewhere, stranded, LeftBehind.UNKNOWN)) {
            assertTrue(GitHeadMoveWarning.resetNeedsConfirmation("hard", left));
        }
        for (String mode : List.of("soft", "mixed")) {
            assertFalse(GitHeadMoveWarning.resetNeedsConfirmation(mode, nothing));
            assertFalse(GitHeadMoveWarning.resetNeedsConfirmation(mode, pushedElsewhere));
            assertTrue(GitHeadMoveWarning.resetNeedsConfirmation(mode, stranded));
            assertFalse(
                    GitHeadMoveWarning.resetNeedsConfirmation(mode, LeftBehind.UNKNOWN),
                    "when Git cannot be asked, soft and mixed behave as they always did");
        }
        assertTrue(GitHeadMoveWarning.checkoutNeedsConfirmation(stranded));
        assertFalse(GitHeadMoveWarning.checkoutNeedsConfirmation(pushedElsewhere));
        assertFalse(GitHeadMoveWarning.checkoutNeedsConfirmation(LeftBehind.UNKNOWN));
    }

    @Test
    void theConfirmationCountsCommitsLeavingTheBranchAndThoseNotOnItsUpstream() {
        assertEquals(
                "3 commits will no longer be on main. 2 of them are not on the upstream branch."
                        + " 1 commit is on no other branch or tag and will be reachable only through the reflog.",
                GitHeadMoveWarning.commitsLeaving(new LeftBehind(true, 3, true, 2, 1), "main"));
        assertEquals(
                "1 commit will no longer be on main. All of them are on the upstream branch.",
                GitHeadMoveWarning.commitsLeaving(new LeftBehind(true, 1, true, 0, 0), "main"));
        assertEquals(
                "2 commits will no longer be on topic. The branch has no upstream, so none of them has been pushed"
                        + " from it. 2 commits are on no other branch or tag and will be reachable only through the"
                        + " reflog.",
                GitHeadMoveWarning.commitsLeaving(new LeftBehind(true, 2, false, 2, 2), "topic"));
        assertEquals("", GitHeadMoveWarning.commitsLeaving(new LeftBehind(true, 0, false, 0, 0), "main"));
        assertEquals("", GitHeadMoveWarning.commitsLeaving(LeftBehind.UNKNOWN, "main"));

        Path root = Path.of("/repo");
        LeftBehind left = new LeftBehind(true, 3, true, 2, 1);
        assertEquals(
                tr("dialog.gitReset.hardConfirm", "abc1234", "main", root)
                        + "\n\n"
                        + GitHeadMoveWarning.commitsLeaving(left, "main"),
                GitHeadMoveWarning.resetPrompt("hard", "abc1234", "main", root, left));
        assertEquals(
                tr("dialog.gitReset.hardConfirm", "abc1234", "main", root),
                GitHeadMoveWarning.resetPrompt("hard", "abc1234", "main", root, LeftBehind.UNKNOWN),
                "unchanged when there is nothing to add");
        String soft = tr("dialog.gitReset.confirm", "abc1234", "main", root, "soft");
        assertTrue(
                soft.startsWith("Reset main in ")
                        && soft.endsWith(" to abc1234 (soft)? Files and uncommitted changes are kept."),
                soft);
        assertEquals(
                soft + "\n\n" + GitHeadMoveWarning.commitsLeaving(left, "main"),
                GitHeadMoveWarning.resetPrompt("soft", "abc1234", "main", root, left));
    }
}
