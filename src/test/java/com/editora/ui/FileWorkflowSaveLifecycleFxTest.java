package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class FileWorkflowSaveLifecycleFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void failedSaveFromDirtyCloseKeepsTheLatestTextDirtyAndTheTabOpen(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("failed-close.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            CountDownLatch writeStarted = new CountDownLatch(1);
            CountDownLatch releaseWrite = new CountDownLatch(1);
            CountDownLatch promptSeen = new CountDownLatch(1);
            async.onClose(releaseWrite::countDown);

            workflows.setDocumentWriter((target, content, commit) -> {
                Path staged =
                        Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".editora-tmp");
                try {
                    Files.write(staged, Arrays.copyOf(content, Math.min(2, content.length)));
                    writeStarted.countDown();
                    if (!releaseWrite.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting to fail the staged write");
                    }
                    throw new IOException("staged write failed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("staged write interrupted", e);
                } finally {
                    Files.deleteIfExists(staged);
                }
            });

            async.start("edit-during-failed-save", () -> {
                async.await(writeStarted, "the document writer to start");
                Platform.runLater(() -> {
                    try {
                        buffer.replaceWholeDocument("snapshot plus later edit");
                    } finally {
                        releaseWrite.countDown();
                    }
                });
            });

            Tab tab = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<EditorArea>field(fx.controller, "editorArea").selectedTab());
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("snapshot");
                Platform.runLater(() -> dismissAlert(tr("dialog.save"), promptSeen));
                FxTestSupport.call(fx.controller, "closeTab", new Class<?>[] {Tab.class}, tab);
            });
            async.await(promptSeen, "the dirty-close Save choice");
            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals("A", Files.readString(file), "the original disk copy must survive");
            assertEquals("snapshot plus later edit", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "a failed save must not acknowledge any text");
            assertTrue(
                    FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                            .tabs()
                            .contains(tab)),
                    "the failed Save choice must not close the dirty tab");
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
            assertEquals(tr("status.failedSave", "staged write failed"), FxTestSupport.callOnFx(() -> {
                StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
                return FxTestSupport.<Label>field(status, "echo").getText();
            }));
            try (var entries = Files.list(dir)) {
                assertEquals(1, entries.count(), "the failed staging file must not become a recovery hazard");
            }
        }
    }

    @Test
    void committedSaveIsUnresolvedUntilItsFxAcknowledgment(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path file = Files.writeString(dir.resolve("close.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");

            FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("BBBB");
                workflows.save(buffer);
                barrier(worker); // disk committed; completion is queued behind this FX action
                assertEquals("BBBB", Files.readString(file));
                buffer.replaceWholeDocument("A");
                assertFalse(buffer.isDirty(), "the previous baseline is still visible until acknowledgment");
                assertTrue(workflows.hasPendingSave(buffer), "close must treat the unresolved commit as unsafe");

                CountDownLatch promptSeen = new CountDownLatch(1);
                Platform.runLater(() -> dismissAlert(tr("dialog.discard"), promptSeen));
                boolean allowed = (Boolean) FxTestSupport.call(
                        fx.controller, "confirmCloseIfDirty", new Class<?>[] {EditorBuffer.class}, buffer);
                assertTrue(allowed, "the explicitly chosen Discard permits close");
                assertTrue(promptSeen.await(5, TimeUnit.SECONDS), "the unresolved save must open the warning");
                return null;
            });

            FxTestSupport.runOnFx(() -> {}); // apply the queued durable baseline
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "A differs from the committed BBBB baseline");
        } finally {
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    @Test
    void supersedingAutosaveRecognizesThePrecedingApplicationCommit(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path file = Files.writeString(dir.resolve("autosave.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");

            FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("BBBB");
                workflows.save(buffer);
                barrier(worker);
                buffer.replaceWholeDocument("CCCCCC");
                workflows.autoSaveBuffer(buffer);
                return null;
            });
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});

            assertEquals("CCCCCC", Files.readString(file));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(6, FxTestSupport.callOnFx(() -> buffer.diskSnapshot().size()));
        } finally {
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    @Test
    void saveAsCommitIsAlsoPendingCloseStateUntilAcknowledged(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path target = dir.resolve("saved-as.txt");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setContent("A");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, created, true);
                return created;
            });
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");

            FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("BBBB");
                assertTrue(workflows.applySaveAsTarget(buffer, target));
                barrier(worker);
                buffer.replaceWholeDocument("A");
                assertTrue(workflows.hasPendingSave(buffer));
                return null;
            });
            FxTestSupport.runOnFx(() -> {});
            assertEquals("BBBB", Files.readString(target));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        } finally {
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    @Test
    void synchronousSaveContractKeepsTheFxThreadResponsive(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch heartbeat = new CountDownLatch(1);
        try {
            Path file = Files.writeString(dir.resolve("run-save.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            workflows.beforeDocumentWriteForTest = () -> {
                writeStarted.countDown();
                try {
                    releaseWrite.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            EditorBuffer editable = buffer;
            FxTestSupport.runOnFx(() -> editable.replaceWholeDocument("saved"));

            Thread.ofVirtual().start(() -> {
                try {
                    if (writeStarted.await(5, TimeUnit.SECONDS)) {
                        Platform.runLater(heartbeat::countDown);
                        heartbeat.await(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseWrite.countDown();
                }
            });

            EditorBuffer saved = editable;
            assertTrue(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(saved)));
            assertTrue(heartbeat.await(1, TimeUnit.SECONDS), "nested completion must keep processing FX work");
            assertEquals("saved", Files.readString(file));
        } finally {
            releaseWrite.countDown();
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    @Test
    void applicationCommitIdentityDoesNotMaskSameMetadataExternalContent(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            Path file = Files.writeString(dir.resolve("external.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("BBBB");
                workflows.save(buffer);
            });
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});
            long applicationModified =
                    FxTestSupport.callOnFx(() -> buffer.diskSnapshot().modifiedMillis());

            Files.writeString(file, "CCCC");
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(applicationModified));
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("DDDDDD");
                workflows.autoSaveBuffer(buffer);
            });
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});

            assertEquals("CCCC", Files.readString(file));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        } finally {
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent("A");
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static void barrier(ExecutorService executor) throws Exception {
        executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
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
}
