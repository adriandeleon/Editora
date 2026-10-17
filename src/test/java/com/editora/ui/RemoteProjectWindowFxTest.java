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
import com.editora.vfs.ConnectFailure;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.RemoteFileSystems;
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
        // Off, as before it became the default: a mount with it on puts the host question, a modal dialog.
        // The tests about that question switch it on.
        host.settings.setRemoteExecution(false);
        remote = new RemoteCoordinator(host, ops, sftp.fileSystems());
        remote.connectFailureShown = ops::reportError; // no modal dialog in a test: counted as the Ops report
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
    void connectingFromAnotherWindowOpensTheProjectsOwnWindowAndHandsItTheConnection() throws Exception {
        char[] secret = "password".toCharArray();

        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), secret));

        Project project = ops.openedProject.get();
        assertNotNull(project, "the project's window was never asked for");
        assertEquals(appUri(), project.root(), "the project is the folder on that host");
        assertEquals("app", project.name());
        assertEquals(List.of(project), projects.list());
        assertEquals("/app", ops.openedWith.get().lastPath(), "its window opens straight to the folder");
        assertNotNull(ops.openedLive.get(), "the logged-in session is handed over");
        assertEquals(sftp.connection().id(), ops.openedLive.get().authority());
        assertNull(ops.openedSecret.get(), "nothing to log in with again");
        assertArrayEquals(new char[8], secret, "the password is wiped: nobody needs it now");
        assertTrue(ops.roots.isEmpty(), "this window's tree is left alone");
        assertFalse(
                sftp.fileSystems().isConnected(sftp.connection().id()),
                "this window no longer holds the connection: the project's window does");
        ops.openedLive.get().close();
    }

    @Test
    void theProjectsWindowTakesTheConnectionOverWithoutLoggingInAgain() throws Exception {
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), "password".toCharArray()));
        RemoteFileSystems.LiveConnection live = ops.openedLive.get();
        assertNotNull(live);
        int logins = sftp.logins();

        RemoteFileSystems other = sftp.newEngine(dir);
        Ops otherOps = new Ops();
        otherOps.window = projects.createOrGet("app", appRoot());
        Host otherHost = new Host();
        // Off, as in setUp: with it on, the mount puts the host question, a modal dialog left open for later tests.
        otherHost.settings.setRemoteExecution(false);
        RemoteCoordinator projectWindow = new RemoteCoordinator(otherHost, otherOps, other);
        try {
            FxTestSupport.runOnFx(() -> projectWindow.adoptForWindowProject(ops.openedWith.get(), live));

            assertEquals(logins, sftp.logins(), "no second login");
            assertTrue(other.isConnected(sftp.connection().id()), "the project's window holds the connection");
            assertEquals(List.of(appRoot()), otherOps.roots, "and shows its folder");
            Files.writeString(sftp.serverPath("app/hello.txt"), "hi");
            assertEquals("hi", Files.readString(otherOps.roots.getFirst().resolve("hello.txt")), "over SFTP");

            sftp.fileSystems().shutdown(); // the window that logged in closes
            assertEquals(
                    "hi",
                    Files.readString(otherOps.roots.getFirst().resolve("hello.txt")),
                    "closing the first window leaves the project's window connected");
        } finally {
            other.shutdown();
        }
    }

    @Test
    void aConnectionThisWindowStillShowsStaysAndTheProjectsWindowLogsInItself() throws Exception {
        ops.projectsOn = false; // this window shows the host's folder first, as it did before projects
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), null));
        ops.projectsOn = true;
        char[] secret = "password".toCharArray();

        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), secret));

        assertNull(ops.openedLive.get(), "a connection in use here is not handed away");
        assertArrayEquals("password".toCharArray(), ops.openedSecret.get(), "the login is passed on instead");
        assertTrue(sftp.fileSystems().isConnected(sftp.connection().id()));
    }

    @Test
    void aLoginThePersonCancelledOrAHostKeyTheyDeclinedOnlyShowsInTheStatusBar() throws Exception {
        AtomicReference<String> dialog = new AtomicReference<>();
        remote.connectFailureShown = (header, body) -> dialog.set(header);
        RemoteConnection conn = sftp.connection();

        for (ConnectFailure.Kind kind : List.of(ConnectFailure.Kind.CANCELLED, ConnectFailure.Kind.HOST_KEY_DECLINED)) {
            FxTestSupport.runOnFx(() -> remote.reportConnectFailure(
                    conn, new RemoteFileSystems.Result(false, null, "x", new ConnectFailure(kind, "x"))));
            assertNull(dialog.get(), kind + ": they did it themselves, nothing to explain");
            assertEquals(com.editora.i18n.Messages.tr("status.remote.cancelled", conn.displayLabel()), host.last);
        }
    }

    @Test
    void theFailureDialogIsSizedToItsTextAsItWraps() {
        assertEquals(1, RemoteCoordinator.wrappedLines("short", 64));
        assertEquals(3, RemoteCoordinator.wrappedLines("x".repeat(130), 64), "one long line wraps to three");
        assertEquals(4, RemoteCoordinator.wrappedLines("x".repeat(70) + "\n\n" + "y", 64), "blank lines count");
    }

    @Test
    void aChangedHostKeySaysHowToForgetTheOldOneAndARefusedLoginWhatToCheck() throws Exception {
        java.util.Map<String, String> shown = new java.util.LinkedHashMap<>();
        remote.connectFailureShown = shown::put;
        RemoteConnection conn = sftp.connection();

        FxTestSupport.runOnFx(() -> remote.reportConnectFailure(
                conn,
                new RemoteFileSystems.Result(
                        false,
                        null,
                        "Server key did not validate",
                        new ConnectFailure(ConnectFailure.Kind.HOST_KEY_CHANGED, "Server key did not validate"))));
        FxTestSupport.runOnFx(() -> remote.reportConnectFailure(
                conn,
                new RemoteFileSystems.Result(
                        false,
                        null,
                        "No more authentication methods available",
                        new ConnectFailure(
                                ConnectFailure.Kind.AUTH_FAILED, "No more authentication methods available"))));

        List<String> headers = List.copyOf(shown.keySet());
        assertEquals(2, headers.size());
        String changed = shown.get(headers.get(0));
        assertTrue(
                changed.contains("ssh-keygen -R [127.0.0.1]:" + conn.port()),
                "names the known_hosts entry to remove: " + changed);
        assertTrue(changed.endsWith("Server key did not validate"), "the underlying message stays, last");
        assertTrue(shown.get(headers.get(1)).contains("authorized_keys"), shown.get(headers.get(1)));
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
        assertFalse(remote.showsDisconnected());
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
        assertTrue(remote.showsDisconnected(), "the window says it is not connected");
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
        assertTrue(remote.showsDisconnected(), "with the way back");
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
        assertTrue(remote.showsDisconnected());

        Path again = sftp.reconnect().resolve("app");
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), again, null));

        assertEquals(again, ops.roots.getLast());
        assertFalse(remote.showsDisconnected(), "the not-connected message was left up");
    }

    @Test
    void theProjectPanelSaysNotConnectedWithAButtonEvenWhenItIsBuiltLater() throws Exception {
        ops.window = new Project("app", "app", "sftp://tester@127.0.0.1:1/app");
        RemoteConnection nowhere =
                new RemoteConnection("127.0.0.1", 1, "tester", RemoteConnection.AuthMethod.PASSWORD, "", null, "/app");
        CountDownLatch failed = new CountDownLatch(1);
        ops.onError = failed::countDown;
        FxTestSupport.runOnFx(() -> remote.connectForWindowProject(nowhere, null));
        assertTrue(failed.await(60, TimeUnit.SECONDS));

        // The Project panel is built on its first use, which may be after the connection failed.
        String shown = FxTestSupport.callOnFx(() -> {
            FxWindowFixture window = FxWindowFixture.create();
            try {
                ProjectPanel panel = FxTestSupport.field(window.controller, "projectPanel");
                if (panel == null) {
                    return "no panel";
                }
                panel.setRoot(null);
                remote.panelBuilt(panel);
                return panel.showsDisconnected() ? "disconnected" : "placeholder";
            } finally {
                window.close();
            }
        });
        org.junit.jupiter.api.Assumptions.assumeFalse("no panel".equals(shown), "the fixture built no Project panel");
        assertEquals("disconnected", shown);
    }

    private int asked;

    /** Mounts the project's folder in its own window and lets the queued question (if any) be put. */
    private void mountInOwnWindow(boolean answer) throws Exception {
        ops.window = projects.createOrGet("app", appRoot());
        remote.execConsent = conn -> {
            asked++;
            return answer;
        };
        FxTestSupport.runOnFx(() -> remote.connected(sftp.connection(), appRoot(), null));
        FxTestSupport.runOnFx(() -> {}); // the question is queued behind the mount
    }

    @Test
    void theQuestionAboutRunningCommandsIsPutOncePerHostAndYesIsRemembered() throws Exception {
        host.settings.setRemoteExecution(true);

        mountInOwnWindow(true);

        assertEquals(1, asked);
        assertEquals(List.of(sftp.connection().id()), ops.execAllowed, "remembered with the saved connections");
        assertEquals(1, ops.execChanges, "the features that run tools there look again");

        FxTestSupport.runOnFx(remote::offerExecution);
        assertEquals(1, asked, "a host that was allowed is not asked about again");
    }

    @Test
    void notNowIsRespectedForTheSessionAndNothingIsRemembered() throws Exception {
        host.settings.setRemoteExecution(true);

        mountInOwnWindow(false);
        FxTestSupport.runOnFx(remote::offerExecution);

        assertEquals(1, asked, "not on every connect");
        assertTrue(ops.execAllowed.isEmpty());

        FxTestSupport.runOnFx(remote::toggleExecution); // off
        FxTestSupport.runOnFx(remote::toggleExecution); // and on again: that is asking for it
        assertEquals(2, asked);
    }

    @Test
    void withTheSettingOffTheQuestionIsNeverPut() throws Exception {
        mountInOwnWindow(true);

        assertEquals(0, asked);
        assertTrue(ops.execAllowed.isEmpty());
    }

    private static final class Host extends CoordinatorHostStub {
        final com.editora.config.Settings settings = new com.editora.config.Settings();
        volatile String last;

        @Override
        public com.editora.config.Settings settings() {
            return settings;
        }

        @Override
        public void requestSave() {}

        @Override
        public void syncSettingsWindow() {}

        @Override
        public void setStatus(String message) {
            last = message;
        }
    }

    private final class Ops implements RemoteCoordinator.Ops {
        final List<Path> roots = new CopyOnWriteArrayList<>();
        final AtomicReference<Project> openedProject = new AtomicReference<>();
        final AtomicReference<RemoteConnection> openedWith = new AtomicReference<>();
        final AtomicReference<char[]> openedSecret = new AtomicReference<>();
        final AtomicReference<RemoteFileSystems.LiveConnection> openedLive = new AtomicReference<>();
        volatile Project window;
        volatile boolean projectsOn = true;
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

        final List<String> execAllowed = new CopyOnWriteArrayList<>();
        volatile int execChanges;

        @Override
        public boolean remoteExecAllowed(String id) {
            return execAllowed.contains(id);
        }

        @Override
        public void allowRemoteExec(String id) {
            execAllowed.add(id);
        }

        @Override
        public void remoteExecChanged() {
            execChanges++;
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
        public void openRemoteProject(
                Project project, RemoteConnection conn, RemoteFileSystems.LiveConnection live, char[] secret) {
            openedProject.set(project);
            openedWith.set(conn);
            openedLive.set(live);
            openedSecret.set(secret);
        }
    }
}
