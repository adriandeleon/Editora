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

    // --- force / push-to / tags / delete-remote ---------------------------------------------------

    /** A forced push is never a bare --force: the lease protects commits fetched by nobody yet. */
    @Test
    void aForcedPushAlwaysCarriesTheLease() {
        assertArrayEquals(
                new String[] {"push", "--force-with-lease"}, GitService.forcePushArgs("main", "origin/main", "origin"));
        assertArrayEquals(
                new String[] {"push", "--force-with-lease", "--set-upstream", "fork", "refs/heads/topic"},
                GitService.forcePushArgs("topic", "", "fork"));
        assertArrayEquals(
                new String[] {"push", "--force-with-lease", "--set-upstream", GitService.PUSH_REMOTE, "refs/heads/topic"
                },
                GitService.forcePushArgs("topic", null));
        for (String[] argv : List.of(
                GitService.forcePushArgs("main", "origin/main", "origin"),
                GitService.forcePushArgs("topic", "", "fork"))) {
            assertEquals(false, List.of(argv).contains("--force"), "a bare --force must never be emitted");
            assertEquals(false, List.of(argv).contains("-f"));
        }
    }

    @Test
    void aForcedPushIsRefusedOnADetachedHead() {
        assertEquals(0, GitService.forcePushArgs("(detached)", "", "origin").length);
        assertEquals(0, GitService.forcePushArgs("", "origin/main", "origin").length);
        assertEquals(0, GitService.forcePushArgs(null, null, "origin").length);
        assertEquals(0, GitService.forcePushArgs("topic", "", "-o").length, "an option-like remote is refused");
        assertEquals(true, GitService.isDetached("(detached)"));
        assertEquals(true, GitService.isDetached(" "));
        assertEquals(false, GitService.isDetached("main"));
    }

    @Test
    void pushToNamesBothSidesByTheirFullRefs() {
        assertArrayEquals(
                new String[] {"push", "fork", "refs/heads/topic:refs/heads/topic"},
                GitService.pushToArgs(false, "fork", "topic", "topic", false));
        assertArrayEquals(
                new String[] {
                    "push",
                    "--set-upstream",
                    GitSafety.END_OF_OPTIONS,
                    "fork",
                    "refs/heads/topic:refs/heads/review/topic"
                },
                GitService.pushToArgs(true, "fork", "topic", "review/topic", true));
    }

    @Test
    void pushToRefusesUnusableNames() {
        assertEquals(0, GitService.pushToArgs(false, "fork", "topic", "bad name", false).length);
        assertEquals(0, GitService.pushToArgs(false, "fork", "topic", "-x", false).length);
        assertEquals(0, GitService.pushToArgs(false, "fork", "topic", "a:b", false).length, "a second refspec colon");
        assertEquals(0, GitService.pushToArgs(false, "-fork", "topic", "topic", false).length);
        assertEquals(0, GitService.pushToArgs(false, "fork", "(detached)", "topic", false).length);
    }

    @Test
    void pushTagsNamesTheRemote() {
        assertArrayEquals(new String[] {"push", "--tags", "origin"}, GitService.pushTagsArgs(false, "origin"));
        assertArrayEquals(
                new String[] {"push", "--tags", GitSafety.END_OF_OPTIONS, "origin"},
                GitService.pushTagsArgs(true, "origin"));
        assertEquals(0, GitService.pushTagsArgs(false, "--mirror").length);
    }

    /** The full ref keeps a tag of the same name out of a remote-branch delete. */
    @Test
    void deletingARemoteBranchNamesItsFullRef() {
        assertArrayEquals(
                new String[] {"push", "--delete", "origin", "refs/heads/feature/x"},
                GitService.deleteRemoteBranchArgs(false, "origin", "feature/x"));
        assertArrayEquals(
                new String[] {"push", "--delete", GitSafety.END_OF_OPTIONS, "origin", "refs/heads/-odd"},
                GitService.deleteRemoteBranchArgs(true, "origin", "-odd"));
        assertEquals(0, GitService.deleteRemoteBranchArgs(false, "origin", "").length);
        assertEquals(0, GitService.deleteRemoteBranchArgs(false, "-origin", "x").length);
    }

    /** The push variants still get --progress and the remote placeholder resolved like a plain push. */
    @Test
    void theVariantsStayPushCommands() {
        assertArrayEquals(
                new String[] {"push", "--progress", "--force-with-lease"},
                GitService.withProgress(GitService.forcePushArgs("main", "origin/main")));
    }
}
