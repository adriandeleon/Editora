package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.git.ChangeType;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Characterization tests for the Git state machine — the {@code applyGitState}/{@code applyGitSupport} core
 * that drives the status bar, Commit/Log windows, and gutter change bars. Built as a safety net <em>before</em>
 * the larger Git-coordinator extraction: they drive {@code applyGitState} directly with a synthetic
 * {@link GitService.RepoState} (no real repo, no async) and pin the repo-root/branch/upstream state, so a
 * future move can't silently change it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitStateFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        fx.shared.getSettings().setGitSupport(true);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** The {@link GitCoordinator} now owns the state machine; reach it on the controller for the asserts. */
    private Object git() throws Exception {
        return FxTestSupport.field(fx.controller, "git");
    }

    private void applyGitState(GitService.RepoState state) throws Exception {
        Object git = git();
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(git, "applyState", new Class<?>[] {GitService.RepoState.class}, state));
    }

    private static GitService.RepoState repo(Path root, String branch, String upstream, int ahead, int behind) {
        GitStatus status = new GitStatus(true, branch, upstream, ahead, behind, List.of());
        return new GitService.RepoState(root, status, Map.of(3, ChangeType.MODIFIED), Map.of());
    }

    @Test
    void repoStateSetsRootBranchAndUpstream() throws Exception {
        Path root = Path.of("/work/repo");
        applyGitState(repo(root, "main", "origin/main", 2, 1));

        assertEquals(root, FxTestSupport.field(git(), "repoRoot"));
        assertEquals("main", FxTestSupport.field(git(), "branchName"));
        assertEquals("origin/main", FxTestSupport.field(git(), "upstream"));
    }

    @Test
    void noneStateClearsRepoInfo() throws Exception {
        // First populate, then clear with NONE — the "not in a repo" path must wipe the repo state.
        applyGitState(repo(Path.of("/work/repo"), "main", "origin/main", 0, 0));
        applyGitState(GitService.RepoState.NONE);

        assertNull(FxTestSupport.field(git(), "repoRoot"), "NONE clears the repo root");
        assertEquals("", FxTestSupport.field(git(), "branchName"));
        assertEquals("", FxTestSupport.field(git(), "upstream"));
    }

    @Test
    void applyGitSupportOffClearsRepoState() throws Exception {
        applyGitState(repo(Path.of("/work/repo"), "develop", "origin/develop", 0, 0));
        fx.shared.getSettings().setGitSupport(false);
        Object git = git();
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(git, "applySupport"));

        assertNull(FxTestSupport.field(git(), "repoRoot"), "disabling Git clears the repo root");
        assertEquals("", FxTestSupport.field(git(), "branchName"));

        fx.shared.getSettings().setGitSupport(true); // restore for any later test ordering
    }
}
