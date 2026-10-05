package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.scene.control.Tab;

import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitChangeBars;
import com.editora.git.GitService;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote (SFTP) tab next to local ones. A MINA SFTP path answers {@code equals}/{@code startsWith} against a
 * local path with {@code ProviderMismatchException}, so every place that compares "the open buffers" with a
 * local path must first notice the two are on different file systems — over a real embedded SFTP server.
 */
@Tag("fx")
class RemoteTabBesideLocalFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void pathHelpersAnswerInsteadOfThrowingAcrossFileSystems(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("remote.txt"), "remote\n");
            Path remote = sftp.remotePath("remote.txt");
            Path local = Files.writeString(dir.resolve("local.txt"), "local\n");

            assertFalse(PathKeys.sameNormalized(remote, local));
            assertFalse(PathKeys.sameNormalized(local, remote));
            assertFalse(PathKeys.samePath(remote, local));
            assertTrue(PathKeys.samePath(remote, remote));
            assertTrue(PathKeys.samePath(null, null));
            assertFalse(PathKeys.samePath(remote, null));
            assertNull(GitService.repoRelative(dir, remote), "a remote file is never inside a local repository");
            assertFalse(GitChangeBars.shouldRediff(remote, dir, false, false));
        }
    }

    @Test
    void localTabsKeepWorkingAfterARemoteTabWasActive(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) { // fails the test on any uncaught FX-thread exception
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("remote.txt"), "remote\n");
            Path alpha = Files.writeString(dir.resolve("alpha.txt"), "alpha\n");
            Path beta = Files.writeString(dir.resolve("beta.txt"), "beta\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ProjectPanel project = FxTestSupport.field(fx.controller, "projectPanel");

            EditorBuffer a = open(async, workflows, area, alpha);
            EditorBuffer b = open(async, workflows, area, beta);
            open(async, workflows, area, sftp.remotePath("remote.txt")); // the remote tab is now the active one

            // D1-1: the tab-selection listener used to abort in ProjectPanel.setActiveFile, on EVERY later switch.
            FxTestSupport.runOnFx(() -> area.select(tabFor(area, a)));
            async.awaitFx();
            assertEquals(alpha.toAbsolutePath().normalize(), FxTestSupport.<Path>field(project, "activeFile"));
            FxTestSupport.runOnFx(() -> area.select(tabFor(area, b)));
            async.awaitFx();
            assertEquals(beta.toAbsolutePath().normalize(), FxTestSupport.<Path>field(project, "activeFile"));

            // D1-3: "the buffers at or under this local path" (LSP workspace edits, renames, deletes).
            @SuppressWarnings("unchecked")
            List<EditorBuffer> under = (List<EditorBuffer>) FxTestSupport.callOnFx(() ->
                    FxTestSupport.call(fx.controller, "buffersAtOrUnderLocal", new Class<?>[] {Path.class}, alpha));
            assertEquals(List.of(a), under);

            // D1-2: every working-tree mutation starts (and ends) by walking all open buffers.
            Object git = FxTestSupport.field(fx.controller, "git");
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(
                        git, "invalidatePendingWrites", new Class<?>[] {Path.class, List.class}, dir, List.of());
                FxTestSupport.call(
                        fx.controller,
                        "invalidatePendingGitWritesLocal",
                        new Class<?>[] {Path.class, List.class},
                        dir,
                        List.of());
            });
            async.awaitFx();
        }
    }

    private static EditorBuffer open(
            AsyncTestScope async, FileWorkflowCoordinator workflows, EditorArea area, Path file) throws Exception {
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

    private static Tab tabFor(EditorArea area, EditorBuffer buffer) {
        return area.tabs().stream()
                .filter(tab -> tab.getUserData() == buffer)
                .findFirst()
                .orElseThrow();
    }
}
