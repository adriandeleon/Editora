package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitService#mayBeRepository}: a folder in no git working tree starts no {@code git} process (on Windows one
 * running in it would keep the folder from being deleted); one in a tree, a worktree's {@code .git} file included, does.
 */
class GitServiceRepositoryCheckTest {

    @Test
    void aFolderInNoWorkingTreeNeedsNoGit(@TempDir Path dir) throws Exception {
        Path plain = Files.createDirectories(dir.resolve("plain/sub"));
        // The temp folder itself may sit inside a checkout on a developer's machine; only judge it when it does not.
        boolean tempInRepo = GitService.mayBeRepository(dir);
        if (!tempInRepo) {
            assertFalse(GitService.mayBeRepository(plain));
        }
    }

    @Test
    void aFolderBelowAGitFolderOrAWorktreesGitFileIsInARepository(@TempDir Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        assertTrue(GitService.mayBeRepository(Files.createDirectories(repo.resolve("src/main"))));
        Path worktree = Files.createDirectories(dir.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../repo/.git/worktrees/w\n");
        assertTrue(GitService.mayBeRepository(worktree));
    }
}
