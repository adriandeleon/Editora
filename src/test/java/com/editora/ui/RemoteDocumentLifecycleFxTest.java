package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.EmbeddedSftpFixture.Fault;
import com.editora.vfs.EmbeddedSftpFixture.StageBlock;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Application-level remote document tests over a real, chrooted embedded SFTP server. */
@Tag("fx")
class RemoteDocumentLifecycleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openEditSaveAndReopenUsesExactRemoteBytes(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("document.txt"), "server baseline\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path remote = sftp.remotePath("document.txt");
            EditorBuffer buffer = openRemote(async, fx, remote);

            assertEquals("server baseline\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertTrue(Vfs.isRemote(FxTestSupport.callOnFx(buffer::getPath)));

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("saved through Editora €\n");
                assertTrue(workflows.save(buffer));
            });
            awaitSave(async, workflows);

            assertEquals("saved through Editora €\n", Files.readString(sftp.serverPath("document.txt")));
            assertEquals("saved through Editora €\n", Files.readString(remote));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            FxTestSupport.runOnFx(() -> editorArea(fx).remove(tabFor(fx, buffer)));
            EditorBuffer reopened = openRemote(async, fx, remote);
            assertEquals("saved through Editora €\n", FxTestSupport.callOnFx(reopened::getContent));
            assertFalse(FxTestSupport.callOnFx(reopened::isDirty));
        }
    }

    @ParameterizedTest(name = "remote {0} failure preserves original and dirty editor copy")
    @EnumSource(
            value = Fault.class,
            names = {"CREATE", "WRITE", "MOVE"})
    void failedSafeReplacementCannotLoseTheRemoteOrEditorCopy(Fault fault, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("protected.txt"), "recoverable server baseline\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("protected.txt"));
            sftp.fault(fault);
            CountDownLatch failed = watchStatus(fx, text -> text.startsWith(tr("status.failedSave", "")));

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("only recoverable editor copy\n");
                assertTrue(workflows.save(buffer));
            });
            awaitSave(async, workflows);
            async.await(failed, "remote " + fault + " failure feedback");

            assertEquals("recoverable server baseline\n", Files.readString(sftp.serverPath("protected.txt")));
            assertEquals("only recoverable editor copy\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(hasStagingFile(sftp.serverPath(".")), "failed replacement cleans its remote staging file");
            assertFalse(cancelClose(async, fx, buffer), "Cancel must keep the only dirty copy open");
            assertEquals("only recoverable editor copy\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void reconnectInvalidatesAStagedOldFilesystemSaveAndRetainsDirtyText(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("reconnect.txt"), "server baseline\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = openRemote(async, fx, sftp.remotePath("reconnect.txt"));
            Path oldPath = FxTestSupport.callOnFx(buffer::getPath);
            String stored = Vfs.toStorableString(oldPath);
            StageBlock block = sftp.holdAfterNextStagedWrite();
            async.onClose(block.release()::countDown);

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("dirty copy across reconnect\n");
                assertTrue(workflows.save(buffer));
            });
            async.await(block.staged(), "remote save staging");
            sftp.fault(Fault.MOVE);
            CountDownLatch disconnectStarted = new CountDownLatch(1);
            CountDownLatch disconnected = new CountDownLatch(1);
            async.start("disconnect-staged-sftp-save", () -> {
                disconnectStarted.countDown();
                try {
                    sftp.disconnect();
                } finally {
                    disconnected.countDown();
                }
            });
            async.await(disconnectStarted, "disconnect to start");
            block.release().countDown();
            async.await(disconnected, "old SFTP filesystem disconnect");
            awaitSave(async, workflows);

            assertEquals("server baseline\n", Files.readString(sftp.serverPath("reconnect.txt")));
            assertEquals("dirty copy across reconnect\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            sftp.fault(Fault.NONE);
            sftp.reconnect();
            RemoteCoordinator coordinator = injectedRemoteCoordinator(fx, sftp);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    coordinator,
                    "rebindOpenBuffers",
                    new Class<?>[] {String.class},
                    sftp.connection().id()));
            Path rebound = FxTestSupport.callOnFx(buffer::getPath);

            assertNotEquals(oldPath.getFileSystem(), rebound.getFileSystem());
            assertEquals(stored, Vfs.toStorableString(rebound));
            assertEquals("dirty copy across reconnect\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            FxTestSupport.runOnFx(() -> assertTrue(workflows.save(buffer)));
            awaitSave(async, workflows);
            assertEquals("dirty copy across reconnect\n", Files.readString(sftp.serverPath("reconnect.txt")));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    private static RemoteCoordinator injectedRemoteCoordinator(FxWindowFixture fx, EmbeddedSftpFixture sftp) {
        CoordinatorHost host = FxTestSupport.field(fx.controller, "coordinatorHost");
        RemoteCoordinator.Ops ops =
                (RemoteCoordinator.Ops) FxTestSupport.call(fx.controller, "remoteOps", new Class<?>[] {});
        return new RemoteCoordinator(host, ops, sftp.fileSystems());
    }

    private static EditorBuffer openRemote(AsyncTestScope async, FxWindowFixture fx, Path remote) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(remote);
            Tab selected = editorArea(fx).selectedTab();
            assertTrue(selected.getUserData() instanceof EditorBuffer, "remote text file opens in an editor tab");
            EditorBuffer opening = (EditorBuffer) selected.getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "remote document load");
        async.awaitFx();
        return buffer;
    }

    private static void awaitSave(AsyncTestScope async, FileWorkflowCoordinator workflows) throws Exception {
        ExecutorService saveWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(saveWorker);
        async.awaitFx();
    }

    private static boolean cancelClose(AsyncTestScope async, FxWindowFixture fx, EditorBuffer buffer) throws Exception {
        CountDownLatch prompted = new CountDownLatch(1);
        boolean allowed = FxTestSupport.callOnFx(() -> {
            Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, prompted));
            return (Boolean) FxTestSupport.call(
                    fx.controller, "confirmCloseIfDirty", new Class<?>[] {EditorBuffer.class}, buffer);
        });
        async.await(prompted, "dirty-close prompt after failed remote save");
        return allowed;
    }

    private static void pressDialog(ButtonBar.ButtonData buttonData, CountDownLatch pressed) {
        for (Window window : Window.getWindows().stream().toList()) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .ifPresent(type -> {
                        pressed.countDown();
                        ((Button) pane.lookupButton(type)).fire();
                    });
        }
    }

    private static CountDownLatch watchStatus(FxWindowFixture fx, Predicate<String> expected) throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label echo = FxTestSupport.field(statusBar, "echo");
            ChangeListener<String> listener = (observable, before, after) -> {
                if (expected.test(after)) {
                    seen.countDown();
                }
            };
            echo.textProperty().addListener(listener);
        });
        return seen;
    }

    private static boolean hasStagingFile(Path directory) throws Exception {
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(path -> path.getFileName().toString().endsWith(".editora-tmp"));
        }
    }

    private static Tab tabFor(FxWindowFixture fx, EditorBuffer buffer) {
        return editorArea(fx).tabs().stream()
                .filter(tab -> tab.getUserData() == buffer)
                .findFirst()
                .orElseThrow();
    }

    private static EditorArea editorArea(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "editorArea");
    }

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }
}
