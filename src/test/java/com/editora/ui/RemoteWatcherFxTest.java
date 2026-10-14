package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote folder is watched on its host: what another program changes there reaches Editora as it does from
 * a local folder's watcher. The host here is the in-process SSH server's shell; the polling fallback is
 * forced (this machine may or may not have inotifywait).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteWatcherFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private Path serverApp;
    private Path remoteApp;
    private RemoteWatcher watcher;
    private final List<ProjectPanel.FsChange> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        RemoteWatcher.pollForTest = true;
        sftp = EmbeddedSftpFixture.startWithShell(base);
        serverApp = Files.createDirectories(sftp.serverPath("app"));
        Files.createDirectories(serverApp.resolve("src"));
        Files.writeString(serverApp.resolve("src/Old.java"), "class Old {}\n");
        Files.createDirectories(serverApp.resolve(".git"));
        remoteApp = sftp.remotePath("app");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) {
            watcher.stop();
        }
        RemoteWatcher.pollForTest = false;
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private void awaitChange(Path path, ProjectPanel.FsKind kind) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            if (seen.stream().anyMatch(c -> c.path().equals(path) && c.kind() == kind)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("never reported " + kind + " " + path + "; saw " + seen);
    }

    @Test
    void whatAnotherProgramChangesOnTheHostIsReported() throws Exception {
        watcher = RemoteWatcher.start(remoteApp, seen::addAll);
        assertNotNull(watcher);
        Thread.sleep(1500); // the poll's first stamp must predate the changes

        Files.writeString(serverApp.resolve("src/New.java"), "class New {}\n");
        Files.delete(serverApp.resolve("src/Old.java"));
        Files.writeString(serverApp.resolve(".git/index"), "churn");

        awaitChange(remoteApp.resolve("src/New.java"), ProjectPanel.FsKind.CHANGED);
        awaitChange(remoteApp.resolve("src"), ProjectPanel.FsKind.CHANGED); // a deletion shows as its folder
        assertFalse(
                seen.stream().anyMatch(c -> c.path().toString().contains("/.git")),
                ".git churn is not reported: " + seen);
        assertTrue(seen.stream().allMatch(c -> c.path().getFileSystem() == remoteApp.getFileSystem()));
    }

    @Test
    void stoppingEndsTheCommandOnTheHost() throws Exception {
        watcher = RemoteWatcher.start(remoteApp, seen::addAll);
        assertTrue(watcher.isRunning());

        watcher.stop();

        for (int i = 0; i < 100 && watcher.isRunning(); i++) {
            Thread.sleep(50);
        }
        assertFalse(watcher.isRunning());
    }

    @Test
    void aHostThatMayNotRunCommandsIsNotWatched() {
        ExecTargets.setRemoteEnabled(() -> false);

        assertNull(RemoteWatcher.start(remoteApp, seen::addAll));
    }

    @Test
    void inotifyEventsAreTheTreesKinds() {
        assertEquals(ProjectPanel.FsKind.CREATED, RemoteWatcher.kind("CREATE"));
        assertEquals(ProjectPanel.FsKind.CREATED, RemoteWatcher.kind("CREATE,ISDIR"));
        assertEquals(ProjectPanel.FsKind.CREATED, RemoteWatcher.kind("MOVED_TO"));
        assertEquals(ProjectPanel.FsKind.DELETED, RemoteWatcher.kind("DELETE"));
        assertEquals(ProjectPanel.FsKind.DELETED, RemoteWatcher.kind("MOVED_FROM,ISDIR"));
        assertEquals(ProjectPanel.FsKind.CHANGED, RemoteWatcher.kind("CLOSE_WRITE,CLOSE"));
        assertEquals(ProjectPanel.FsKind.CHANGED, RemoteWatcher.kind("CHANGED"));
    }

    @Test
    void aReportedLineBecomesAPathOnTheHostsFilesystem() {
        var target = ExecTargets.forPath(remoteApp);

        ProjectPanel.FsChange change =
                RemoteWatcher.parse(target, "MODIFY|" + target.toTargetPath(remoteApp) + "/a b.txt");

        assertNotNull(change);
        assertEquals(remoteApp.resolve("a b.txt"), change.path());
        assertNull(RemoteWatcher.parse(target, "garbage"), "not a report");
        assertNull(RemoteWatcher.parse(target, "MODIFY|relative/path"), "not an absolute path");
        assertNull(
                RemoteWatcher.parse(target, "CREATE|" + target.toTargetPath(remoteApp) + "/.editora-typst-x.typ"),
                "Editora's own throwaway files are left out");
    }

    @Test
    void aRemoteProjectTreeIsWatchedOnItsHost() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            ProjectPanel panel = FxTestSupport.callOnFx(() -> {
                FxTestSupport.call(fx.controller, "setProjectRoot", new Class<?>[] {Path.class}, remoteApp);
                return fx.controller.projectPanel();
            });

            assertTrue(FxTestSupport.callOnFx(panel::watchesRemotely), "the remote tree is not watched");

            FxTestSupport.runOnFx(() ->
                    FxTestSupport.call(fx.controller, "setProjectRoot", new Class<?>[] {Path.class}, (Object) null));
            assertFalse(FxTestSupport.callOnFx(panel::watchesRemotely), "still watching a folder no longer shown");
        }
    }
}
