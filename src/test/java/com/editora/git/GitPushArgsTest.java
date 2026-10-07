package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pure {@code git push} argv decision: a branch with no upstream gets {@code --set-upstream <remote>
 * refs/heads/<branch>} on its first push, everything else is a plain {@code push} — and a blank branch never produces
 * a {@code --set-upstream origin} with an empty ref.
 */
class GitPushArgsTest {

    @Test
    void firstPushOfUpstreamlessBranchSetsUpstream() {
        assertArrayEquals(
                new String[] {"push", "--set-upstream", "origin", "refs/heads/feature"},
                GitService.pushArgs("feature", "", "origin"));
        // a null upstream is equivalent to a blank one
        assertArrayEquals(
                new String[] {"push", "--set-upstream", "fork", "refs/heads/main"},
                GitService.pushArgs("main", null, "fork"));
        // Without a remote the argv carries the placeholder the service resolves when the push starts.
        assertArrayEquals(
                new String[] {"push", "--set-upstream", GitService.PUSH_REMOTE, "refs/heads/feature"},
                GitService.pushArgs("feature", ""));
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

    @Test
    void aBranchNameIsNeverReadAsAnOption() {
        // E11: a branch called "-f" or "--delete" used to land in the argv bare. Its full ref cannot start
        // with a dash; a name git could not have created (a control character) gets the plain push.
        assertArrayEquals(
                new String[] {"push", "--set-upstream", "origin", "refs/heads/-f"},
                GitService.pushArgs("-f", "", "origin"));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("a\nb", "", "origin"));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("feature", "", "--receive-pack=evil"));
        assertArrayEquals(new String[] {"push"}, GitService.pushArgs("feature", "", ""));
    }

    private static String config(String... keyValues) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.append(keyValues[i]).append('\n').append(keyValues[i + 1]).append('\0');
        }
        return out.toString();
    }

    @Test
    void theFirstPushGoesWhereTheConfigurationSays() {
        // E2: "origin" was hard-coded.
        List<String> both = List.of("origin", "fork");
        assertEquals("origin", GitService.pushRemote("", "feature", both));
        assertEquals("upstream", GitService.pushRemote("", "feature", List.of("upstream")), "the sole remote");
        assertEquals("origin", GitService.pushRemote("", "feature", List.of("a", "b")), "ambiguous: git explains");
        assertEquals("origin", GitService.pushRemote(null, "feature", List.of()));

        String pushDefault = config("remote.origin.url", "x", "remote.pushdefault", "fork");
        assertEquals("fork", GitService.pushRemote(pushDefault, "feature", both));

        String perBranch = config(
                "remote.pushdefault", "fork", "branch.feature.pushremote", "mine", "branch.other.pushremote", "no");
        assertEquals("mine", GitService.pushRemote(perBranch, "feature", both));
        assertEquals("fork", GitService.pushRemote(perBranch, "unrelated", both));

        // branch.<name>.remote is git's third choice; "." (tracking a local branch) is not a push target.
        assertEquals("up", GitService.pushRemote(config("branch.feature.remote", "up"), "feature", both));
        assertEquals("origin", GitService.pushRemote(config("branch.feature.remote", "."), "feature", both));
        // The branch name is case-sensitive, the last value wins, and an option-like value is ignored.
        assertEquals("origin", GitService.pushRemote(config("branch.Feature.pushremote", "x"), "feature", both));
        assertEquals(
                "b", GitService.pushRemote(config("remote.pushdefault", "a", "remote.pushdefault", "b"), "f", both));
        assertEquals("origin", GitService.pushRemote(config("remote.pushdefault", "--exec=x"), "f", both));
        // A dotted branch name is still one subsection.
        assertEquals(
                "mine", GitService.pushRemote(config("branch.release/1.2.pushremote", "mine"), "release/1.2", both));
    }
}
