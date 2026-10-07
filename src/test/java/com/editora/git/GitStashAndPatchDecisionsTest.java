package com.editora.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.editora.git.StashParser.StashEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the stash list, stash options, blame options and patch apply. */
class GitStashAndPatchDecisionsTest {

    // --- stash list --------------------------------------------------------------------------------

    @Test
    void theDetailedStashListCarriesCommitTimeBranchAndMessage() {
        String out = "aaaa1111\u001f1791346589\u001fOn feature/x: my saved work\n"
                + "bbbb2222\u001f1791340000\u001fWIP on main: 1a2b3c4 Fix: the gutter\n"
                + "cccc3333\u001fnot-a-number\u001fsomething git never wrote\n";
        List<StashEntry> list = StashParser.parseDetailed(out);

        assertEquals(3, list.size());
        assertEquals(
                new StashEntry(0, "stash@{0}", "feature/x", "my saved work", "aaaa1111", 1791346589L), list.get(0));
        assertEquals("main", list.get(1).branch());
        assertEquals("1a2b3c4 Fix: the gutter", list.get(1).subject(), "only the first colon ends the branch");
        assertEquals("stash@{1}", list.get(1).ref(), "the index is the line's position, not a printed selector");
        assertEquals("", list.get(2).branch());
        assertEquals("something git never wrote", list.get(2).subject());
        assertEquals(0L, list.get(2).epochSeconds());
        assertTrue(StashParser.parseDetailed("").isEmpty());
        assertTrue(StashParser.parseDetailed(null).isEmpty());
    }

    // --- stash options -----------------------------------------------------------------------------

    @Test
    void stashOptionsBecomeGitArguments() {
        assertArrayEquals(new String[] {"stash", "push"}, StashOptions.DEFAULT.args());
        assertArrayEquals(
                new String[] {"stash", "push", "-m", "wip"}, new StashOptions(" wip ", false, false, false).args());
        assertArrayEquals(
                new String[] {"stash", "push", "--include-untracked", "--keep-index", "-m", "both"},
                new StashOptions("both", true, false, true).args());
    }

    /** Git refuses --staged with --include-untracked, and a staged-only stash leaves no index to keep. */
    @Test
    void stagedOnlyIsPassedAlone() {
        assertArrayEquals(new String[] {"stash", "push", "--staged"}, new StashOptions(null, true, true, true).args());
    }

    @Test
    void stagedOnlyNeedsGit235() {
        assertTrue(StashOptions.stagedSupported("git version 2.35.0"));
        assertTrue(StashOptions.stagedSupported("git version 2.47.3"));
        assertTrue(StashOptions.stagedSupported("git version 3.1.0"));
        assertFalse(StashOptions.stagedSupported("git version 2.34.1"));
        assertFalse(StashOptions.stagedSupported("git version 2.9.5"));
        assertFalse(StashOptions.stagedSupported(""));
        assertFalse(StashOptions.stagedSupported(null));
    }

    // --- what a pop/apply did ----------------------------------------------------------------------

    @Test
    void aConflictingPopIsNotAPlainFailure() {
        String out = "Auto-merging f.txt\nCONFLICT (content): Merge conflict in f.txt\n"
                + "The stash entry is kept in case you need it again.\n";
        assertEquals(StashOutcome.CONFLICT, StashOutcome.classify(false, out, ""));
        assertTrue(StashOutcome.CONFLICT.stashKept());
        // A conflict together with an untracked file that is in the way is still, first, a conflict.
        assertEquals(
                StashOutcome.CONFLICT,
                StashOutcome.classify(false, out, "untracked.txt already exists, no checkout\n"));
    }

    @Test
    void theOtherRefusalsAreToldApart() {
        assertEquals(StashOutcome.APPLIED, StashOutcome.classify(true, "Dropped stash@{0}", ""));
        assertFalse(StashOutcome.APPLIED.stashKept());
        assertEquals(
                StashOutcome.WOULD_OVERWRITE,
                StashOutcome.classify(
                        false,
                        "",
                        "error: Your local changes to the following files would be overwritten by merge:\n\tf.txt\n"));
        assertEquals(
                StashOutcome.UNTRACKED_EXISTS,
                StashOutcome.classify(
                        false,
                        "",
                        "new.txt already exists, no checkout\nerror: could not restore untracked files from stash\n"));
        assertEquals(StashOutcome.EMPTY, StashOutcome.classify(false, "", "No stash entries found.\n"));
        assertEquals(StashOutcome.FAILED, StashOutcome.classify(false, "", "fatal: something else\n"));
        assertEquals(StashOutcome.FAILED, StashOutcome.classify(false, null, null));
    }

    // --- patch apply -------------------------------------------------------------------------------

    @Test
    void aThreeWayApplyWithConflictsIsNotARejection() {
        assertEquals(PatchOutcome.APPLIED, PatchOutcome.classify(true, "", ""));
        assertEquals(
                PatchOutcome.CONFLICTS,
                PatchOutcome.classify(false, "", "Applied patch to 'f.txt' with conflicts.\nU f.txt\n"));
        assertEquals(
                PatchOutcome.REJECTED,
                PatchOutcome.classify(false, "", "error: patch failed: f.txt:1\nerror: f.txt: patch does not apply\n"));
        assertEquals(PatchOutcome.REJECTED, PatchOutcome.classify(false, null, null));
    }

    // --- blame options -----------------------------------------------------------------------------

