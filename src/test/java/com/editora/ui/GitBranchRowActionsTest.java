package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.ui.BranchPopup.BranchRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the branch dropdown's rows and the work-tree form. */
class GitBranchRowActionsTest {

    private static List<String> ids(BranchRef branch) {
        List<String> ids = new ArrayList<>(GitBranchCoordinator.rowActionIds(branch));
        ids.removeIf(GitBranchCoordinator.ROW_SEPARATOR::equals);
        return ids;
    }

    @Test
    void aLocalBranchOffersEverythingIncludingDelete() {
        assertEquals(
                List.of("newFrom", "compare", "merge", "rebase", "rename", "setUpstream", "unsetUpstream", "delete"),
                ids(new BranchRef("topic", false, false, "origin/topic", false)));
        assertEquals(
                List.of("newFrom", "compare", "merge", "rebase", "rename", "setUpstream", "delete"),
                ids(new BranchRef("topic", false, false, "", false)),
                "nothing to unset without an upstream");
    }

    /** The checked-out branch cannot be merged into itself, compared with itself, or deleted. */
    @Test
    void theCurrentBranchIsNeverOfferedForDeletion() {
        List<String> ids = ids(new BranchRef("main", false, true, "origin/main", false));
        assertEquals(List.of("newFrom", "rename", "setUpstream", "unsetUpstream"), ids);
        assertFalse(ids.contains("delete"));
    }

    @Test
    void aRemoteBranchIsDeletedOnItsRemote() {
        assertEquals(
                List.of("newFrom", "compare", "merge", "rebase", "deleteRemote"),
                ids(new BranchRef("origin/topic", true, false, "", false)));
    }

    /** A menu never starts or ends with a separator, nor shows two in a row. */
    @Test
    void separatorsAreWellPlaced() {
        for (BranchRef branch : List.of(
                new BranchRef("topic", false, false, "origin/topic", true),
                new BranchRef("main", false, true, "", false),
                new BranchRef("origin/topic", true, false, "", false))) {
            List<String> ids = GitBranchCoordinator.rowActionIds(branch);
            String separator = GitBranchCoordinator.ROW_SEPARATOR;
            assertFalse(ids.get(0).equals(separator), ids.toString());
            assertFalse(ids.get(ids.size() - 1).equals(separator), ids.toString());
            for (int i = 1; i < ids.size(); i++) {
                assertFalse(ids.get(i).equals(separator) && ids.get(i - 1).equals(separator), ids.toString());
            }
        }
    }

    @Test
    void remoteBranchesAreGroupedOnlyWhenThereAreSeveralRemotes() {
        assertEquals(
                Map.of("", List.of("origin/a", "origin/Main", "origin/z")),
                BranchPopup.remoteGroups(List.of("origin/z", "origin/a", "origin/Main"), List.of("origin")));
        Map<String, List<String>> groups = BranchPopup.remoteGroups(
                List.of("origin/main", "fork/topic", "origin/a", "fork/main"), List.of("origin", "fork"));
        assertEquals(List.of("fork", "origin"), List.copyOf(groups.keySet()));
        assertEquals(List.of("fork/main", "fork/topic"), groups.get("fork"));
        assertEquals(List.of("origin/a", "origin/main"), groups.get("origin"));
        assertTrue(BranchPopup.remoteGroups(List.of(), List.of("origin")).isEmpty());
    }

    /** A relative work-tree path lands beside the repository, where it cannot become untracked files of it. */
    @Test
    void aRelativeWorktreePathIsResolvedBesideTheRepository() {
        Path root = Path.of("/home/me/src/app").toAbsolutePath();
        Path parent = root.getParent();
        assertEquals(parent.resolve("app-topic"), GitBranchCoordinator.worktreeTarget(root, "app-topic"));
        assertEquals(parent.resolve("app-topic"), GitBranchCoordinator.worktreeTarget(root, "  ./app-topic "));
        assertEquals(parent.getParent().resolve("x"), GitBranchCoordinator.worktreeTarget(root, "../x"));
        Path absolute = Path.of("/tmp/elsewhere").toAbsolutePath();
        assertEquals(absolute, GitBranchCoordinator.worktreeTarget(root, absolute.toString()));
        assertNull(GitBranchCoordinator.worktreeTarget(root, " "));
        assertNull(GitBranchCoordinator.worktreeTarget(root, "bad\u0000path"));
    }

