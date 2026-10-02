package com.editora;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot-version helpers. Between releases the pom carries a {@code -SNAPSHOT} suffix, so these
 * decide both what the UI shows (the toolbar badge) and what must be stripped before a version reaches
 * a versioned docs URL.
 */
class AppInfoTest {

    @Test
    void detectsTheSnapshotSuffix() {
        assertTrue(AppInfo.isSnapshot("0.9.8-SNAPSHOT"));
        assertTrue(AppInfo.isSnapshot("1.0.0-SNAPSHOT"));
        // Maven writes it upper-case, but don't be brittle about a hand-edited pom.
        assertTrue(AppInfo.isSnapshot("0.9.8-snapshot"));
        assertTrue(AppInfo.isSnapshot("  0.9.8-SNAPSHOT  "));
    }

    @Test
    void aReleaseVersionIsNotASnapshot() {
        assertFalse(AppInfo.isSnapshot("0.9.7"));
        assertFalse(AppInfo.isSnapshot("1.0.0"));
        // A pre-release tag is still a real release, not a development build.
        assertFalse(AppInfo.isSnapshot("0.9.8-rc1"));
        // The unfiltered/IDE fallback.
        assertFalse(AppInfo.isSnapshot("0.0.0"));
        assertFalse(AppInfo.isSnapshot(""));
        assertFalse(AppInfo.isSnapshot(null));
    }

    @Test
    void releaseVersionStripsOnlyTheSuffix() {
        assertEquals("0.9.8", AppInfo.releaseVersion("0.9.8-SNAPSHOT"));
        assertEquals("0.9.8", AppInfo.releaseVersion("0.9.8-snapshot"));
        assertEquals("0.9.8", AppInfo.releaseVersion("  0.9.8-SNAPSHOT  "));
    }

    @Test
    void releaseVersionLeavesANonSnapshotAlone() {
        assertEquals("0.9.7", AppInfo.releaseVersion("0.9.7"));
        // An rc must survive intact — it names a real published release.
        assertEquals("0.9.8-rc1", AppInfo.releaseVersion("0.9.8-rc1"));
        assertEquals("", AppInfo.releaseVersion(null));
    }

    @Test
    void theLiveVersionAgreesWithItsOwnHelpers() {
        // Whatever this build was filtered with, the two views must stay consistent.
        assertEquals(AppInfo.isSnapshot(AppInfo.VERSION), AppInfo.isSnapshot());
        assertEquals(AppInfo.releaseVersion(AppInfo.VERSION), AppInfo.releaseVersion());
        assertFalse(AppInfo.releaseVersion().isEmpty());
    }

    // --- the dev-build git identity: never on the caller's thread, never in the wrong repository ---

    private static com.editora.process.ProcessRunner.Result result(int exit, String out) {
        return new com.editora.process.ProcessRunner.Result(exit, out, "");
    }

    /**
     * The lookup spawns {@code git} (and may first pay for the login-shell PATH probe), and it used to run
     * inline on the FX thread from the Welcome page — reading git's output to EOF <em>before</em> the
     * {@code waitFor} that carried its 2 s timeout, so a git that never exited froze the window for good.
     */
    @Test
    void aGitLookupNeverRunsOnTheThreadThatAsksForIt() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var ranOn = new java.util.concurrent.atomic.AtomicReference<Thread>();

        java.util.concurrent.CompletableFuture<String> lookup = AppInfo.lookup(() -> {
            ranOn.set(Thread.currentThread());
            try {
                release.await(); // a git that has not answered yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "abc1234";
        });

        assertFalse(lookup.isDone(), "the caller got its answer-in-waiting back without waiting");
        assertEquals("", lookup.getNow(""), "which reads as 'not known yet' to a non-blocking caller");
        release.countDown();
        assertEquals("abc1234", lookup.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(ranOn.get() != Thread.currentThread() && ranOn.get().isDaemon());
    }

    @Test
    void aFailingOrEmptyLookupCompletesBlankInsteadOfFailing() throws Exception {
        assertEquals(
                "",
                AppInfo.lookup(() -> {
                            throw new IllegalStateException("git blew up");
                        })
                        .get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("", AppInfo.lookup(() -> null).get(10, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void theCommitIsAHexHashFromASuccessfulGitOrNothing() {
        assertEquals("32334d0b", AppInfo.commitFrom(result(0, "32334d0b\n")));
        assertEquals("", AppInfo.commitFrom(result(128, "fatal: not a git repository")));
        assertEquals("", AppInfo.commitFrom(result(0, "fatal: not a git repository")), "not a hash");
        assertEquals("", AppInfo.commitFrom(result(-1, "")), "timed out or could not start");
        assertEquals("", AppInfo.commitFrom(null), "no checkout to ask about");
    }

    @Test
    void theBranchIsASingleRefNameOrNothing() {
        assertEquals("fix/review-process-env", AppInfo.branchFrom(result(0, "fix/review-process-env\n")));
        assertEquals("", AppInfo.branchFrom(result(0, "HEAD\n")), "a detached checkout has no branch");
        assertEquals("", AppInfo.branchFrom(result(0, "fatal: bad thing")), "an error line, not a ref");
        assertEquals("", AppInfo.branchFrom(result(128, "master")));
        assertEquals("", AppInfo.branchFrom(result(0, "  \n")));
        assertEquals("", AppInfo.branchFrom(null));
    }

    /**
     * {@code git} is asked about the checkout this build lives in. It used to run in the launch working
     * directory, so an editor started from inside some other repository reported that repository's commit.
     */
    @Test
    void gitIsAskedAboutTheBuildsOwnCheckoutNotTheLaunchDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path classes = java.nio.file.Files.createDirectories(dir.resolve("target/classes"));
        java.nio.file.Path jar = java.nio.file.Files.createFile(dir.resolve("target/editora.jar"));
        java.nio.file.Path runtime = java.nio.file.Files.createDirectories(dir.resolve("image/lib/runtime"));

        // A dev run: the exploded classes directory.
        assertEquals(classes, AppInfo.checkoutDir(classes.toUri().toURL(), runtime.toString()));
        // A jar: the folder that holds it.
        assertEquals(jar.getParent(), AppInfo.checkoutDir(jar.toUri().toURL(), runtime.toString()));
        // A jlinked image keeps its classes in jrt:, so fall back to the bundled runtime's directory.
        assertEquals(
                runtime,
                AppInfo.checkoutDir(java.net.URI.create("jrt:/com.editora").toURL(), runtime.toString()));
        assertEquals(runtime, AppInfo.checkoutDir(null, runtime.toString()));
        // Nothing usable: no directory, so git is never run.
        assertEquals(null, AppInfo.checkoutDir(null, null));
        assertEquals(null, AppInfo.checkoutDir(null, dir.resolve("missing").toString()));
    }

    @Test
    void theAccessorsAnswerAtOnceAndAgreeWithTheirLookups() throws Exception {
        // Whatever this checkout is, the non-blocking accessor returns immediately; once the lookup has
        // finished, it reports exactly the lookup's answer (cached — one lookup per process).
        assertEquals(AppInfo.gitCommitAsync(), AppInfo.gitCommitAsync());
        String commit = AppInfo.gitCommitAsync().get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(commit, AppInfo.gitCommit());
        assertTrue(commit.isEmpty() || commit.matches("[0-9a-f]{4,40}"), commit);
        String branch = AppInfo.gitBranchAsync().get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(branch, AppInfo.gitBranch());
    }
}
