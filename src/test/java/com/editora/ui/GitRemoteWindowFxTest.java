package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.scene.control.Tab;

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

            Path root = null;
            for (int i = 0; i < 400 && root == null; i++) {
                if (i % 100 == 0) {
                    FxTestSupport.runOnFx(git::refresh); // a newer request drops the answer of the one before
                }
                Thread.sleep(50);
                root = FxTestSupport.callOnFx(git::repoRoot);
            }

            assertEquals(sftp.remotePath("app"), root, "the active repository is the one on the host");
            assertEquals("main", FxTestSupport.callOnFx(() -> git.status().branch()));
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            boolean marked = false;
            for (int i = 0; i < 200 && !marked; i++) {
                Thread.sleep(25);
                marked = FxTestSupport.callOnFx(() -> {
                    Tab tab = area.selectedTab();
                    return tab.getStyleClass().contains("git-status-modified");
                });
            }
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

            FxTestSupport.runOnFx(git::refresh);
            Thread.sleep(500);
            async.awaitFx();

            assertNull(FxTestSupport.callOnFx(git::repoRoot));
            assertEquals(before, sftp.commands().size(), "nothing was run on the host");
        }
    }
}
