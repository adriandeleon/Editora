package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.editora.editor.EditorBuffer;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window showing a file in a repository on a remote host: its Git state is that repository's, read on the
 * host, when the host may run commands, and nothing at all when it may not.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitRemoteWindowFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void restoreTheDefault() {
        ExecTargets.setRemoteEnabled(null);
    }

    private static void git(Path repo, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(repo, Duration.ofSeconds(30), argv);
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
    }

    private static Path repository(EmbeddedSftpFixture sftp) throws Exception {
        Path repo = Files.createDirectories(sftp.serverPath("app"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "ana@example.com");
        git(repo, "config", "user.name", "Ana");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "first");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo changed\n");
        return repo;
    }

    /**
     * The repository root the window settles on, refreshing again every five seconds (a newer request drops the
     * answer of the one before), for up to twenty; null when none came.
     */
    private static Path awaitRepoRoot(GitCoordinator git) throws Exception {
        for (int round = 0; round < 4; round++) {
            FxTestSupport.runOnFx(git::refresh);
            if (com.editora.testkit.Await.holdsWithin(
                    FxTestSupport.fxCondition(() -> git.repoRoot() != null), Duration.ofSeconds(5))) {
                break;
            }
        }
        return FxTestSupport.callOnFx(git::repoRoot);
    }

    private static EditorBuffer open(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(file);
            EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "document load");
        async.awaitFx();
        return buffer;
    }

    @Test
    void theWindowShowsTheRemoteRepositorysState(@TempDir Path dir) throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.startWithShell(dir));
            repository(sftp);
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = sftp.remotePath("app/a.txt");
            open(async, fx, file);
            GitCoordinator git = fx.controller.git;

            Path root = awaitRepoRoot(git);

            assertEquals(sftp.remotePath("app"), root, "the active repository is the one on the host");
            assertEquals("main", FxTestSupport.callOnFx(() -> git.status().branch()));
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            boolean marked = com.editora.testkit.Await.holdsWithin(
                    FxTestSupport.fxCondition(
                            () -> area.selectedTab().getStyleClass().contains("git-status-modified")),
                    Duration.ofSeconds(5));
            assertTrue(marked, "the modified remote file's tab is not coloured");
        }
    }

    @Test
    void withTheSettingOffARemoteFileHasNoGit(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.startWithShell(dir));
            repository(sftp);
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(async, fx, sftp.remotePath("app/a.txt"));
            GitCoordinator git = fx.controller.git;
            int before = sftp.commands().size();

            FxTestSupport.runOnFx(git::refresh); // decided on the FX thread: the setting is off
            async.awaitWorker(FxTestSupport.field(git.service(), "exec")); // and anything it queued all the same
            async.awaitFx();

            assertNull(FxTestSupport.callOnFx(git::repoRoot));
            assertEquals(before, sftp.commands().size(), "nothing was run on the host");
        }
    }

    /**
     * "Fetch automatically" in a window on a remote repository: the timer fetches it on the host, once the
     * folder is trusted, and origin/main moves there.
     */
    @Test
    void fetchAutomaticallyFetchesARemoteRepositoryOnItsHost(@TempDir Path dir) throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.startWithShell(dir));
            Path repo = repository(sftp);
            Path bare = sftp.serverPath("origin.git");
            git(sftp.serverPath(""), "clone", "-q", "--bare", repo.toString(), bare.toString());
            git(repo, "remote", "add", "origin", bare.toString());
            git(repo, "fetch", "-q", "origin");
            Path other = sftp.serverPath("elsewhere");
            git(sftp.serverPath(""), "clone", "-q", bare.toString(), other.toString());
            git(other, "config", "user.email", "bo@example.com");
            git(other, "config", "user.name", "Bo");
            git(other, "config", "commit.gpgsign", "false");
            git(other, "commit", "-q", "--allow-empty", "-m", "second");
            git(other, "push", "-q", "origin", "main");
            String second = ProcessRunner.run(other, Duration.ofSeconds(30), List.of("git", "rev-parse", "HEAD"))
                    .out()
                    .strip();

            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            open(async, fx, sftp.remotePath("app/a.txt"));
            GitFeatureFx w = GitFeatureFx.attach(fx);
            Path remoteRoot = sftp.remotePath("app");
            Path root = awaitRepoRoot(w.git);
            assertEquals(remoteRoot, root, "the remote repository");
            GitAutoFetch autoFetch = w.git.autoFetch();
            FxTestSupport.runOnFx(() -> {
                autoFetch.setTrust(remoteRoot::equals);
                autoFetch.setRecentlyActiveForTest(() -> true);
                fx.shared.getSettings().setGitAutoFetch(true);
                autoFetch.setPeriodForTest(javafx.util.Duration.millis(200));
            });

            // origin/main is read again each time a fetch has run and the network lane is free again
            String[] fetchedNow = {""};
            int[] checkedAfter = {0};
            com.editora.testkit.Await.holdsWithin(
                    FxTestSupport.unchecked(() -> {
                        boolean[] idle = {false};
                        int ran = FxTestSupport.callOnFx(() -> {
                            idle[0] = !w.git.service().networkBusy();
                            return autoFetch.runsForTest();
                        });
                        if (ran == checkedAfter[0] || !idle[0]) {
                            return false;
                        }
                        checkedAfter[0] = ran;
                        fetchedNow[0] = ProcessRunner.run(
                                        repo, Duration.ofSeconds(30), List.of("git", "rev-parse", "origin/main"))
                                .out()
                                .strip();
                        return second.equals(fetchedNow[0]);
                    }),
                    Duration.ofSeconds(30));
            String fetched = fetchedNow[0];
            FxTestSupport.runOnFx(autoFetch::stop);
            assertEquals(second, fetched, "origin/main fetched on the host");
            assertTrue(FxTestSupport.callOnFx(autoFetch::runsForTest) > 0);
        }
    }
}
