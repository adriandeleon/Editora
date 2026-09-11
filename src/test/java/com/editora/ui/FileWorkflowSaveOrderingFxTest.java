package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DelegatingFileOperations;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class FileWorkflowSaveOrderingFxTest {

    private enum Alias {
        DIRECT,
        NORMALIZED,
        SYMLINK,
        CASE_ALIAS
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @ParameterizedTest(name = "newer save wins through {0} path identity")
    @EnumSource(Alias.class)
    void newerSaveFromAnotherWindowSupersedesAnOlderStagedSave(Alias alias, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController second = newWindow(fx);
            Path target = Files.writeString(dir.resolve("shared.txt"), "baseline");
            Path olderPath = aliasPath(alias, dir, target);
            EditorBuffer older = open(fx.controller, olderPath);
            EditorBuffer newer = open(second, target);
            FileWorkflowCoordinator olderWorkflows = workflows(fx.controller);
            FileWorkflowCoordinator newerWorkflows = workflows(second);
            ExecutorService olderWorker = FxTestSupport.field(olderWorkflows, "autoSaveExecutor");
            ExecutorService newerWorker = FxTestSupport.field(newerWorkflows, "autoSaveExecutor");
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            async.onClose(release::countDown);
            olderWorkflows.setDocumentWriter(blockingStagedWriter(staged, release));

            FxTestSupport.runOnFx(() -> {
                older.replaceWholeDocument("older window snapshot");
                assertTrue(olderWorkflows.save(older));
            });
            async.await(staged, "the older window save to finish staging");
            FxTestSupport.runOnFx(() -> {
                newer.replaceWholeDocument("newer window snapshot");
                assertTrue(newerWorkflows.save(newer));
            });
            release.countDown();

            async.awaitWorker(olderWorker);
            async.awaitWorker(newerWorker);
            async.awaitFx();

            assertEquals("newer window snapshot", Files.readString(target));
            assertTrue(FxTestSupport.callOnFx(older::isDirty), "the uncommitted older snapshot stays dirty");
            assertFalse(FxTestSupport.callOnFx(newer::isDirty), "the committed newer snapshot becomes clean");
            assertFalse(FxTestSupport.callOnFx(() -> olderWorkflows.hasPendingSave(older)));
            assertFalse(FxTestSupport.callOnFx(() -> newerWorkflows.hasPendingSave(newer)));
        }
    }

    @Test
    void saveAsSupersedesAStagedAutosaveForTheOldDestination(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path oldTarget = Files.writeString(dir.resolve("old-name.txt"), "baseline");
            Path newTarget = dir.resolve("new-name.txt");
            EditorBuffer buffer = open(fx.controller, oldTarget);
            FileWorkflowCoordinator workflows = workflows(fx.controller);
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            async.onClose(release::countDown);
            workflows.setDocumentWriter(blockingStagedWriter(staged, release));

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("obsolete old-path snapshot");
                workflows.autoSaveBuffer(buffer);
            });
            async.await(staged, "the old-path autosave to finish staging");
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("latest save-as snapshot");
                workflows.setDocumentWriter(AtomicFileWrite::writeIf);
                assertTrue(workflows.applySaveAsTarget(buffer, newTarget));
            });
            release.countDown();

            async.awaitWorker(worker);
            async.awaitFx();

            assertEquals("baseline", Files.readString(oldTarget), "the obsolete autosave must not alter its old path");
            assertEquals("latest save-as snapshot", Files.readString(newTarget));
            assertEquals(newTarget, FxTestSupport.callOnFx(buffer::getPath));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    @Test
    void saveAsInOneWindowDoesNotCancelAnotherWindowsSaveToTheOldDestination(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController second = newWindow(fx);
            Path oldTarget = Files.writeString(dir.resolve("shared-old-name.txt"), "baseline");
            Path newTarget = dir.resolve("first-window-new-name.txt");
            EditorBuffer movingBuffer = open(fx.controller, oldTarget);
            EditorBuffer oldPathBuffer = open(second, oldTarget);
            FileWorkflowCoordinator movingWorkflows = workflows(fx.controller);
            FileWorkflowCoordinator oldPathWorkflows = workflows(second);
            ExecutorService movingWorker = FxTestSupport.field(movingWorkflows, "autoSaveExecutor");
            ExecutorService oldPathWorker = FxTestSupport.field(oldPathWorkflows, "autoSaveExecutor");
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            async.onClose(release::countDown);
            oldPathWorkflows.setDocumentWriter(blockingStagedWriter(staged, release));

            FxTestSupport.runOnFx(() -> {
                oldPathBuffer.replaceWholeDocument("second window keeps old destination");
                assertTrue(oldPathWorkflows.save(oldPathBuffer));
            });
            async.await(staged, "the other window's old-destination save to finish staging");
            FxTestSupport.runOnFx(() -> {
                movingBuffer.replaceWholeDocument("first window moves destination");
                assertTrue(movingWorkflows.applySaveAsTarget(movingBuffer, newTarget));
            });
            release.countDown();

            async.awaitWorker(oldPathWorker);
            async.awaitWorker(movingWorker);
            async.awaitFx();

            assertEquals("second window keeps old destination", Files.readString(oldTarget));
            assertEquals("first window moves destination", Files.readString(newTarget));
            assertFalse(FxTestSupport.callOnFx(oldPathBuffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(movingBuffer::isDirty));
        }
    }

    @Test
    void closingTheOlderWindowCannotReleaseItsStagedSaveOverTheNewerWindow(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController second = newWindow(fx);
            Path target = Files.writeString(dir.resolve("close-race.txt"), "baseline");
            EditorBuffer closingBuffer = open(fx.controller, target);
            EditorBuffer survivingBuffer = open(second, target);
            FileWorkflowCoordinator closingWorkflows = workflows(fx.controller);
            FileWorkflowCoordinator survivingWorkflows = workflows(second);
            ExecutorService survivingWorker = FxTestSupport.field(survivingWorkflows, "autoSaveExecutor");
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch promptSeen = new CountDownLatch(1);
            async.onClose(release::countDown);
            closingWorkflows.setDocumentWriter(blockingStagedWriter(staged, release));

            FxTestSupport.runOnFx(() -> {
                closingBuffer.replaceWholeDocument("closing window snapshot");
                assertTrue(closingWorkflows.save(closingBuffer));
            });
            async.await(staged, "the closing window save to finish staging");
            FxTestSupport.runOnFx(() -> {
                survivingBuffer.replaceWholeDocument("surviving window snapshot");
                assertTrue(survivingWorkflows.save(survivingBuffer));
                Platform.runLater(() -> dismissAlert(tr("dialog.discard"), promptSeen));
                assertTrue(fx.windowManager.requestClose(fx.controller));
            });
            async.await(promptSeen, "the closing window's dirty-save warning");
            async.awaitWorker(survivingWorker);
            async.awaitFx();

            assertEquals("surviving window snapshot", Files.readString(target));
            assertTrue(FxTestSupport.callOnFx(closingBuffer::isDisposed));
            assertFalse(FxTestSupport.callOnFx(survivingBuffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(() -> survivingWorkflows.hasPendingSave(survivingBuffer)));
        }
    }

    @Test
    void movingAFolderRemapsOpenDescendantsAndCancelsTheirOldPathSaves(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path oldFolder = Files.createDirectory(dir.resolve("before"));
            Path firstFile = Files.writeString(oldFolder.resolve("first.txt"), "first baseline");
            Path secondFile = Files.writeString(oldFolder.resolve("second.txt"), "second baseline");
            Path movedFolder = dir.resolve("after");
            EditorBuffer first = open(fx.controller, firstFile);
            EditorBuffer second = open(fx.controller, secondFile);
            FileWorkflowCoordinator workflows = workflows(fx.controller);
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            CountDownLatch writerClaimed = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            async.onClose(releaseWriter::countDown);
            workflows.beforeDocumentWriteForTest = () -> {
                writerClaimed.countDown();
                try {
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release moved-folder save");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };

            FxTestSupport.runOnFx(() -> {
                first.replaceWholeDocument("obsolete old-path autosave");
                workflows.autoSaveBuffer(first);
            });
            async.await(writerClaimed, "the old-path save worker to claim its ticket");
            Files.move(oldFolder, movedFolder);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "onProjectFileRenamed",
                    new Class<?>[] {Path.class, Path.class},
                    oldFolder,
                    movedFolder));
            releaseWriter.countDown();

            async.awaitWorker(worker);
            async.awaitFx();

            assertFalse(Files.exists(oldFolder), "the stale save must not recreate the moved folder");
            assertEquals("first baseline", Files.readString(movedFolder.resolve("first.txt")));
            assertEquals("second baseline", Files.readString(movedFolder.resolve("second.txt")));
            assertEquals(movedFolder.resolve("first.txt"), FxTestSupport.callOnFx(first::getPath));
            assertEquals(movedFolder.resolve("second.txt"), FxTestSupport.callOnFx(second::getPath));
            assertTrue(FxTestSupport.callOnFx(first::isDirty), "the uncommitted edit stays recoverable in memory");
            assertFalse(FxTestSupport.callOnFx(second::isDirty));
        }
    }

    private static FileWorkflowCoordinator.DocumentWriter blockingStagedWriter(
            CountDownLatch staged, CountDownLatch release) {
        return (target, bytes, commit) ->
                AtomicFileWrite.writeIf(target, bytes, commit, new DelegatingFileOperations() {
                    @Override
                    public void write(Path path, byte[] content) throws IOException {
                        super.write(path, content);
                        staged.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("timed out waiting to release staged save");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("staged save interrupted", e);
                        }
                    }
                });
    }

    private static Path aliasPath(Alias alias, Path dir, Path target) throws IOException {
        return switch (alias) {
            case DIRECT -> target;
            case NORMALIZED -> {
                Files.createDirectories(dir.resolve("unused"));
                yield dir.resolve("unused/../shared.txt");
            }
            case SYMLINK -> {
                Path link = dir.resolve("shared-link.txt");
                try {
                    Files.createSymbolicLink(link, target.getFileName());
                } catch (UnsupportedOperationException | IOException unavailable) {
                    Assumptions.abort("symbolic links unavailable: " + unavailable.getMessage());
                }
                yield link;
            }
            case CASE_ALIAS -> {
                Path differentlyCased = dir.resolve("SHARED.TXT");
                try {
                    Assumptions.assumeTrue(
                            Files.isSameFile(target, differentlyCased), "test filesystem is case-sensitive");
                } catch (IOException unavailable) {
                    Assumptions.abort("case alias unavailable: " + unavailable.getMessage());
                }
                yield differentlyCased;
            }
        };
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

    private static MainController newWindow(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            Object holder = holders.get(holders.size() - 1);
            return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
        });
    }

    private static FileWorkflowCoordinator workflows(MainController controller) {
        return FxTestSupport.field(controller, "fileWorkflows");
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
}
