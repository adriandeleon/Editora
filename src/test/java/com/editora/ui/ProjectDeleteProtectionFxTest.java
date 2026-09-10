package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class ProjectDeleteProtectionFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    @Test
    void cancellingTheDirtyBufferDecisionLeavesFileAndBufferUntouched() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("cancel-delete.txt"), "disk baseline");
            EditorBuffer buffer = open(fx.controller, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("unsaved work"));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.cancel"), promptSeen);

            async.await(promptSeen, "dirty delete cancellation prompt");
            ProjectPanel.DeleteResult outcome = async.await(result);
            assertFalse(outcome.prepared());
            assertEquals(0, outcome.deleted());
            assertEquals("disk baseline", Files.readString(file));
            FxTestSupport.runOnFx(() -> {
                assertEquals("unsaved work", buffer.getContent());
                assertTrue(buffer.isDirty());
                assertFalse(buffer.isDisposed());
            });
        }
    }

    @Test
    void aFailedSaveAbortsDeletionAndKeepsTheDirtyCopyOpen() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("failed-save-delete.txt"), "only disk copy");
            EditorBuffer buffer = open(fx.controller, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            workflows.setDocumentWriter((target, bytes, current) -> {
                throw new IOException("simulated failed save before delete");
            });
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("last dirty copy"));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.save"), promptSeen);

            async.await(promptSeen, "dirty delete save prompt");
            ProjectPanel.DeleteResult outcome = async.await(result);
            assertFalse(outcome.prepared());
            assertEquals("only disk copy", Files.readString(file));
            FxTestSupport.runOnFx(() -> {
                assertEquals("last dirty copy", buffer.getContent());
                assertTrue(buffer.isDirty(), "failed save must not make the buffer clean");
                assertFalse(buffer.isDisposed(), "failed save must not let delete close the last dirty copy");
            });
        }
    }

    @Test
    void savingBeforeDeletePublishesTheSavedTextDurablyThenClosesTheBuffer() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            enableHistory(fx);
            Path file = Files.writeString(dir.resolve("save-delete.txt"), "disk baseline");
            EditorBuffer buffer = open(fx.controller, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("saved before delete"));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.save"), promptSeen);

            async.await(promptSeen, "dirty delete save prompt");
            ProjectPanel.DeleteResult outcome = async.await(result);
            assertTrue(outcome.prepared());
            assertEquals(1, outcome.deleted());
            assertFalse(Files.exists(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDisposed));
            assertEquals("saved before delete", deleteHistoryBody(fx, file));
        }
    }

    @Test
    void explicitDiscardDeletesButPreservesTheLastDiskRevisionInHistory() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            enableHistory(fx);
            Path file = Files.writeString(dir.resolve("discard-delete.txt"), "recoverable disk baseline");
            EditorBuffer buffer = open(fx.controller, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("explicitly discarded edit"));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.discard"), promptSeen);

            async.await(promptSeen, "dirty delete discard prompt");
            ProjectPanel.DeleteResult outcome = async.await(result);
            assertTrue(outcome.prepared());
            assertEquals(1, outcome.deleted());
            assertFalse(Files.exists(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDisposed));
            assertEquals("recoverable disk baseline", deleteHistoryBody(fx, file));
        }
    }

    @Test
    void anEditMadeWhileHistoryPublishesAbortsDeletion() throws Exception {
        CountDownLatch publicationClaimed = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            async.onClose(releasePublication::countDown);
            enableHistory(fx);
            Path file = Files.writeString(dir.resolve("edit-during-delete.txt"), "disk baseline");
            EditorBuffer buffer = open(fx.controller, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("discard decision text"));
            Object writer = FxTestSupport.field(fx.shared, "writer");
            set(writer, "afterBatchClaimedForTest", (Runnable) () -> {
                publicationClaimed.countDown();
                try {
                    if (!releasePublication.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out holding history publication");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.discard"), promptSeen);
            async.await(promptSeen, "dirty delete discard prompt");
            async.await(publicationClaimed, "durable history publication to be claimed");
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText(" + later edit"));
            releasePublication.countDown();

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertFalse(outcome.prepared());
            assertEquals("disk baseline", Files.readString(file));
            FxTestSupport.runOnFx(() -> {
                assertEquals("discard decision text + later edit", buffer.getContent());
                assertTrue(buffer.isDirty());
                assertFalse(buffer.isDisposed());
            });
        }
    }

    @Test
    void aDiscardedPendingSaveCannotRecreateTheDeletedPath() throws Exception {
        CountDownLatch writerClaimed = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            async.onClose(releaseWriter::countDown);
            enableHistory(fx);
            Path file = Files.writeString(dir.resolve("pending-save-delete.txt"), "recoverable disk baseline");
            EditorBuffer buffer = open(fx.controller, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            workflows.beforeDocumentWriteForTest = () -> {
                writerClaimed.countDown();
                try {
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out holding the pending delete save");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("obsolete pending save");
                workflows.autoSaveBuffer(buffer);
            });
            async.await(writerClaimed, "pending save to claim its write ticket");
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.discard"), promptSeen);
            async.await(promptSeen, "pending save discard prompt");

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertTrue(outcome.prepared());
            assertEquals(1, outcome.deleted());
            assertFalse(Files.exists(file));
            assertEquals("recoverable disk baseline", deleteHistoryBody(fx, file));
            releaseWriter.countDown();
            async.awaitWorker(worker);
            async.awaitFx();
            assertFalse(Files.exists(file), "the invalidated pending save must not recreate the deleted path");
        }
    }

    @Test
    void oneFailedHistoryPublicationLeavesTheEntireSelectionUntouched() throws Exception {
        Path index = null;
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            enableHistory(fx);
            assertTrue(fx.shared.flushWrites());
            Path first = Files.writeString(dir.resolve("history-failure-a.txt"), "first");
            Path second = Files.writeString(dir.resolve("history-failure-b.txt"), "second");
            index = fx.shared.getHistoryFile();
            Files.createDirectories(index);
            Files.writeString(index.resolve("block"), "prevent atomic replacement");

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    FxTestSupport.callOnFx(() -> panel(fx.controller).deleteConfirmed(List.of(first, second)));

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertFalse(outcome.prepared());
            assertEquals(0, outcome.deleted());
            assertEquals("first", Files.readString(first));
            assertEquals("second", Files.readString(second));

            Files.delete(index.resolve("block"));
            Files.delete(index);
            index = null;
        } finally {
            if (index != null && Files.isDirectory(index)) {
                Files.deleteIfExists(index.resolve("block"));
                Files.deleteIfExists(index);
            }
        }
    }

    @Test
    void aFailedMultiFileDeleteStillLeavesDurableRecoveryForEveryFile() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            enableHistory(fx);
            Path first = Files.writeString(dir.resolve("partial-delete-a.txt"), "first recovery body");
            Path second = Files.writeString(dir.resolve("partial-delete-b.txt"), "second recovery body");
            ProjectPanel panel = panel(fx.controller);
            CountDownLatch errorSeen = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> panel.setDeleteOperations(new ProjectPanel.DeleteOperations() {
                @Override
                public byte[] readAllBytes(Path file) throws IOException {
                    return Files.readAllBytes(file);
                }

                @Override
                public void delete(Path file) throws IOException {
                    if (file.equals(second)) {
                        Platform.runLater(() -> dismissOkAlert(errorSeen));
                        throw new IOException("simulated second-file delete failure");
                    }
                    Files.delete(file);
                }
            }));

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    FxTestSupport.callOnFx(() -> panel.deleteConfirmed(List.of(first, second)));

            ProjectPanel.DeleteResult outcome = async.await(result);
            async.await(errorSeen, "failed delete error dialog");
            assertTrue(outcome.prepared());
            assertEquals(1, outcome.deleted());
            assertEquals(List.of(second), outcome.failed());
            assertFalse(Files.exists(first));
            assertEquals("second recovery body", Files.readString(second));
            assertEquals("first recovery body", deleteHistoryBody(fx, first));
            assertEquals("second recovery body", deleteHistoryBody(fx, second));
        }
    }

    @Test
    void aChangedPreimageAbortsTheWholeSelectionBeforeTheFirstDelete() throws Exception {
        Path first = Files.writeString(dir.resolve("changed-preimage-a.txt"), "captured first");
        Path second = Files.writeString(dir.resolve("changed-preimage-b.txt"), "captured second");
        AtomicReference<Consumer<ProjectPanel.DeleteApproval>> approval = new AtomicReference<>();
        try (AsyncTestScope async = new AsyncTestScope()) {
            ProjectPanel panel = FxTestSupport.callOnFx(() -> {
                ProjectPanel value = new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false);
                value.setDeletePreparation((files, completion) -> approval.set(completion));
                return value;
            });
            async.onClose(() -> FxTestSupport.runOnFx(panel::dispose));

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    FxTestSupport.callOnFx(() -> panel.deleteConfirmed(List.of(first, second)));
            Files.writeString(second, "changed after durable capture");
            FxTestSupport.runOnFx(() -> approval.get()
                    .accept(new ProjectPanel.DeleteApproval(
                            true,
                            Map.of(
                                    first, "captured first".getBytes(UTF_8),
                                    second, "captured second".getBytes(UTF_8)))));

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertFalse(outcome.prepared());
            assertEquals("captured first", Files.readString(first));
            assertEquals("changed after durable capture", Files.readString(second));
        }
    }

    private static CompletableFuture<ProjectPanel.DeleteResult> deleteWithChoice(
            ProjectPanel panel, List<Path> files, String buttonText, CountDownLatch promptSeen) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Platform.runLater(() -> dismissAlert(buttonText, promptSeen));
            return panel.deleteConfirmed(files);
        });
    }

    private static void dismissAlert(String buttonText, CountDownLatch seen) {
        for (Window window : Window.getWindows().stream().toList()) {
            if (!(window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> buttonText.equals(type.getText()))
                    .findFirst()
                    .ifPresent(type -> {
                        seen.countDown();
                        ((Button) pane.lookupButton(type)).fire();
                    });
        }
    }

    private static void dismissOkAlert(CountDownLatch seen) {
        for (Window window : Window.getWindows().stream().toList()) {
            if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane) {
                Button button = (Button) pane.lookupButton(ButtonType.OK);
                if (button != null) {
                    seen.countDown();
                    button.fire();
                }
            }
        }
    }

    private static void enableHistory(FxWindowFixture fx) throws Exception {
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setLocalHistory(true));
    }

    private static ProjectPanel panel(MainController controller) {
        return FxTestSupport.field(controller, "projectPanel");
    }

    private static EditorBuffer open(MainController controller, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static String deleteHistoryBody(FxWindowFixture fx, Path file) throws Exception {
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        List<HistoryRevision> revisions = config.getHistory().get(PathKeys.normalizedKey(file));
        assertNotNull(revisions, "the deleted file must remain indexed by its original path");
        HistoryRevision revision = revisions.stream()
                .filter(item -> HistoryRevision.REASON_DELETE.equals(item.reason()))
                .findFirst()
                .orElseThrow();
        HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        return blobs.get(revision.sha256());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
