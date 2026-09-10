package com.editora.ui;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryService;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DelegatingFileOperations;
import com.editora.io.DocumentWriteSequencer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class HistoryRestoreFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"restored text\n", ""})
    void restoresADeletedFileWithMissingParentsIncludingAnEmptyRevision(String restored) throws Exception {
        ControlledLoader loader = new ControlledLoader();
        Path file = dir.resolve("missing/parents/recovered.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            loader.complete(restored);

            assertEquals(HistoryCoordinator.RestoreResult.RESTORED, async.await(result));
            assertEquals(restored, Files.readString(file));
            assertEquals(List.of(file), harness.ops.opened);
            assertEquals(1, harness.ops.refreshes.get());
        }
    }

    @Test
    void cancellingAnExistingFileRestoreLeavesItByteForByteUntouched() throws Exception {
        Path file = Files.writeString(dir.resolve("cancel.txt"), "keep this");
        ControlledLoader loader = new ControlledLoader();
        AtomicInteger confirmations = new AtomicInteger();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> {
                confirmations.incrementAndGet();
                return false;
            }));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));

            assertEquals(HistoryCoordinator.RestoreResult.CANCELLED, async.await(result));
            assertEquals("keep this", Files.readString(file));
            assertEquals(1, confirmations.get());
            assertNull(loader.callback.get(), "cancel must happen before loading or writing the revision");
            assertTrue(harness.ops.opened.isEmpty());
        }
    }

    @Test
    void aFileChangedWhileTheBlobLoadsIsNotOverwritten() throws Exception {
        Path file = Files.writeString(dir.resolve("changed.txt"), "confirmed version");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            Files.writeString(file, "external edit after confirmation");
            loader.complete("history body");

            assertEquals(HistoryCoordinator.RestoreResult.TARGET_CHANGED, async.await(result));
            assertEquals("external edit after confirmation", Files.readString(file));
            assertTrue(harness.ops.opened.isEmpty());
            assertEquals(0, harness.ops.refreshes.get());
        }
    }

    @Test
    void aFileCreatedWhileADeletedRevisionLoadsIsNotOverwritten() throws Exception {
        Path file = dir.resolve("recreated.txt");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            Files.writeString(file, "new owner");
            loader.complete("deleted history body");

            assertEquals(HistoryCoordinator.RestoreResult.TARGET_CHANGED, async.await(result));
            assertEquals("new owner", Files.readString(file));
            assertTrue(harness.ops.opened.isEmpty());
        }
    }

    @Test
    void anAtomicReplacementFailureLeavesTheExistingFileIntact() throws Exception {
        Path documents = Files.createDirectory(dir.resolve("documents"));
        Path file = Files.writeString(documents.resolve("write-failure.txt"), "only recoverable disk copy");
        ControlledLoader loader = new ControlledLoader();
        HistoryCoordinator.RestoreWriter failingWriter =
                (target, expected, replacement, current) -> AtomicFileWrite.replaceIfUnchanged(
                        target, expected, replacement, current, new DelegatingFileOperations() {
                            @Override
                            public void move(Path source, Path destination, CopyOption... options) throws IOException {
                                throw new IOException("simulated restore move failure");
                            }
                        });
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, failingWriter, path -> true));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            loader.complete("replacement");

            assertEquals(HistoryCoordinator.RestoreResult.WRITE_FAILED, async.await(result));
            assertEquals("only recoverable disk copy", Files.readString(file));
            try (var entries = Files.list(documents)) {
                assertEquals(1, entries.count(), "failed restore staging must be cleaned up");
            }
            assertTrue(harness.ops.opened.isEmpty());
            assertEquals(0, harness.ops.refreshes.get());
        }
    }

    @Test
    void aMissingOrCorruptBlobCannotBecomeAnEmptyFile() throws Exception {
        Path file = Files.writeString(dir.resolve("missing-blob.txt"), "valuable current bytes");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            loader.complete(null);

            assertEquals(HistoryCoordinator.RestoreResult.CONTENT_UNAVAILABLE, async.await(result));
            assertEquals("valuable current bytes", Files.readString(file));
            assertTrue(harness.ops.opened.isEmpty());
        }
    }

    @Test
    void restoreIntoTheEditorIsDirtyUndoableAndPreservesTheDiskCopy() throws Exception {
        Path file = Files.writeString(dir.resolve("editor-restore.txt"), "disk baseline");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true, file, "live editor text"));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreHistory(revision(file)));
            async.await(loader.requested, "history revision content request");
            loader.complete("historical revision");

            assertEquals(HistoryCoordinator.RestoreResult.RESTORED, async.await(result));
            FxTestSupport.runOnFx(() -> {
                assertEquals("historical revision", harness.buffer.getContent());
                assertTrue(harness.buffer.isDirty());
                assertTrue(harness.buffer.getArea().isUndoAvailable());
                harness.buffer.getArea().undo();
                assertEquals("live editor text", harness.buffer.getContent());
            });
            assertEquals("disk baseline", Files.readString(file));
        }
    }

    @Test
    void anEditMadeWhileARevisionLoadsIsNeverReplaced() throws Exception {
        Path file = Files.writeString(dir.resolve("edited-during-restore.txt"), "disk baseline");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true, file, "initial editor text"));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreHistory(revision(file)));
            async.await(loader.requested, "history revision content request");
            FxTestSupport.runOnFx(() -> harness.buffer.getArea().appendText(" + user edit"));
            loader.complete("stale historical revision");

            assertEquals(HistoryCoordinator.RestoreResult.BUFFER_CHANGED, async.await(result));
            FxTestSupport.runOnFx(() -> assertEquals("initial editor text + user edit", harness.buffer.getContent()));
            assertEquals("disk baseline", Files.readString(file));
        }
    }

    @Test
    void closingTheBufferWhileARevisionLoadsCannotApplyIntoAReplacementBuffer() throws Exception {
        Path file = Files.writeString(dir.resolve("closed-during-restore.txt"), "disk baseline");
        ControlledLoader loader = new ControlledLoader();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true, file, "closing editor text"));

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreHistory(revision(file)));
            async.await(loader.requested, "history revision content request");
            FxTestSupport.runOnFx(harness.buffer::dispose);
            loader.complete("stale historical revision");

            assertEquals(HistoryCoordinator.RestoreResult.BUFFER_CHANGED, async.await(result));
            assertEquals("disk baseline", Files.readString(file));
        }
    }

    @Test
    void aPendingOlderSaveCannotRecreatePreRestoreContent() throws Exception {
        Path file = Files.writeString(dir.resolve("pending-save.txt"), "disk baseline");
        ControlledLoader loader = new ControlledLoader();
        CountDownLatch oldWriterEntered = new CountDownLatch(1);
        CountDownLatch releaseOldWriter = new CountDownLatch(1);
        AtomicReference<DocumentWriteSequencer.Outcome<Boolean>> oldOutcome = new AtomicReference<>();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Harness harness = async.own(harness(loader, null, path -> true));
            async.onClose(releaseOldWriter::countDown);
            DocumentWriteSequencer.Ticket oldSave = harness.sequencer.begin(file);
            async.start("pending-history-restore-save", () -> {
                try (oldSave) {
                    oldOutcome.set(oldSave.runIfCurrent(() -> {
                        oldWriterEntered.countDown();
                        try {
                            if (!releaseOldWriter.await(10, TimeUnit.SECONDS)) {
                                throw new IOException("timed out waiting to release pending save");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                        return AtomicFileWrite.writeIf(file, "older autosave".getBytes(UTF_8), oldSave::isCurrent);
                    }));
                }
            });
            async.await(oldWriterEntered, "older save to enter the document write lock");

            CompletableFuture<HistoryCoordinator.RestoreResult> result =
                    FxTestSupport.callOnFx(() -> harness.history.restoreRevisionToDisk(revision(file)));
            async.await(loader.requested, "history revision content request");
            loader.complete("restored history body");
            releaseOldWriter.countDown();

            assertEquals(HistoryCoordinator.RestoreResult.RESTORED, async.await(result));
            assertFalse(oldOutcome.get().value(), "the pre-restore save must fail its commit guard");
            assertEquals("restored history body", Files.readString(file));
        }
    }

    private Harness harness(
            ControlledLoader loader, HistoryCoordinator.RestoreWriter writer, Predicate<Path> confirmation)
            throws Exception {
        return harness(loader, writer, confirmation, null, null);
    }

    private Harness harness(
            ControlledLoader loader,
            HistoryCoordinator.RestoreWriter writer,
            Predicate<Path> confirmation,
            Path bufferPath,
            String bufferContent)
            throws Exception {
        HistoryService service = new HistoryService(new HistoryBlobStore(dir.resolve("blobs-" + System.nanoTime())));
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        return FxTestSupport.callOnFx(() -> {
            RecordingHost host = new RecordingHost();
            EditorBuffer buffer = null;
            if (bufferPath != null) {
                buffer = new EditorBuffer();
                buffer.setPath(bufferPath);
                buffer.setContent(bufferContent);
                host.active = buffer;
            }
            DiffOps diffOps = new DiffOps(buffer);
            DiffCoordinator diff = new DiffCoordinator(host, null, diffOps);
            RecordingOps ops = new RecordingOps(dir.resolve("unused-blobs"));
            HistoryCoordinator.RestoreSupport support = new HistoryCoordinator.RestoreSupport(
                    loader,
                    writer == null ? HistoryCoordinator::writeRestoredContent : writer,
                    confirmation,
                    sequencer::begin);
            HistoryCoordinator history = new HistoryCoordinator(host, diff, ops, service, support);
            return new Harness(history, diff, service, host, ops, sequencer, buffer);
        });
    }

    private static HistoryRevision revision(Path file) {
        return new HistoryRevision(file.toString(), 1L, 1L, "unused-test-sha", HistoryRevision.REASON_SAVE);
    }

    private static final class ControlledLoader implements HistoryCoordinator.RevisionContentLoader {
        final CountDownLatch requested = new CountDownLatch(1);
        final AtomicReference<Consumer<String>> callback = new AtomicReference<>();

        @Override
        public void load(HistoryRevision revision, Consumer<String> completion) {
            callback.set(completion);
            requested.countDown();
        }

        void complete(String text) throws Exception {
            Consumer<String> completion = callback.get();
            FxTestSupport.runOnFx(() -> completion.accept(text));
        }
    }

    private static final class RecordingHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        volatile String status;
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            status = message;
        }
    }

    private static final class RecordingOps implements HistoryCoordinator.Ops {
        final Map<String, List<HistoryRevision>> history = new HashMap<>();
        final List<Path> opened = new ArrayList<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final Path blobsDir;

        RecordingOps(Path blobsDir) {
            this.blobsDir = blobsDir;
        }

        @Override
        public Map<String, List<HistoryRevision>> historyMap() {
            return history;
        }

        @Override
        public Map<String, Map<String, List<HistoryRevision>>> historyByProject() {
            return Map.of("test", history);
        }

        @Override
        public void saveHistory() {}

        @Override
        public Path blobsDir() {
            return blobsDir;
        }

        @Override
        public void setToolWindowAvailable(boolean available) {}

        @Override
        public void openToolWindow() {}

        @Override
        public void openPath(Path file) {
            opened.add(file);
        }

        @Override
        public void refreshProjectTree() {
            refreshes.incrementAndGet();
        }

        @Override
        public String currentTextOf(Path file) {
            return "";
        }
    }

    private static final class DiffOps implements DiffCoordinator.Ops {
        private final EditorBuffer buffer;

        DiffOps(EditorBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void addDiffTab(com.editora.editor.TabContent pane) {}

        @Override
        public EditorBuffer openBufferFor(Path target) {
            return buffer != null && target.equals(buffer.getPath()) ? buffer : null;
        }

        @Override
        public EditorBuffer openBackgroundBuffer(Path target) {
            return null;
        }

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return false;
        }

        @Override
        public List<DiffViewerPane> openDiffPanes() {
            return List.of();
        }

        @Override
        public DiffViewerPane activeDiffPane() {
            return null;
        }

        @Override
        public Path finderStartDir() {
            return null;
        }

        @Override
        public String editorConfigCharset(Path file) {
            return null;
        }

        @Override
        public void openAt(Path file, int line) {}
    }

    private record Harness(
            HistoryCoordinator history,
            DiffCoordinator diff,
            HistoryService service,
            RecordingHost host,
            RecordingOps ops,
            DocumentWriteSequencer sequencer,
            EditorBuffer buffer)
            implements AutoCloseable {

        @Override
        public void close() throws Exception {
            history.shutdown();
            diff.shutdown();
            service.shutdown();
            if (buffer != null && !buffer.isDisposed()) {
                FxTestSupport.runOnFx(buffer::dispose);
            }
        }
    }
}
