package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote folder is a project, and a project has its own window: the window that shows it is the one
 * connected to its host, and it says so, with a way back, when that connection is not there.
 */
@Tag("fx")
class RemoteProjectWindowFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        com.editora.i18n.Messages.init("en");
    }

    @TempDir
    Path dir;

    private EmbeddedSftpFixture sftp;
    private ProjectManager projects;
    private Ops ops;
    private Host host;
    private RemoteCoordinator remote;

    @BeforeEach
    void setUp() throws Exception {
        sftp = EmbeddedSftpFixture.start(dir.resolve("server"));
        Files.createDirectories(sftp.serverPath("app/src"));
        projects = new ProjectManager(dir.resolve("config"));
        ops = new Ops();
        host = new Host();
        remote = new RemoteCoordinator(host, ops, sftp.fileSystems());
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
    }

    private Path appRoot() {
        return sftp.remotePath("app");
    }

    private String appUri() {
        return "sftp://" + sftp.connection().id() + "/app";
    }

    @Test
    void connectingFromAnotherWindowOpensTheProjectsOwnWindowAndHandsItTheLogin() throws Exception {
        char[] secret = "password".toCharArray();

        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), secret));

        Project project = ops.openedProject.get();
        assertNotNull(project, "the project's window was never asked for");
        assertEquals(appUri(), project.root(), "the project is the folder on that host");
        assertEquals("app", project.name());
        assertEquals(List.of(project), projects.list());
        assertEquals("/app", ops.openedWith.get().lastPath(), "its window connects straight to the folder");
        assertArrayEquals("password".toCharArray(), ops.openedSecret.get(), "the login is passed on, not wiped here");
        assertTrue(ops.roots.isEmpty(), "this window's tree is left alone");
        assertFalse(
                sftp.fileSystems().isConnected(sftp.connection().id()),
                "the connection that only found the folder is closed: its window holds its own");
    }

    @Test
    void connectingInTheProjectsOwnWindowMountsTheFolderThere() throws Exception {
        ops.window = projects.createOrGet("app", appRoot());
        char[] secret = "password".toCharArray();

        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), secret));

        assertNull(ops.openedProject.get(), "no second window");
        assertEquals(List.of(appRoot()), ops.roots);
        assertArrayEquals(new char[8], secret, "nobody else needs the login: wiped");
        assertTrue(sftp.fileSystems().isConnected(sftp.connection().id()));
        assertEquals(0, ops.disconnectedShown.size());
    }

    @Test
    void withoutProjectsARemoteFolderIsOnlyShownInThisWindow() throws Exception {
        ops.projectsOn = false;

        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), null));

        assertEquals(List.of(appRoot()), ops.roots);
        assertTrue(projects.list().isEmpty());
    }

    @Test
    void aWindowHandedALoginConnectsAndShowsItsProject() throws Exception {
        sftp.disconnect();
        ops.window = new Project("app", "app", appUri());
        RemoteConnection at = new RemoteConnection(
                "127.0.0.1",
                sftp.connection().port(),
                "tester",
                RemoteConnection.AuthMethod.PASSWORD,
                "",
                null,
                "/app");
        CountDownLatch mounted = ops.expectRoot();

        FxTestSupport.runOnFx(() -> remote.connectForWindowProject(at, "password".toCharArray()));

        assertTrue(mounted.await(30, TimeUnit.SECONDS), "never mounted");
        assertEquals("/app", ops.roots.getLast().toString());
        assertEquals(ops.roots.getLast(), ops.window.rootPath(), "the project's root is now a live path");
    }

    @Test
    void aLoginThatFailsLeavesTheWindowOpenSayingItIsNotConnected() throws Exception {
        sftp.disconnect();
        ops.window = new Project("app", "app", "sftp://tester@127.0.0.1:1/app");
        RemoteConnection nowhere =
                new RemoteConnection("127.0.0.1", 1, "tester", RemoteConnection.AuthMethod.PASSWORD, "", null, "/app");
        CountDownLatch failed = new CountDownLatch(1);
        ops.onError = failed::countDown;

        FxTestSupport.runOnFx(() -> remote.connectForWindowProject(nowhere, "password".toCharArray()));

        assertTrue(failed.await(60, TimeUnit.SECONDS), "the failure was never reported");
        assertEquals(List.of("Not connected to tester@127.0.0.1:1"), ops.disconnectedShown);
        assertTrue(ops.roots.isEmpty());
    }

    @Test
    void losingTheConnectionEmptiesTheTreeAndOffersToReconnect() throws Exception {
        ops.window = projects.createOrGet("app", appRoot());
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), null));
        CountDownLatch emptied = ops.expectRoot();

        sftp.disconnect(); // the session ends: here, as it would when the network drops

        assertTrue(emptied.await(30, TimeUnit.SECONDS), "the tree kept showing a folder it cannot read");
        assertNull(ops.roots.getLast());
        assertEquals(List.of("Not connected to " + sftp.connection().id()), ops.disconnectedShown);
        assertEquals("The connection to " + sftp.connection().id() + " was lost", host.last);
        assertNull(ops.window.rootPath());
    }

    @Test
    void reconnectingTakesTheMessageAwayAgain() throws Exception {
        ops.window = projects.createOrGet("app", appRoot());
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), null));
        CountDownLatch emptied = ops.expectRoot();
        sftp.disconnect();
        assertTrue(emptied.await(30, TimeUnit.SECONDS));

        Path again = sftp.reconnect().resolve("app");
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), again, null));

        assertEquals(again, ops.roots.getLast());
        assertTrue(ops.cleared >= 2, "the not-connected message was left up");
    }

    private static final class Host extends CoordinatorHostStub {
        volatile String last;

        @Override
        public void setStatus(String message) {
            last = message;
        }
    }

    private final class Ops implements RemoteCoordinator.Ops {
        final List<Path> roots = new CopyOnWriteArrayList<>();
        final List<String> disconnectedShown = new CopyOnWriteArrayList<>();
        final AtomicReference<Project> openedProject = new AtomicReference<>();
        final AtomicReference<RemoteConnection> openedWith = new AtomicReference<>();
        final AtomicReference<char[]> openedSecret = new AtomicReference<>();
        volatile Project window;
        volatile boolean projectsOn = true;
        volatile int cleared;
        volatile Runnable onError = () -> {};
        private volatile CountDownLatch nextRoot;

        CountDownLatch expectRoot() {
            nextRoot = new CountDownLatch(1);
            return nextRoot;
        }

        @Override
        public KeymapManager keymap() {
            return null;
        }

        @Override
        public void openPath(Path file) {}

        @Override
        public void setProjectRoot(Path root) {
            roots.add(root);
            CountDownLatch waiting = nextRoot;
            if (waiting != null) {
                waiting.countDown();
            }
        }

        @Override
        public void openProjectToolWindow() {}

        @Override
        public Path activeProjectRoot() {
            return null;
        }

        @Override
        public void reportError(String summary, String detail) {
            onError.run();
        }

        @Override
        public List<RemoteConnection> connections() {
            return List.of();
        }

        @Override
        public void putConnection(RemoteConnection conn) {}

        @Override
        public void removeConnection(String id) {}

        @Override
        public void invalidatePendingWrite(Path path) {}

        @Override
        public Project windowProject() {
            return window;
        }

        @Override
        public boolean projectsEnabled() {
            return projectsOn;
        }

        @Override
        public ProjectManager projects() {
            return projects;
        }

        @Override
        public void openRemoteProject(Project project, RemoteConnection conn, char[] secret) {
            openedProject.set(project);
            openedWith.set(conn);
            openedSecret.set(secret);
        }

        @Override
        public void showDisconnected(String message, String buttonText, Runnable reconnect) {
            disconnectedShown.add(message);
        }

        @Override
        public void clearDisconnected() {
            cleared++;
        }
    }
}
