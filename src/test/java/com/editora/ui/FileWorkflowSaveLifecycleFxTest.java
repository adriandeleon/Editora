package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.diff.DiffEngine;
import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import org.fxmisc.richtext.CodeArea;
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
    void successfulSaveOfOlderSnapshotDoesNotAuthorizeClose(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("successful-close-race.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            CountDownLatch promptSeen = new CountDownLatch(1);
            workflows.setDocumentWriter((target, bytes, commit) -> {
                Platform.runLater(() -> buffer.replaceWholeDocument("later edit"));
                return AtomicFileWrite.writeIf(target, bytes, commit);
            });
            Tab tab = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<EditorArea>field(fx.controller, "editorArea").selectedTab());

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("saved snapshot");
                Platform.runLater(() -> dismissAlert(tr("dialog.save"), promptSeen));
                FxTestSupport.call(fx.controller, "closeTab", new Class<?>[] {Tab.class}, tab);
            });
            async.await(promptSeen, "the dirty-close Save choice");
            async.awaitFx();

            assertEquals("saved snapshot", Files.readString(file));
            assertEquals("later edit", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                    .tabs()
                    .contains(tab)));
        }
    }

    @Test
    void failedSaveAsRollsBackIdentityAndKeepsUntitledContentDirty(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setContent("template content");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, created, true);
                return created;
            });
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            workflows.setDocumentWriter((target, bytes, commit) -> {
                throw new IOException("injected Save As failure");
            });

            FxTestSupport.runOnFx(() -> assertTrue(workflows.applySaveAsTarget(buffer, dir.resolve("failed.txt"))));
            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals(null, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals("template content", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(Files.exists(dir.resolve("failed.txt")));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedAutosaveRetainsPrecedingCommitIdentity(boolean externalChange, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("acknowledged-autosave.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            CountDownLatch releaseAutosave = new CountDownLatch(1);
            async.onClose(releaseAutosave::countDown);

            var gate = FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("BBBB");
                workflows.save(buffer);
                barrier(worker); // the first commit is still awaiting its FX acknowledgment
                var paused = worker.submit(() -> {
                    async.await(releaseAutosave, "the preceding save acknowledgment");
                    return null;
                });
                buffer.replaceWholeDocument("CCCCCC");
                workflows.autoSaveBuffer(buffer);
                return paused;
            });
            async.awaitFx(); // retire the first request before the queued autosave reads the file
            assertEquals(4, FxTestSupport.callOnFx(() -> buffer.diskSnapshot().size()));
            assertTrue(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
            if (externalChange) {
                var modified = Files.getLastModifiedTime(file);
                Files.writeString(file, "XXXX");
                Files.setLastModifiedTime(file, modified);
            }
            releaseAutosave.countDown();
            async.await(gate);
            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals(externalChange ? "XXXX" : "CCCCCC", Files.readString(file));
            assertEquals(externalChange, FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    externalChange ? 4 : 6,
                    FxTestSupport.callOnFx(() -> buffer.diskSnapshot().size()));
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

    @Test
    void explicitLocalSaveDoesNotOverwriteAnExternalChangeWithoutConsent(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("local-conflict.txt"), "A");
            EditorBuffer buffer = open(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            Files.writeString(file, "external version\n");
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("editor version\n"));

            CountDownLatch cancelled = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(() -> assertTrue(workflows.save(buffer)));
            async.awaitWorker(FxTestSupport.field(workflows, "autoSaveExecutor"));
            async.awaitFx();
            async.await(cancelled, "local save conflict cancellation");

            assertEquals("external version\n", Files.readString(file));
            assertEquals("editor version\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void saveAsDoesNotOverwriteATargetCreatedAfterSelection(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            Path target = dir.resolve("appeared.txt");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                created.setContent("editor draft\n");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, created, true);
                return created;
            });
            AtomicBoolean injected = new AtomicBoolean();
            workflows.beforeDocumentWriteForTest = () -> {
                if (injected.compareAndSet(false, true)) {
                    try {
                        Files.writeString(target, "external arrival\n");
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
            };

            CountDownLatch cancelled = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(() -> assertTrue(workflows.applySaveAsTarget(buffer, target)));
            async.awaitWorker(FxTestSupport.field(workflows, "autoSaveExecutor"));
            async.awaitFx();
            async.await(cancelled, "Save As target race cancellation");

            assertEquals("external arrival\n", Files.readString(target));
            assertEquals(null, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals("editor draft\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void closingADirtyDiffDraftRequiresAnExplicitChoice() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> {
                String left = "reference\n";
                String right = "working\n";
                DiffViewerPane created = new DiffViewerPane(
                        "draft diff",
                        "left",
                        "right",
                        "x.txt",
                        "x.txt",
                        left,
                        right,
                        DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT),
                        "Monospaced",
                        13,
                        true,
                        "x.txt");
                created.setEditable(DiffViewerPane.EditableSide.RIGHT, text -> true, () -> {}, () -> {});
                created.setOnResultEdited(text -> {});
                FxTestSupport.call(
                        fx.controller,
                        "addContentTab",
                        new Class<?>[] {com.editora.editor.TabContent.class, boolean.class},
                        created,
                        true);
                created.toggleResultEditing();
                ((CodeArea) FxTestSupport.field(created, "resultArea")).replaceText("unapplied draft\n");
                return created;
            });
            Tab tab = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<EditorArea>field(fx.controller, "editorArea").selectedTab());

            CountDownLatch cancelled = pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "closeTab", new Class<?>[] {Tab.class}, tab));
            async.await(cancelled, "dirty diff close cancellation");

            assertTrue(pane.hasDirtyResult());
            assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.<EditorArea>field(fx.controller, "editorArea")
                    .tabs()
                    .contains(tab)));
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

    private static CountDownLatch pressNextDialog(AsyncTestScope async, ButtonBar.ButtonData buttonData)
            throws Exception {
        CountDownLatch pressed = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
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
                if (pressed.getCount() == 0) {
                    stop();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return pressed;
    }
}
