package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The pure {@code git push} argv decision: a branch with no upstream gets {@code --set-upstream origin
 * <branch>} on its first push, everything else is a plain {@code push} — and a blank branch never produces
 * a {@code --set-upstream origin} with an empty ref.
 */
class GitPushArgsTest {

    @Test
    void firstPushOfUpstreamlessBranchSetsUpstream() {
        assertArrayEquals(
                new String[] {"push", "--set-upstream", "origin", "feature"}, GitService.pushArgs("feature", ""));
        // a null upstream is equivalent to a blank one
        assertArrayEquals(new String[] {"push", "--set-upstream", "origin", "main"}, GitService.pushArgs("main", null));
    }

    @Test
    void trackedBranchUsesPlainPush() {
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("main", "origin/main"));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("feature", "origin/feature"));
    }

    @Test
    void blankOrNullBranchNeverSetsUpstream() {
        // Detached HEAD / unknown branch: never emit "--set-upstream origin <empty>".
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("", ""));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs(null, null));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("   ", ""));
        // upstream present but branch blank still falls back to a plain push
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("", "origin/main"));
    }

    @Test
    void detachedHeadDoesNotSetUpstreamToTheLiteralMarker() {
        // git status --branch reports a detached HEAD as the non-blank "(detached)"; it must not become
        // `push --set-upstream origin (detached)` (git rejects that refname). A plain push instead.
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("(detached)", ""));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("(no branch)", ""));
    }
}
