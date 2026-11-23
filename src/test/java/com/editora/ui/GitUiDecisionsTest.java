package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure decisions behind the Git / GitHub windows. */
class GitUiDecisionsTest {

    @Test
    void aFileHistoryComparesOnlyItsOwnFileWithTheHistoryFile(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Path a = Files.writeString(root.resolve("a.txt"), "a\n");
        Files.writeString(root.resolve("b.txt"), "b\n");

        assertEquals(a, GitWindowCoordinator.historyWorkingFile(root, a, "a.txt"));
        assertEquals(
                root.resolve("b.txt"),
                GitWindowCoordinator.historyWorkingFile(root, a, "b.txt"),
                "another file of the same commit is compared with ITS working copy, never written into a.txt");
        assertEquals(root.resolve("b.txt"), GitWindowCoordinator.historyWorkingFile(root, null, "b.txt"));
        assertEquals(root.resolve("b.txt"), GitWindowCoordinator.historyWorkingFile(root, root, "b.txt"));
    }

    @Test
    void unstageRefusesAConflictedFileAndAFolderHoldingOne() {
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(new FileEntry("src/story.txt", 'U', 'U', null), new FileEntry("ok.txt", 'M', '.', null)));

        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of("src/story.txt")));
        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of("ok.txt", "src")));
        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of(".")));
        assertNull(GitCoordinator.firstUnmergedUnder(status, List.of("ok.txt")));
        assertNull(GitCoordinator.firstUnmergedUnder(null, List.of("ok.txt")));
    }

    @Test
    void aFailedListSaysWhatGhSaid() {
        assertEquals(
                "Could not list issues: HTTP 403: API rate limit exceeded",
                GitHubCoordinator.failureLine(
                        "Could not list issues", "\n  HTTP 403: API rate limit exceeded\nmore\n"));
        assertEquals("Could not list issues", GitHubCoordinator.failureLine("Could not list issues", " \n"));
        assertEquals("Could not list issues", GitHubCoordinator.failureLine("Could not list issues", null));
    }

    @Test
    void aLogCommandNeverRunsOnAHiddenSelection() {
        // The closed log still remembers its last selection — that is exactly the case that must not run.
        assertEquals(GitWindowCoordinator.LogCommand.OPEN_AND_ASK, GitWindowCoordinator.logCommand(false, "abc123"));
        assertEquals(GitWindowCoordinator.LogCommand.OPEN_AND_ASK, GitWindowCoordinator.logCommand(false, null));
        assertEquals(GitWindowCoordinator.LogCommand.ASK, GitWindowCoordinator.logCommand(true, null));
        assertEquals(GitWindowCoordinator.LogCommand.ASK, GitWindowCoordinator.logCommand(true, " "));
        assertEquals(GitWindowCoordinator.LogCommand.RUN, GitWindowCoordinator.logCommand(true, "abc123"));
    }

    @Test
    void aRemoteBranchSwitchesOnlyToTheLocalBranchThatTracksIt() {
        List<com.editora.git.GitService.BranchInfo> local = List.of(
                new com.editora.git.GitService.BranchInfo("main", "origin/main", 0, 0, false),
                new com.editora.git.GitService.BranchInfo("feature/x", "", 0, 0, false),
                new com.editora.git.GitService.BranchInfo("feature", "origin/feature", 0, 0, false));
        List<String> remotes = List.of("origin", "upstream", "team/eu");

        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.SWITCH, "main"),
                GitWindowCoordinator.remoteCheckout("origin/main", local, remotes));
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(
                        GitWindowCoordinator.RemoteCheckout.Kind.ASK, "origin-feature/x"),
                GitWindowCoordinator.remoteCheckout("origin/feature/x", local, remotes),
                "a local branch of that name that tracks nothing is not taken for it");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(
                        GitWindowCoordinator.RemoteCheckout.Kind.ASK, "upstream-feature"),
                GitWindowCoordinator.remoteCheckout("upstream/feature", local, remotes),
                "feature tracks origin/feature, not upstream/feature");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.TRACK, "new-work"),
                GitWindowCoordinator.remoteCheckout("origin/new-work", local, remotes),
                "no local branch yet: a tracking branch is created");
        assertEquals(
                new GitWindowCoordinator.RemoteCheckout(GitWindowCoordinator.RemoteCheckout.Kind.TRACK, "main-2"),
                GitWindowCoordinator.remoteCheckout("team/eu/main-2", local, remotes),
                "split at the remote's whole name, slash and all");
        assertEquals(
                GitWindowCoordinator.RemoteCheckout.Kind.TRACK,
                GitWindowCoordinator.remoteCheckout("noslash", local, remotes).kind());
    }

    @Test
    void fetchPrunes() {
        assertEquals(
                List.of("fetch", "--all", "--prune"),
                List.of(GitWindowCoordinator.FETCH_ARGS),
                "without --prune a deleted upstream is never noticed and no branch is ever marked gone");
    }

    @org.junit.jupiter.api.Test
    void theLogReloadsOnlyWhenItsRefsMoved() {
        String refs = "HEAD=refs/heads/main\nabc HEAD\nabc refs/heads/main\n";
        org.junit.jupiter.api.Assertions.assertFalse(GitWindowCoordinator.logNeedsReload(refs, refs));
        org.junit.jupiter.api.Assertions.assertTrue(
                GitWindowCoordinator.logNeedsReload(refs, refs.replace("abc refs", "def refs")));
        org.junit.jupiter.api.Assertions.assertTrue(GitWindowCoordinator.logNeedsReload("", refs));
        org.junit.jupiter.api.Assertions.assertTrue(GitWindowCoordinator.logNeedsReload(refs, ""));
    }
}
