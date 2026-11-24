package com.editora.git;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void trackedBranchIsResolvedWhenThePushStarts() {
        assertArrayEquals(
                new String[] {"push", GitService.PUSH_UPSTREAM, "refs/heads/main"},
                GitService.pushArgs("main", "origin/main"));
    }

    private static String[] resolved(String[] args, String config) {
        return GitService.resolvePush(args, config, List.of("origin", "fork"), true);
    }

    /** G5: an upstream of the branch's own name is git's business; another name is never pushed onto. */
    @Test
    void aTrackedPushNeverLandsOnABranchOfAnotherName() {
        String same = config("branch.feat.remote", "origin", "branch.feat.merge", "refs/heads/feat");
        assertArrayEquals(new String[] {"push"}, resolved(GitService.pushArgs("feat", "origin/feat"), same));
        String other = config("branch.feat.remote", "origin", "branch.feat.merge", "refs/heads/main");
        assertArrayEquals(
                new String[] {"push", "--set-upstream", "origin", "refs/heads/feat:refs/heads/feat"},
                resolved(GitService.pushArgs("feat", "origin/main"), other));
        // The remote's own push refspecs (Gerrit's HEAD:refs/for/main) decide, as in a terminal.
        String gerrit = other + "\u0000" + "remote.origin.push\nHEAD:refs/for/main";
        assertArrayEquals(new String[] {"push"}, resolved(GitService.pushArgs("feat", "origin/main"), gerrit));
        // A triangular workflow pushes to its push remote: git's own push.default handles that.
        String triangular = other + "\u0000" + "branch.feat.pushremote\nfork";
        assertArrayEquals(new String[] {"push"}, resolved(GitService.pushArgs("feat", "origin/main"), triangular));
    }

    /** C2/C3: a forced push names its remote and refspec, and refuses tips it never integrated. */
    @Test
    void aForcedPushOfATrackedBranchIsExplicit() {
        String same = config("branch.main.remote", "origin", "branch.main.merge", "refs/heads/main");
        assertArrayEquals(
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", "origin", "refs/heads/main:refs/heads/main"
                },
                resolved(GitService.forcePushArgs("main", "origin/main"), same));
        assertArrayEquals(
                new String[] {"push", "--force-with-lease", "origin", "refs/heads/main:refs/heads/main"},
                GitService.resolvePush(GitService.forcePushArgs("main", "origin/main"), same, List.of("origin"), false),
                "git older than 2.30 has no --force-if-includes");
        String other = config("branch.feat.remote", "origin", "branch.feat.merge", "refs/heads/main");
        assertArrayEquals(
                new String[] {
                    "push",
                    "--force-with-lease",
                    "--force-if-includes",
                    "--set-upstream",
                    "origin",
                    "refs/heads/feat:refs/heads/feat"
                },
                resolved(GitService.forcePushArgs("feat", "origin/main"), other));
        // Untracked: the remote placeholder is resolved as for a first push.
        assertArrayEquals(
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", "--set-upstream", "fork", "refs/heads/topic"
                },
                resolved(GitService.forcePushArgs("topic", ""), config("remote.pushdefault", "fork")));
    }

    /** R3-E2: a remote with push refspecs of its own (Gerrit) keeps them for a forced push, untracked. */
    @Test
    void aForcedPushKeepsTheRemotesOwnPushRefspecs() {
        String gerrit = config(
                "branch.feat.remote", "origin",
                "branch.feat.merge", "refs/heads/main",
                "remote.origin.push", "HEAD:refs/for/main");
        assertArrayEquals(
                new String[] {"push", "--force-with-lease", "--force-if-includes"},
                resolved(GitService.forcePushArgs("feat", "origin/main"), gerrit));
        String sameName = config(
                "branch.main.remote", "origin",
                "branch.main.merge", "refs/heads/main",
                "remote.origin.push", "HEAD:refs/for/main");
        assertArrayEquals(
                new String[] {"push", "--force-with-lease"},
                GitService.resolvePush(
                        GitService.forcePushArgs("main", "origin/main"), sameName, List.of("origin"), false));
        // Untracked: the push remote is named, but neither the branch's ref nor --set-upstream.
        String untracked = config("remote.origin.push", "HEAD:refs/for/main");
        assertArrayEquals(
                new String[] {"push", "--force-with-lease", "--force-if-includes", "origin"},
                resolved(GitService.forcePushArgs("topic", ""), untracked));
        assertArrayEquals(new String[] {"push", "origin"}, resolved(GitService.pushArgs("topic", ""), untracked));
        // A wildcard refspec would force every matching branch: the forced push names its one branch instead.
        String mirror = config(
                "branch.feat.remote", "origin",
                "branch.feat.merge", "refs/heads/feat",
                "remote.origin.push", "+refs/heads/*:refs/heads/*");
        assertArrayEquals(
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", "origin", "refs/heads/feat:refs/heads/feat"
                },
                resolved(GitService.forcePushArgs("feat", "origin/feat"), mirror));
        assertArrayEquals(
                new String[] {"push", "origin", "refs/heads/feat:refs/heads/feat"},
                resolved(GitService.pushArgs("feat", "origin/feat"), mirror));
        // Another remote's refspecs do not count.
        String forkOnly = config("remote.fork.push", "HEAD:refs/for/main");
        assertArrayEquals(
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", "--set-upstream", "origin", "refs/heads/topic"
                },
                resolved(GitService.forcePushArgs("topic", ""), forkOnly));
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

    /** R4-E1/E2: only refspecs that push HEAD (Gerrit) decide where the branch goes; any other is overridden. */
    @Test
    void onlyHeadBasedPushRefspecsDecideWhereTheBranchGoes() {
        assertTrue(GitService.headBasedPushRefspecs(config("remote.origin.push", "HEAD:refs/for/main"), "origin"));
        assertTrue(GitService.headBasedPushRefspecs(config("remote.origin.push", "HEAD"), "origin"));
        assertTrue(GitService.headBasedPushRefspecs(config("remote.origin.push", "+HEAD:refs/heads/x"), "origin"));
        assertTrue(GitService.headBasedPushRefspecs(
                config("remote.origin.push", "HEAD:refs/for/main", "remote.origin.push", "HEAD:refs/for/dev"),
                "origin"));
        assertFalse(GitService.headBasedPushRefspecs("", "origin"), "no refspecs at all");
        assertFalse(GitService.headBasedPushRefspecs(config("remote.fork.push", "HEAD"), "origin"), "another remote");
        assertFalse(GitService.headBasedPushRefspecs(config("remote.Origin.push", "HEAD"), "origin"), "case-kept name");
        assertFalse(
                GitService.headBasedPushRefspecs(config("remote.origin.push", "refs/notes/*:refs/notes/*"), "origin"));
        assertFalse(GitService.headBasedPushRefspecs(
                config("remote.origin.push", "refs/heads/main:refs/heads/main"), "origin"));
        assertFalse(
                GitService.headBasedPushRefspecs(config("remote.origin.push", "+refs/heads/*:refs/heads/*"), "origin"));
        assertFalse(GitService.headBasedPushRefspecs(config("remote.origin.push", ":refs/heads/gone"), "origin"));
        // Multi-valued: one non-HEAD value anywhere, first or last, means "not Gerrit-style".
        assertFalse(GitService.headBasedPushRefspecs(
                config("remote.origin.push", "HEAD:refs/for/main", "remote.origin.push", "refs/notes/*:refs/notes/*"),
                "origin"));
        assertFalse(GitService.headBasedPushRefspecs(
                config("remote.origin.push", "refs/notes/*:refs/notes/*", "remote.origin.push", "HEAD:refs/for/main"),
                "origin"));

        String[] firstPush = {"push", "--set-upstream", "origin", "refs/heads/topic"};
        String[] firstForced = {
            "push", "--force-with-lease", "--force-if-includes", "--set-upstream", "origin", "refs/heads/topic"
        };
        for (String other :
                List.of("refs/notes/*:refs/notes/*", "refs/heads/main:refs/heads/main", "+refs/heads/*:refs/heads/*")) {
            String untracked = config("remote.origin.push", other);
            assertArrayEquals(firstPush, resolved(GitService.pushArgs("topic", ""), untracked), other);
            assertArrayEquals(firstForced, resolved(GitService.forcePushArgs("topic", ""), untracked), other);
            String mixed = config("remote.origin.push", "HEAD:refs/for/main", "remote.origin.push", other);
            assertArrayEquals(firstPush, resolved(GitService.pushArgs("topic", ""), mixed), "mixed with " + other);
            String tracked = config(
                    "branch.feat.remote", "origin",
                    "branch.feat.merge", "refs/heads/feat",
                    "remote.origin.push", other);
            assertArrayEquals(
                    new String[] {
                        "push", "--force-with-lease", "--force-if-includes", "origin", "refs/heads/feat:refs/heads/feat"
                    },
                    resolved(GitService.forcePushArgs("feat", "origin/feat"), tracked),
                    other);
            String otherName = config(
                    "branch.feat.remote", "origin",
                    "branch.feat.merge", "refs/heads/main",
                    "remote.origin.push", other);
            assertArrayEquals(
                    new String[] {"push", "--set-upstream", "origin", "refs/heads/feat:refs/heads/feat"},
                    resolved(GitService.pushArgs("feat", "origin/main"), otherName),
                    "an ordinary push onto another name is named too: " + other);
        }
        // Gerrit-style alone keeps deferring: the configuration says where HEAD goes.
        String gerrit = config("remote.origin.push", "HEAD:refs/for/main");
        assertArrayEquals(new String[] {"push", "origin"}, resolved(GitService.pushArgs("topic", ""), gerrit));
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
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", GitService.PUSH_UPSTREAM, "refs/heads/main"
                },
                GitService.forcePushArgs("main", "origin/main", "origin"));
        assertArrayEquals(
                new String[] {
                    "push", "--force-with-lease", "--force-if-includes", "--set-upstream", "fork", "refs/heads/topic"
                },
                GitService.forcePushArgs("topic", "", "fork"));
        assertArrayEquals(
                new String[] {
                    "push",
                    "--force-with-lease",
                    "--force-if-includes",
                    "--set-upstream",
                    GitService.PUSH_REMOTE,
                    "refs/heads/topic"
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
                new String[] {
                    "push",
                    "--progress",
                    "--force-with-lease",
                    "--force-if-includes",
                    GitService.PUSH_UPSTREAM,
                    "refs/heads/main"
                },
                GitService.withProgress(GitService.forcePushArgs("main", "origin/main")));
    }
}
