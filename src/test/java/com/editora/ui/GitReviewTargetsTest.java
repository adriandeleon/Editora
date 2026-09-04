package com.editora.ui;

import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitReviewTargetsTest {

    private static final GitStatus STATUS = new GitStatus(
            true,
            "main",
            "origin/main",
            0,
            0,
            List.of(
                    new FileEntry("staged.txt", 'M', '.', null),
                    new FileEntry("working.txt", '.', 'M', null),
                    new FileEntry("both.txt", 'M', 'M', null),
                    new FileEntry("new.txt", '?', '?', null),
                    new FileEntry("renamed.java", 'R', '.', "original.java"),
                    new FileEntry("moved.md", '.', 'R', "before.md")));

    @Test
    void stagedReviewUsesIndexStatusAndRenameSource() {
        assertEquals(
                List.of(
                        new DiffCoordinator.GitReviewTarget("staged.txt", "staged.txt", 'M'),
                        new DiffCoordinator.GitReviewTarget("both.txt", "both.txt", 'M'),
                        new DiffCoordinator.GitReviewTarget("renamed.java", "original.java", 'R')),
                DiffCoordinator.gitReviewTargets(STATUS, true));
    }

    @Test
    void workingReviewIncludesUntrackedAndUsesWorktreeRenameSource() {
        assertEquals(
                List.of(
                        new DiffCoordinator.GitReviewTarget("working.txt", "working.txt", 'M'),
                        new DiffCoordinator.GitReviewTarget("both.txt", "both.txt", 'M'),
                        new DiffCoordinator.GitReviewTarget("new.txt", null, '?'),
                        new DiffCoordinator.GitReviewTarget("moved.md", "before.md", 'R')),
                DiffCoordinator.gitReviewTargets(STATUS, false));
    }

    @Test
    void absentRepositoryHasNoReviewTargets() {
        assertEquals(List.of(), DiffCoordinator.gitReviewTargets(GitStatus.NOT_A_REPO, false));
    }
}
