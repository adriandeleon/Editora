package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.EmbeddedSftpFixture.StageBlock;
import com.editora.vfs.RemoteFileSystems;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a save must not do to a file on an SFTP server, driven through the real open → edit → {@code
 * file.save} workflow against the chrooted embedded server. Each case is one a local file already survived.
 */
@Tag("fx")
class RemoteSaveFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void savingThroughARemoteSymlinkWritesItsTargetAndKeepsTheLink(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.createDirectories(sftp.serverPath("sites-available"));
            Files.createDirectories(sftp.serverPath("sites-enabled"));
            Path real = Files.writeString(sftp.serverPath("sites-available/site"), "real\n");
            Path link = sftp.serverPath("sites-enabled/site");
            Files.createSymbolicLink(link, Path.of("../sites-available/site"));
            // A link to a link, in the same folder: the chain is followed to its end.
            Path alias = sftp.serverPath("sites-enabled/alias");
            Files.createSymbolicLink(alias, Path.of("site"));
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("sites-enabled/alias"));

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            save(async, fx);

            assertEquals("real\nedited\n", Files.readString(real), "the file behind the link is what was saved");
            assertTrue(Files.isSymbolicLink(link), "the link is still a link");
            assertTrue(Files.isSymbolicLink(alias), "and so is the link to it");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(hasStagingFile(sftp.serverPath("sites-enabled")));
            assertFalse(hasStagingFile(sftp.serverPath("sites-available")));
        }
    }

    @Test
    void aReadOnlyRemoteFileOpensInViewModeAndIsNotReplacedWithoutAsking(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Path file = Files.writeString(sftp.serverPath("ro.txt"), "locked\n");
            assumePosix(file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("ro.txt"));

            assertTrue(FxTestSupport.callOnFx(buffer::isViewMode), "read-only on the server: opened to look at");
            // The banner says why, as it does for a local file; "Enable Editing" would promise a plain save.
            javafx.scene.control.Button enable = FxTestSupport.field(buffer, "enableEditingButton");
            Label readOnlyNote = FxTestSupport.field(buffer, "viewModeNote");
            assertFalse(FxTestSupport.callOnFx(enable::isVisible), "no Enable Editing for a file with no write bit");
            assertTrue(FxTestSupport.callOnFx(readOnlyNote::isVisible));
            assertEquals(tr("viewmode.note"), FxTestSupport.callOnFx(readOnlyNote::getText));
            Files.writeString(sftp.serverPath("rw.txt"), "open\n");
            EditorBuffer writable = openRemote(async, fx, sftp.remotePath("rw.txt"));
            FxTestSupport.runOnFx(() -> writable.setViewMode(true));
            javafx.scene.control.Button enableWritable = FxTestSupport.field(writable, "enableEditingButton");
            assertTrue(FxTestSupport.callOnFx(enableWritable::isVisible), "a writable remote file still offers it");
            FxTestSupport.runOnFx(() -> editorArea(fx)
                    .select(editorArea(fx).tabs().stream()
                            .filter(tab -> tab.getUserData() == buffer)
                            .findFirst()
                            .orElseThrow()));
            async.awaitFx();
            FxTestSupport.runOnFx(() -> {
                buffer.setViewMode(false);
                buffer.getArea().insertText(0, "changed\n");
            });

            // A background save never asks, and never writes.
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(buffer));
            settle(async, workflows);
            assertEquals("locked\n", Files.readString(file));
            assertEquals(tr("status.autoSave.cannotWriteReadOnly", "ro.txt"), echo(fx));

            CountDownLatch declined = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            save(async, fx);
            async.await(declined, "the read-only confirmation");
            assertEquals("locked\n", Files.readString(file), "declined: the file is untouched");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            CountDownLatch agreed = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.OK_DONE);
            save(async, fx);
            async.await(agreed, "the read-only confirmation");
            assertEquals("changed\nlocked\n", Files.readString(file));
            assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @ParameterizedTest(name = "SFTP protocol {0}")
    @ValueSource(ints = {3, 6})
    void aRemoteFileInAFolderThatAllowsNoNewEntriesIsSavedInPlace(int protocol, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            sftp.speakProtocolVersion(protocol);
            Path locked = Files.createDirectories(sftp.serverPath("locked"));
            Path file = Files.writeString(locked.resolve("conf.txt"), "v1\n");
            assumePosix(file);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
            async.onClose(() -> Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x")));
            Assumptions.assumeFalse(Files.isWritable(locked), "running as a user the mode does not bind (root)");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("locked/conf.txt"));

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("v2\n"));
            save(async, fx);

            assertEquals("v1\nv2\n", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    tr("status.savedInPlace", com.editora.config.PathDisplay.of(sftp.remotePath("locked/conf.txt"))),
                    echo(fx));
            assertEquals(List.of(), backups(fx), "the previous bytes are not kept once the write is done");
        }
    }

    @Test
    void aRemoteInPlaceWriteThatDiesHalfWayLeavesThePreviousBytesInALocalBackup(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Path locked = Files.createDirectories(sftp.serverPath("locked"));
            Path file = Files.writeString(locked.resolve("conf.txt"), "previous\n");
            assumePosix(file);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
            async.onClose(() -> Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x")));
            Assumptions.assumeFalse(Files.isWritable(locked), "running as a user the mode does not bind (root)");
            Path backups = dir.resolve("backups");
            // The connection goes away after the server truncated the file: neither the new bytes nor the
            // old ones can be written any more.
            AtomicFileWrite.FileOperations dying = new ForwardingFileOperations() {
                @Override
                public void overwrite(Path path, byte[] bytes) throws java.io.IOException {
                    Files.write(file, new byte[0]);
                    throw new java.io.IOException("connection reset");
                }
            };

            java.io.IOException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> AtomicFileWrite.writeDocument(
                            sftp.remotePath("locked/conf.txt"), "new\n".getBytes(), () -> true, backups, dying));

            List<Path> kept;
            try (Stream<Path> children = Files.list(backups)) {
                kept = children.toList();
            }
            assertEquals(1, kept.size(), "the previous bytes are still somewhere: " + failure.getMessage());
            assertEquals("previous\n", Files.readString(kept.getFirst()));
            assertTrue(Vfs.isLocal(kept.getFirst()), "on this machine, where a dead connection cannot hide them");
            assertTrue(failure.getMessage().contains(kept.getFirst().toString()), failure.getMessage());

            // A refusal before anything was written is not a torn file: no backup is left, nothing is restored.
            Files.writeString(file, "previous\n");
            AtomicFileWrite.FileOperations refused = new ForwardingFileOperations() {
                @Override
                public void overwrite(Path path, byte[] bytes) throws java.io.IOException {
                    throw new java.nio.file.AccessDeniedException(path.toString());
                }
            };
            Path unused = dir.resolve("unused-backups");
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> AtomicFileWrite.writeDocument(
                            sftp.remotePath("locked/conf.txt"), "new\n".getBytes(), () -> true, unused, refused));
            assertEquals("previous\n", Files.readString(file));
            try (Stream<Path> children = Files.list(unused)) {
                assertEquals(List.of(), children.toList());
            }
        }
    }

    /**
     * Where the link count comes from differs by protocol: a version-3 directory listing (OpenSSH), the
     * version-6 link-count attribute where the server sends it, and — for a server that speaks 4 to 6
     * without that attribute but also offers 3, as MINA's does — a version-3 listing on a second channel.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"protocol 3 listing", "protocol 6 attribute", "protocol 6 with a protocol 3 channel"})
    void savingARemoteFileWithAnotherHardLinkKeepsBothNamesOnTheSameContent(String server, @TempDir Path dir)
            throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Path first = Files.writeString(sftp.serverPath("h1.txt"), "hard\n");
            Path second = sftp.serverPath("h2.txt");
            Path alone = Files.writeString(sftp.serverPath("alone.txt"), "one name\n");
            try {
                Files.createLink(second, first);
            } catch (UnsupportedOperationException | java.io.IOException noHardLinks) {
                Assumptions.abort("no hard links here: " + noHardLinks);
            }
            Object aloneKey = Files.readAttributes(alone, java.nio.file.attribute.BasicFileAttributes.class)
                    .fileKey();
            switch (server) {
                case "protocol 3 listing" -> sftp.speakProtocolVersion(3);
                case "protocol 6 attribute" -> {
                    sftp.reportLinkCounts();
                    sftp.speakProtocolVersion(6); // 6 only: no protocol-3 channel to fall back to
                }
                default -> assertEquals(6, sftpVersion(sftp), "the unpinned server and client agree on 6");
            }
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("h1.txt"));

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("more\n"));
            save(async, fx);

            assertEquals("hard\nmore\n", Files.readString(first));
            assertEquals("hard\nmore\n", Files.readString(second), "the other name still names the same file");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // A file with one name is still replaced, not written in place: the count was read, not assumed.
            EditorBuffer single = openRemote(async, fx, sftp.remotePath("alone.txt"));
            FxTestSupport.runOnFx(() -> single.getArea().appendText("more\n"));
            save(async, fx);
            assertEquals("one name\nmore\n", Files.readString(alone));
            if (aloneKey != null) {
                assertFalse(
                        aloneKey.equals(Files.readAttributes(alone, java.nio.file.attribute.BasicFileAttributes.class)
                                .fileKey()),
                        "a file with a single name is replaced by a new one");
            }
        }
    }

    private static int sftpVersion(EmbeddedSftpFixture sftp) {
        return ((org.apache.sshd.sftp.client.fs.SftpFileSystem)
                        sftp.remotePath("x").getFileSystem())
                .getVersion();
    }

    @Test
    void unsavedRemoteEditsCanStillBeKeptOnceTheConnectionIsGone(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("notes.txt"), "server\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("notes.txt"));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("only copy\n"));

            sftp.disconnect();
            save(async, fx);

            // Says what happened and what to do, not which exception the first request raised.
            assertEquals(
                    tr(
                            "status.failedSave",
                            tr(
                                    "status.remote.connectionLost",
                                    sftp.connection().id(),
                                    tr("command.remote.connect"),
                                    tr("command.file.saveAs"))),
                    echo(fx));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // Save As from the palette: a local copy, where it used to say remote files are not supported.
            Path copy = dir.resolve("kept").resolve("notes-copy.txt");
            Files.createDirectories(copy.getParent());
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("file.saveAs"));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller, "stage");
                TextField target = (TextField) stage.getScene().getFocusOwner();
                assertTrue(target.getText().endsWith("notes.txt"), "the remote name is suggested: " + target.getText());
                assertTrue(Vfs.isLocal(Path.of(target.getText()).getParent()));
                target.setText(copy.toString());
                javafx.event.Event.fireEvent(
                        target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
            settle(async, workflows);

            assertEquals("server\nonly copy\n", Files.readString(copy));
            assertEquals(copy, FxTestSupport.callOnFx(buffer::getPath));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("server\n", Files.readString(sftp.serverPath("notes.txt")));
        }
    }

    @Test
    void disconnectAsksBeforeStrandingUnsavedRemoteTabs(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("a.txt"), "a\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            RemoteCoordinator remote = remoteCoordinator(fx, sftp);
            String authority = sftp.connection().id();
            FxTestSupport.runOnFx(() -> mount(remote, sftp, sftp.remotePath(".")));
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("a.txt"));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("unsaved\n"));

            AtomicReference<String> asked = new AtomicReference<>();
            CountDownLatch declined = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, asked);
            FxTestSupport.runOnFx(remote::disconnect);
            async.await(declined, "the unsaved-remote-tabs question");
            async.awaitFx();
            assertTrue(asked.get().contains("a.txt"), "the question names the file: " + asked.get());
            assertTrue(sftp.fileSystems().isConnected(authority), "Cancel keeps the connection");
            assertTrue(FxTestSupport.callOnFx(remote::isMounted));

            // Still connected, so the edit can be saved; a clean tab needs no question.
            save(async, fx);
            assertEquals("a\nunsaved\n", Files.readString(sftp.serverPath("a.txt")));
            FxTestSupport.runOnFx(remote::disconnect);
            async.awaitFx();
            assertEquals(0, openDialogs());
            assertFalse(sftp.fileSystems().isConnected(authority));
            assertFalse(FxTestSupport.callOnFx(remote::isMounted));
            assertEquals(tr("status.remote.disconnected"), echo(fx));
        }
    }

    @Test
    void aStalledRemoteSaveDoesNotHoldBackALocalSave(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Path slow = Files.writeString(sftp.serverPath("slow.txt"), "slow\n");
            Path local = Files.writeString(dir.resolve("local.txt"), "local\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer remoteBuffer = openRemote(async, fx, sftp.remotePath("slow.txt"));
            EditorBuffer localBuffer = openRemote(async, fx, local);
            StageBlock block = sftp.holdAfterNextStagedWrite();
            async.onClose(block.release()::countDown);

            FxTestSupport.runOnFx(() -> {
                remoteBuffer.getArea().appendText("edit\n");
                assertTrue(workflows.save(remoteBuffer));
            });
            async.await(block.staged(), "the remote save to stall on the server");

            FxTestSupport.runOnFx(() -> {
                localBuffer.getArea().appendText("edit\n");
                assertTrue(workflows.save(localBuffer));
            });
            ExecutorService localWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
            async.awaitWorker(localWorker); // times out when the local write waits behind the remote one
            async.awaitFx();
            assertEquals("local\nedit\n", Files.readString(local), "written while the server is still stalled");
            assertEquals("slow\n", Files.readString(slow));
            assertFalse(FxTestSupport.callOnFx(localBuffer::isDirty));

            block.release().countDown();
            settle(async, workflows);
            assertEquals("slow\nedit\n", Files.readString(slow));
            assertFalse(FxTestSupport.callOnFx(remoteBuffer::isDirty));
        }
    }

    @ParameterizedTest(name = "after an app restart: {0}")
    @ValueSource(booleans = {false, true})
    void aStagingFileLeftByADroppedConnectionIsRemovedByTheNextSaveToThatServer(boolean restarted, @TempDir Path dir)
            throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("drop.txt"), "server\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("drop.txt"));
            StageBlock block = sftp.holdAfterNextStagedWrite();
            async.onClose(block.release()::countDown);

            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("edit\n");
                assertTrue(workflows.save(buffer));
            });
            async.await(block.staged(), "remote save staging");
            CountDownLatch disconnected = new CountDownLatch(1);
            async.start("drop-the-connection-mid-save", () -> {
                try {
                    sftp.disconnect();
                } finally {
                    disconnected.countDown();
                }
            });
            TimeUnit.MILLISECONDS.sleep(200); // let the close begin while the staged write is still held
            block.release().countDown();
            async.await(disconnected, "the connection to close");
            settle(async, workflows);

            assertEquals("server\n", Files.readString(sftp.serverPath("drop.txt")));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            Assumptions.assumeTrue(
                    hasStagingFile(sftp.serverPath(".")), "the close won the race before anything was staged");
            assertTrue(echo(fx).contains(sftp.connection().id()), "the failure names the lost connection: " + echo(fx));
            Path ledger = fx.configDir.resolve("save-backups").resolve("remote-staging-leftovers.txt");
            assertTrue(Files.exists(ledger), "the leftover is written down");
            if (restarted) {
                // What the next run starts with: nothing in memory, only the list beside the save backups.
                java.lang.reflect.Method forget =
                        Class.forName("com.editora.io.SftpFiles").getDeclaredMethod("forgetOrphansInMemory");
                forget.setAccessible(true);
                forget.invoke(null);
            }

            sftp.reconnect();
            RemoteCoordinator remote = remoteCoordinator(fx, sftp);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    remote,
                    "rebindOpenBuffers",
                    new Class<?>[] {String.class},
                    sftp.connection().id()));
            save(async, fx);

            assertEquals("server\nedit\n", Files.readString(sftp.serverPath("drop.txt")));
            assertFalse(hasStagingFile(sftp.serverPath(".")), "the earlier save's staging file is gone too");
            assertFalse(Files.exists(ledger), "and crossed off the list");
        }
    }

    @Test
    void connectingToASecondHostClosesTheFirstUnlessItsTabsStillNeedIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture first = async.own(EmbeddedSftpFixture.start(Files.createDirectories(dir.resolve("a"))));
            EmbeddedSftpFixture second =
                    async.own(EmbeddedSftpFixture.start(Files.createDirectories(dir.resolve("b"))));
            EmbeddedSftpFixture third = async.own(EmbeddedSftpFixture.start(Files.createDirectories(dir.resolve("c"))));
            Files.writeString(second.serverPath("kept.txt"), "kept\n");
            RemoteFileSystems engine = first.fileSystems(); // one window, one engine, three hosts
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            RemoteCoordinator remote = remoteCoordinator(fx, first);
            String a = first.connection().id();
            String b = second.connection().id();
            String c = third.connection().id();

            FxTestSupport.runOnFx(() -> mount(remote, first, first.remotePath(".")));
            Path rootB = connect(async, engine, second);
            FxTestSupport.runOnFx(() -> mount(remote, second, rootB));
            assertFalse(engine.isConnected(a), "nothing shows the first host any more: its session is closed");
            assertTrue(engine.isConnected(b));

            // A host with a tab open stays up when the Project tree moves on, so the tab can still be saved.
            EditorBuffer buffer = openRemote(async, fx, rootB.resolve("kept.txt"));
            Path rootC = connect(async, engine, third);
            FxTestSupport.runOnFx(() -> mount(remote, third, rootC));
            assertTrue(engine.isConnected(b));
            assertTrue(engine.isConnected(c));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            save(async, fx);
            assertEquals("kept\nedited\n", Files.readString(second.serverPath("kept.txt")));

            // Disconnect closes every connection of the window, not only the mounted one.
            FxTestSupport.runOnFx(remote::disconnect);
            async.awaitFx();
            assertEquals(java.util.Set.of(), engine.connectedAuthorities());
            FxTestSupport.runOnFx(remote::disconnect);
            assertEquals(tr("status.remote.notConnected"), echo(fx));
        }
    }

    @Test
    void aRemoteFileOfUnknownEncodingIsNeverReencoded(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            byte[] original = HexFormat.of().parseHex("93fa967b8cea81400d0a"); // 日本語 + ideographic space, Shift-JIS
            Path file = Files.write(sftp.serverPath("sjis.txt"), original);
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("sjis.txt"));
            assertTrue(FxTestSupport.callOnFx(buffer::isCharsetAssumed), "decoded through a stand-in charset");

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "ok\n日"));
            save(async, fx);

            assertEquals(hex(original), hex(Files.readAllBytes(file)), "not one byte may be re-encoded");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the edit is still unsaved");
            assertEquals(tr("status.save.cannotEncodeAssumed", "sjis.txt", "ISO-8859-1", "日", "2"), echo(fx));

            // Auto-save and the synchronous save used by close/run pass through the same refusal.
            FxTestSupport.runOnFx(() -> workflows.autoSaveBuffer(buffer));
            settle(async, workflows);
            assertFalse(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(buffer)));
            assertEquals(hex(original), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
            assertFalse(hasStagingFile(sftp.serverPath(".")));

            // Taking the character out again makes the file savable, and only the edit reaches the server.
            FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(3, 4));
            save(async, fx);
            assertEquals("6f6b0d0a" + hex(original), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** The production filesystem boundary, for a test to override the one step it makes fail. */
    private static class ForwardingFileOperations implements AtomicFileWrite.FileOperations {
        private final AtomicFileWrite.FileOperations files = AtomicFileWrite.systemFileOperations();

        @Override
        public boolean isDirectory(Path path) {
            return files.isDirectory(path);
        }

        @Override
        public boolean exists(Path path, java.nio.file.LinkOption... options) {
            return files.exists(path, options);
        }

        @Override
        public void createDirectories(Path path) throws java.io.IOException {
            files.createDirectories(path);
        }

        @Override
        public Path createTempFile(
                Path directory, String prefix, String suffix, java.nio.file.attribute.FileAttribute<?>... attributes)
                throws java.io.IOException {
            return files.createTempFile(directory, prefix, suffix, attributes);
        }

        @Override
        public Path createTempFile(String prefix, String suffix, java.nio.file.attribute.FileAttribute<?>... attributes)
                throws java.io.IOException {
            return files.createTempFile(prefix, suffix, attributes);
        }

        @Override
        public void write(Path path, byte[] bytes) throws java.io.IOException {
            files.write(path, bytes);
        }

        @Override
        public void force(Path path) throws java.io.IOException {
            files.force(path);
        }

        @Override
        public void writeNew(Path path, byte[] bytes) throws java.io.IOException {
            files.writeNew(path, bytes);
        }

        @Override
        public void overwrite(Path path, byte[] bytes) throws java.io.IOException {
            files.overwrite(path, bytes);
        }

        @Override
        public void move(Path source, Path target, java.nio.file.CopyOption... options) throws java.io.IOException {
            files.move(source, target, options);
        }

        @Override
        public byte[] readAllBytes(Path path) throws java.io.IOException {
            return files.readAllBytes(path);
        }

        @Override
        public boolean deleteIfExists(Path path) throws java.io.IOException {
            return files.deleteIfExists(path);
        }
    }

    private static void assumePosix(Path file) {
        Assumptions.assumeTrue(
                file.getFileSystem().supportedFileAttributeViews().contains("posix"));
    }

    private static RemoteCoordinator remoteCoordinator(FxWindowFixture fx, EmbeddedSftpFixture sftp) {
        CoordinatorHost host = FxTestSupport.field(fx.controller, "coordinatorHost");
        RemoteCoordinator.Ops ops =
                (RemoteCoordinator.Ops) FxTestSupport.call(fx.controller, "remoteOps", new Class<?>[] {});
        return new RemoteCoordinator(host, ops, sftp.fileSystems());
    }

    /** What the connect form does once the connection is up. */
    private static void mount(RemoteCoordinator remote, EmbeddedSftpFixture sftp, Path root) {
        FxTestSupport.call(
                remote,
                "mount",
                new Class<?>[] {com.editora.vfs.RemoteConnection.class, Path.class},
                sftp.connection(),
                root);
    }

    /** Opens {@code server}'s connection on {@code engine}, as a second connect from the same window does. */
    private static Path connect(AsyncTestScope async, RemoteFileSystems engine, EmbeddedSftpFixture server)
            throws Exception {
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(server.connection(), "password".toCharArray(), connected -> {
            result.set(connected);
            done.countDown();
        });
        async.await(done, "the second SFTP connection");
        assertTrue(result.get().ok(), result.get().error());
        return result.get().root();
    }

    private static EditorBuffer openRemote(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(file);
            Tab selected = editorArea(fx).selectedTab();
            assertTrue(selected.getUserData() instanceof EditorBuffer, "a text file opens in an editor tab");
            EditorBuffer opening = (EditorBuffer) selected.getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "document load");
        async.awaitFx();
        return buffer;
    }

    /** {@code file.save} on the active tab, then every write worker and its FX acknowledgment. */
    private static void save(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        settle(async, workflows(fx));
    }

    private static void settle(AsyncTestScope async, FileWorkflowCoordinator workflows) throws Exception {
        Map<String, ExecutorService> remoteWorkers = FxTestSupport.field(workflows, "remoteSaveExecutors");
        for (ExecutorService worker : List.copyOf(remoteWorkers.values())) {
            async.awaitWorker(worker);
        }
        ExecutorService localWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(localWorker);
        async.awaitFx();
    }

    private static CountDownLatch pressNextDialog(
            AsyncTestScope async, ButtonBar.ButtonData buttonData, AtomicReference<String> content) throws Exception {
        FxTestSupport.runOnFx(() -> new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane) {
                        content.compareAndSet(null, pane.getContentText());
                        stop();
                    }
                }
            }
        }.start());
        return SaveGuardsFxTest.pressNextDialog(async, buttonData);
    }

    private static List<String> backups(FxWindowFixture fx) throws Exception {
        Path backups = fx.shared.getConfigDir().resolve("save-backups");
        if (!Files.isDirectory(backups)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(backups)) {
            return children.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static boolean hasStagingFile(Path directory) throws Exception {
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(path -> path.getFileName().toString().endsWith(".editora-tmp"));
        }
    }

    private static int openDialogs() {
        return (int) Window.getWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().getRoot() instanceof DialogPane)
                .count();
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static EditorArea editorArea(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "editorArea");
    }

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }
}