    @Test
    void blameOptionsBecomeGitArguments() {
        assertEquals(List.of(), BlameOptions.NONE.args());
        assertEquals(List.of("-w"), BlameOptions.NONE.withIgnoreWhitespace(true).args());
        assertEquals(
                List.of("-M", "-C"), BlameOptions.NONE.withDetectMoves(true).args());
        assertEquals(
                List.of("-w", "-M", "-C"),
                BlameOptions.NONE
                        .withIgnoreWhitespace(true)
                        .withDetectMoves(true)
                        .args());
        assertEquals(
                BlameOptions.NONE,
                new BlameOptions(true, true).withIgnoreWhitespace(false).withDetectMoves(false));
    }

    @Test
    void blameCarriesThePreviousCommitOfALine() {
        String c2 = "b".repeat(40);
        String c1 = "a".repeat(40);
        String porcelain = c2 + " 1 1 1\nauthor A\nauthor-time 10\nsummary second\nprevious " + c1
                + " old name.txt\nfilename new.txt\n\tline\n" + c1
                + " 2 2 1\nauthor A\nauthor-time 5\nsummary first\nfilename old name.txt\n\tline two\n";
        List<BlameParser.BlameLine> lines = BlameParser.parse(porcelain);

        assertEquals(c1, lines.get(0).previousHash());
        assertEquals("old name.txt", lines.get(0).previousPath());
        assertNull(lines.get(1).previousHash(), "the commit that added the file has nothing before it");
        assertNull(lines.get(1).previousPath());
    }

    // --- ignore-revs -------------------------------------------------------------------------------

    private static final Path ROOT = Path.of("/repo").toAbsolutePath();

    private static BlameIgnoreRevs.Plan plan(List<BlameIgnoreRevs.Configured> configured, String... existing) {
        Set<Path> files = new java.util.HashSet<>();
        for (String name : existing) {
            files.add(ROOT.resolve(name));
        }
        return BlameIgnoreRevs.plan(configured, ROOT, files::contains);
    }

    @Test
    void theConventionalFileIsUsedWhenNothingIsConfigured() {
        BlameIgnoreRevs.Plan plan = plan(List.of(), BlameIgnoreRevs.ROOT_FILE);
        assertEquals(List.of(ROOT.resolve(".git-blame-ignore-revs").toString()), plan.files());
        assertEquals(
                List.of(
                        "--ignore-revs-file",
                        ROOT.resolve(".git-blame-ignore-revs").toString()),
                plan.args());
        assertFalse(plan.withoutGlobalConfig());

        assertTrue(plan(List.of()).plain(), "no file, nothing configured: a plain blame");
    }

    /** Git applies a configured file itself; passing it again would only read it twice. */
    @Test
    void readableConfiguredFilesAreLeftToGit() {
        BlameIgnoreRevs.Plan plan = plan(
                List.of(new BlameIgnoreRevs.Configured("local", "ignore.txt")),
                "ignore.txt",
                BlameIgnoreRevs.ROOT_FILE);
        assertTrue(plan.plain());
        assertNull(plan.unreadable());
    }

    /**
     * The common breakage: a global {@code blame.ignoreRevsFile = .git-blame-ignore-revs} in a repository
     * without that file. Git dies on it, and no option removes a configured entry — so blame runs without
     * the global config's entry, with the readable global entries handed over explicitly.
     */
    @Test
    void aMissingGloballyConfiguredFileIsSteppedAround() {
        BlameIgnoreRevs.Plan plan = plan(
                List.of(
                        new BlameIgnoreRevs.Configured("global", ".git-blame-ignore-revs"),
                        new BlameIgnoreRevs.Configured("global", "other.txt"),
                        new BlameIgnoreRevs.Configured("local", "mine.txt")),
                "other.txt",
                "mine.txt");
        assertTrue(plan.withoutGlobalConfig());
        assertEquals(List.of(ROOT.resolve("other.txt").toString()), plan.files(), "the local one git still applies");
        assertNull(plan.unreadable());
    }

    /** A file the repository's own config names cannot be skipped: the user is told which one. */
    @Test
    void aMissingRepositoryConfiguredFileIsReported() {
        BlameIgnoreRevs.Plan plan = plan(List.of(new BlameIgnoreRevs.Configured("local", "gone.txt")));
        assertEquals("gone.txt", plan.unreadable());
        assertFalse(plan.withoutGlobalConfig());
        assertTrue(plan.files().isEmpty());
    }

    @Test
    void parsesTheScopedConfigListing() {
        assertEquals(
                List.of(
                        new BlameIgnoreRevs.Configured("global", "/home/u/.ignore"),
                        new BlameIgnoreRevs.Configured("local", "a b.txt")),
                BlameIgnoreRevs.parseScoped("global\u0000/home/u/.ignore\u0000local\u0000a b.txt\u0000"));
        assertTrue(BlameIgnoreRevs.parseScoped("").isEmpty());
        assertTrue(BlameIgnoreRevs.parseScoped("local\u0000\u0000").isEmpty(), "an empty value is not a file");
    }

    @Test
    void recognisesGitsIgnoreRevsFailures() {
        assertTrue(BlameIgnoreRevs.isIgnoreRevsFailure("fatal: could not open object name list: nope.txt"));
        assertTrue(BlameIgnoreRevs.isIgnoreRevsFailure("fatal: invalid object name: garbage"));
        assertFalse(BlameIgnoreRevs.isIgnoreRevsFailure("fatal: no such path 'x' in HEAD"));
        assertFalse(BlameIgnoreRevs.isIgnoreRevsFailure(null));
    }

    @Test
    void versionComparison() {
        assertTrue(GitSafety.versionAtLeast("git version 2.35.1.windows.2", 2, 35));
        assertFalse(GitSafety.versionAtLeast("git version 2.9.0", 2, 35));
        assertTrue(GitSafety.versionAtLeast("git version 10.0.0", 2, 35));
    }
}
