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
}
