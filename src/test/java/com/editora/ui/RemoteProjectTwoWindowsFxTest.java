package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.RemoteFileSystems;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole path with real windows: a remote project's window opens not connected and says so; a connect
 * made in another window is carried out again by the project's own window, which then shows the folder on a
 * connection of its own, while the window the connect came from is left as it was.
 */
@Tag("fx")
class RemoteProjectTwoWindowsFxTest {

    @org.junit.jupiter.api.BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @Test
    void theProjectsWindowConnectsForItselfAndTheOtherWindowIsLeftAlone(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.createDirectories(sftp.serverPath("app/src"));
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setProjectSupport(true));
            MainController first = fx.controller;
            CoordinatorHost host = FxTestSupport.field(first, "coordinatorHost");
            RemoteCoordinator.Ops ops =
                    (RemoteCoordinator.Ops) FxTestSupport.call(first, "remoteOps", new Class<?>[] {});
            // The first window's own engine is the fixture's: it is connected, as after the connect form.
            RemoteCoordinator remote = new RemoteCoordinator(host, ops, sftp.fileSystems());
            Path root = sftp.remotePath("app");
            String authority = sftp.connection().id();
            ProjectManager projects = FxTestSupport.field(first, "projects");
            Project project = FxTestSupport.callOnFx(() -> projects.createOrGet("app", root));
            assertEquals("sftp://" + authority + "/app", project.root());

            // Reopened from the project list, with nobody handing it a login: its window opens anyway.
            sftp.disconnect();
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(project));
            async.awaitFx();
            MainController second = FxTestSupport.callOnFx(() -> fx.windowManager.controllerOf(project));
            assertNotNull(second, "the project's window was not opened");
            assertNotSame(first, second);
            async.awaitFx();
            assertTrue(
                    FxTestSupport.callOnFx(() -> second.remoteCoordinator().showsDisconnected()),
                    "a window whose project's host is not connected says so");
            assertNull(FxTestSupport.callOnFx(() -> FxTestSupport.<Path>field(second, "projectRoot")));

            // Its engine in a test: a throwaway known_hosts instead of the user's, and no host-key dialog.
            RemoteFileSystems engine = sftp.newEngine(dir);
            FxTestSupport.runOnFx(() -> setEngine(second.remoteCoordinator(), engine));
            Path found = sftp.reconnect().resolve("app"); // the first window's connect form succeeds

            FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), found, "password".toCharArray()));

            Path shown = null;
            for (int i = 0; i < 400 && shown == null; i++) {
                Thread.sleep(25);
                shown = FxTestSupport.callOnFx(() -> FxTestSupport.<Path>field(second, "projectRoot"));
            }
            assertNotNull(shown, "the project's window never showed the folder");
            assertEquals("/app", shown.toString());
            assertEquals("sftp://" + authority + "/app", Vfs.toStorableString(shown));
            assertTrue(engine.isConnected(authority), "on a connection of its own");
            assertEquals(shown, project.rootPath(), "which is what the project's root now resolves to");
            assertFalse(FxTestSupport.callOnFx(() -> second.remoteCoordinator().showsDisconnected()));
            assertFalse(
                    sftp.fileSystems().isConnected(authority),
                    "the first window's connection only found the folder, and is closed");
            assertNull(
                    FxTestSupport.callOnFx(() -> FxTestSupport.<Path>field(first, "projectRoot")),
                    "the first window's tree is not the remote folder");
        }
    }

    private static void setEngine(RemoteCoordinator coordinator, RemoteFileSystems engine) {
        try {
            java.lang.reflect.Field field = RemoteCoordinator.class.getDeclaredField("remoteFs");
            field.setAccessible(true);
            field.set(coordinator, engine);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