    /** B4: a repository on another file system (a remote host's) gets its work tree there, never a local path. */
    @Test
    void aWorktreeOfARepositoryOnAnotherFileSystemStaysOnIt(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.net.URI zip = java.net.URI.create("jar:" + dir.resolve("host.zip").toUri());
        try (java.nio.file.FileSystem host = java.nio.file.FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path root = host.getPath("/srv/app");
            Path target = GitBranchCoordinator.worktreeTarget(root, "app-topic");
            assertEquals(host.getPath("/srv/app-topic"), target);
            assertTrue(target.getFileSystem() == host, "on the repository's own file system");
            assertEquals(host.getPath("/opt/x"), GitBranchCoordinator.worktreeTarget(root, "/opt/x"));
            assertEquals(host.getPath("/srv/app-y"), GitBranchCoordinator.onFileSystemOf(root, "/srv/app-y"));

            // The folders git lists for that repository are compared on that file system too.
            Map<String, String> elsewhere = GitBranchCoordinator.checkedOutElsewhere(
                    root,
                    List.of(
                            tree("/srv/app", "main", true),
                            tree("/srv/app-topic", "topic", false),
                            tree("/srv/app-detached", "", false)));
            assertEquals(Map.of("topic", "/srv/app-topic"), elsewhere);
        }
    }

    /** B12: the branches checked out in another work tree, without this one's own, a detached one or a bare one. */
    @Test
    void theBranchesCheckedOutElsewhereLeaveThisWorktreeOut() {
        Path root = Path.of("/home/me/src/app").toAbsolutePath();
        Map<String, String> elsewhere = GitBranchCoordinator.checkedOutElsewhere(
                root,
                List.of(
                        tree(root.toString(), "main", true),
                        tree(root.resolveSibling("app-a").toString(), "a", false),
                        tree(root.resolveSibling("app-b").toString(), "", false)));
        assertEquals(Map.of("a", root.resolveSibling("app-a").toString()), elsewhere);
    }

    /** B2: New Branch From never tracks where it started, switched to or not. */
    @Test
    void aNewBranchFromNeverTracksItsStart() {
        assertEquals(
                List.of("checkout", "--no-track", "-b", "work", "origin/main", "--"),
                List.of(GitBranchCoordinator.newBranchArgs("work", "origin/main", true)));
        List<String> branch = List.of(GitBranchCoordinator.newBranchArgs("work", "origin/main", false));
        assertEquals(List.of("branch", "--no-track"), branch.subList(0, 2));
        assertEquals(List.of("work", "origin/main"), branch.subList(branch.size() - 2, branch.size()));
    }

    /** M6: a merge is cherry-picked against a chosen parent, as it is reverted. */
    @Test
    void aMergeIsCherryPickedAgainstItsMainline() {
        assertEquals(List.of("cherry-pick", "abc"), List.of(GitWindowCoordinator.cherryPickArgs("abc", 0)));
        assertEquals(List.of("cherry-pick", "-m", "2", "abc"), List.of(GitWindowCoordinator.cherryPickArgs("abc", 2)));
    }

    /** L3: a page of the log that could not be read says git's reason. */
    @Test
    void aFailedPageSaysWhy() {
        assertEquals(
                com.editora.i18n.Messages.tr("status.git.log.loadMoreFailed", "fatal: bad object"),
                GitWindowCoordinator.loadMoreFailed("fatal: bad object\nmore"));
    }

    /** B3: the question names a hash as the log shows it, and a branch or tag as it is. */
    @Test
    void theLeaveDetachedQuestionNamesTheTarget() {
        assertEquals("main", GitLeaveDetachedHead.shown("main"));
        assertEquals("feature-branch-name", GitLeaveDetachedHead.shown("feature-branch-name"));
        assertEquals("0123456", GitLeaveDetachedHead.shown("0123456789abcdef0123456789abcdef01234567"));
    }

    private static com.editora.git.GitWorktrees.Worktree tree(String path, String branch, boolean main) {
        return new com.editora.git.GitWorktrees.Worktree(
                path, "0123456", branch, main, false, branch.isEmpty(), false, false);
    }
}
