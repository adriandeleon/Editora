package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.collections.FXCollections;

import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.io.DocumentWriteSequencer;
import com.editora.search.SearchQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class SearchCoordinatorReplaceInFilesFxTest {

    private static final SearchQuery QUERY = new SearchQuery("old", true, false, false);

    private static final class RecordingHost extends CoordinatorHostStub {

        private String status;
        private String error;

        @Override
        public void setStatus(String message) {
            status = message;
        }

        @Override
        public void setError(String message) {
            error = message;
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void failedDurableHistoryLeavesTheClosedFileIntactAndReportsPartialSuccess(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path openPath = Files.writeString(dir.resolve("open.txt"), "old open");
            Path closedPath = Files.writeString(dir.resolve("closed.txt"), "old closed");
            EditorBuffer open = dirtyBuffer(openPath, "old open", " unsaved");
            async.onClose(() -> dispose(open));
            Map<String, EditorBuffer> openBuffers = new HashMap<>();
            openBuffers.put(key(openPath), open);
            List<String> recordedPreimages = new ArrayList<>();
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    openBuffers,
                    (file, content, completion) -> {
                        recordedPreimages.add(content);
                        completion.accept(false);
                    },
                    count -> true,
                    Executors.newSingleThreadExecutor());
            async.onClose(coordinator::shutdown);

            SearchCoordinator.ReplaceResult result = async.await(FxTestSupport.callOnFx(
                    () -> coordinator.replaceInFiles(QUERY, "new", List.of(openPath, closedPath))));

            assertEquals(1, result.count());
            assertEquals(1, result.changedFiles());
            assertEquals(List.of(closedPath), result.failedFiles());
            assertFalse(result.cancelled());
            assertEquals("old closed", Files.readString(closedPath));
            assertEquals(List.of("old closed"), recordedPreimages);
            assertEquals("new open unsaved", FxTestSupport.callOnFx(open::getContent));
            assertTrue(FxTestSupport.callOnFx(open::isDirty));
            assertTrue(host.error.contains(closedPath.toString()));

            FxTestSupport.runOnFx(() -> open.getArea().undo());
            assertEquals("old open unsaved", FxTestSupport.callOnFx(open::getContent));
            assertTrue(FxTestSupport.callOnFx(open::isDirty));
        }
    }

    @Test
    void mixedOperationReportsExactCountsFailuresAndDurablePreimages(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path openPath = Files.writeString(dir.resolve("open.txt"), "old open");
            Path closedPath = Files.writeString(dir.resolve("closed.txt"), "old closed old");
            Path readOnlyPath = Files.writeString(dir.resolve("read-only.txt"), "old read only");
            Path invalidPath = dir.resolve("invalid.txt");
            byte[] invalidBytes = {(byte) 0xc3, 0x28};
            Files.write(invalidPath, invalidBytes);
            Path unchangedPath = Files.writeString(dir.resolve("unchanged.txt"), "nothing to replace");

            EditorBuffer open = dirtyBuffer(openPath, "old open", " unsaved");
            EditorBuffer readOnly = buffer(readOnlyPath, "old read only");
            FxTestSupport.runOnFx(() -> readOnly.setViewMode(true));
            async.onClose(() -> dispose(open));
            async.onClose(() -> dispose(readOnly));
            Map<String, EditorBuffer> openBuffers = new HashMap<>();
            openBuffers.put(key(openPath), open);
            openBuffers.put(key(readOnlyPath), readOnly);
            Map<Path, String> preimages = new HashMap<>();
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    openBuffers,
                    (file, content, completion) -> {
                        preimages.put(file, content);
                        completion.accept(true);
                    },
                    count -> true,
                    Executors.newSingleThreadExecutor());
            async.onClose(coordinator::shutdown);

            SearchCoordinator.ReplaceResult result =
                    async.await(FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(
                            QUERY, "new", List.of(openPath, closedPath, readOnlyPath, invalidPath, unchangedPath))));

            assertEquals(3, result.count());
            assertEquals(2, result.changedFiles());
            assertEquals(List.of(readOnlyPath, invalidPath), result.failedFiles());
            assertEquals(Map.of(closedPath, "old closed old"), preimages);
            assertEquals("new closed new", Files.readString(closedPath));
            assertArrayEquals(invalidBytes, Files.readAllBytes(invalidPath));
            assertEquals("nothing to replace", Files.readString(unchangedPath));
            assertEquals("old read only", FxTestSupport.callOnFx(readOnly::getContent));
            assertTrue(host.error.contains(readOnlyPath.toString()));
            assertTrue(host.error.contains(invalidPath.toString()));

            FxTestSupport.runOnFx(() -> open.getArea().undo());
            assertEquals("old open unsaved", FxTestSupport.callOnFx(open::getContent));
        }
    }

    @Test
    void fileOpenedAfterClassificationIsReplacedInMemoryInsteadOfOnDisk(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("opened-late.txt"), "old text");
            Map<String, EditorBuffer> openBuffers = new HashMap<>();
            AtomicInteger historyCalls = new AtomicInteger();
            CountDownLatch workerOccupied = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            worker.submit(() -> {
                workerOccupied.countDown();
                releaseWorker.await();
                return null;
            });
            async.onClose(releaseWorker::countDown);
            async.await(workerOccupied, "the replace worker to be held before classification completes");
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    openBuffers,
                    (path, content, completion) -> {
                        historyCalls.incrementAndGet();
                        completion.accept(true);
                    },
                    count -> true,
                    worker);
            async.onClose(coordinator::shutdown);

            var completion = FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(QUERY, "new", List.of(file)));
            EditorBuffer opened = dirtyBuffer(file, "old text", " unsaved");
            async.onClose(() -> dispose(opened));
            FxTestSupport.runOnFx(() -> openBuffers.put(key(file), opened));
            releaseWorker.countDown();

            SearchCoordinator.ReplaceResult result = async.await(completion);

            assertEquals(1, result.count());
            assertEquals(1, result.changedFiles());
            assertTrue(result.failedFiles().isEmpty());
            assertEquals(0, historyCalls.get());
            assertEquals("old text", Files.readString(file));
            assertEquals("new text unsaved", FxTestSupport.callOnFx(opened::getContent));
            assertTrue(FxTestSupport.callOnFx(opened::isDirty));
            FxTestSupport.runOnFx(() -> opened.getArea().undo());
            assertEquals("old text unsaved", FxTestSupport.callOnFx(opened::getContent));
        }
    }

    @Test
    void fileOpenedDuringTheHistoryBarrierCannotBeOverwrittenOnDisk(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("opened-during-history.txt"), "old text");
            Map<String, EditorBuffer> openBuffers = new HashMap<>();
            CountDownLatch historyRequested = new CountDownLatch(1);
            AtomicReference<java.util.function.Consumer<Boolean>> historyCompletion = new AtomicReference<>();
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    openBuffers,
                    (path, content, completion) -> {
                        historyCompletion.set(completion);
                        historyRequested.countDown();
                    },
                    count -> true,
                    Executors.newSingleThreadExecutor());
            async.onClose(coordinator::shutdown);

            var completion = FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(QUERY, "new", List.of(file)));
            async.await(historyRequested, "the durable history barrier");
            EditorBuffer opened = dirtyBuffer(file, "old text", " unsaved");
            async.onClose(() -> dispose(opened));
            FxTestSupport.runOnFx(() -> {
                openBuffers.put(key(file), opened);
                historyCompletion.get().accept(true);
            });

            SearchCoordinator.ReplaceResult result = async.await(completion);

            assertEquals(0, result.count());
            assertEquals(0, result.changedFiles());
            assertEquals(List.of(file), result.failedFiles());
            assertEquals("old text", Files.readString(file));
            assertEquals("old text unsaved", FxTestSupport.callOnFx(opened::getContent));
            assertTrue(FxTestSupport.callOnFx(opened::isDirty));
            assertTrue(host.error.contains(file.toString()));
        }
    }

    @Test
    void cancelledConfirmationChangesNothing(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path openPath = Files.writeString(dir.resolve("open.txt"), "old open");
            Path closedPath = Files.writeString(dir.resolve("closed.txt"), "old closed");
            EditorBuffer open = dirtyBuffer(openPath, "old open", " unsaved");
            async.onClose(() -> dispose(open));
            Map<String, EditorBuffer> openBuffers = new HashMap<>();
            openBuffers.put(key(openPath), open);
            AtomicInteger historyCalls = new AtomicInteger();
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    openBuffers,
                    (file, content, completion) -> historyCalls.incrementAndGet(),
                    count -> false,
                    Executors.newSingleThreadExecutor());
            async.onClose(coordinator::shutdown);

            SearchCoordinator.ReplaceResult result = async.await(FxTestSupport.callOnFx(
                    () -> coordinator.replaceInFiles(QUERY, "new", List.of(openPath, closedPath))));

            assertTrue(result.cancelled());
            assertEquals(0, result.count());
            assertEquals(0, result.changedFiles());
            assertTrue(result.failedFiles().isEmpty());
            assertEquals(0, historyCalls.get());
            assertEquals("old open unsaved", FxTestSupport.callOnFx(open::getContent));
            assertEquals("old closed", Files.readString(closedPath));
            assertNull(host.status);
            assertNull(host.error);
        }
    }

    @Test
    void shutdownDuringTheHistoryBarrierLeavesTheFileIntactAndCompletesAsFailed(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("shutdown.txt"), "old text");
            CountDownLatch historyRequested = new CountDownLatch(1);
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    new HashMap<>(),
                    (path, content, completion) -> historyRequested.countDown(),
                    count -> true,
                    Executors.newSingleThreadExecutor());
            async.onClose(coordinator::shutdown);

            var completion = FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(QUERY, "new", List.of(file)));
            async.await(historyRequested, "the durable history barrier before shutdown");
            coordinator.shutdown();
            SearchCoordinator.ReplaceResult result = async.await(completion);

            assertEquals(0, result.count());
            assertEquals(0, result.changedFiles());
            assertEquals(List.of(file), result.failedFiles());
            assertEquals("old text", Files.readString(file));
        }
    }

    @Test
    void shutdownBeforeAQueuedWorkerStartsCompletesTheFileAsFailed(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Path file = Files.writeString(dir.resolve("queued-shutdown.txt"), "old text");
            CountDownLatch workerOccupied = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            worker.submit(() -> {
                workerOccupied.countDown();
                releaseWorker.await();
                return null;
            });
            async.onClose(releaseWorker::countDown);
            async.await(workerOccupied, "the replace worker to be occupied before shutdown");
            RecordingHost host = new RecordingHost();
            SearchCoordinator coordinator = coordinator(
                    host,
                    new HashMap<>(),
                    (path, content, completion) -> completion.accept(true),
                    count -> true,
                    worker);
            async.onClose(coordinator::shutdown);

            var completion = FxTestSupport.callOnFx(() -> coordinator.replaceInFiles(QUERY, "new", List.of(file)));
            coordinator.shutdown();
            SearchCoordinator.ReplaceResult result = async.await(completion);

            assertEquals(0, result.count());
            assertEquals(0, result.changedFiles());
            assertEquals(List.of(file), result.failedFiles());
            assertEquals("old text", Files.readString(file));
        }
    }

    private static SearchCoordinator coordinator(
            RecordingHost host,
            Map<String, EditorBuffer> openBuffers,
            SearchCoordinator.HistoryRecorder historyRecorder,
            SearchCoordinator.ReplaceConfirmation confirmation,
            ExecutorService worker)
            throws Exception {
        DocumentWriteSequencer sequencer = new DocumentWriteSequencer();
        SearchCoordinator.Ops ops = SearchCoordinator.ops(
                new SearchCoordinator.Navigation(
                        () -> null, (file, line, col, focus) -> {}, () -> false, () -> {}, () -> {}),
                new SearchCoordinator.ReplaceSupport(
                        file -> openBuffers.get(key(file)), buffer -> false, historyRecorder, sequencer::begin),
                new SearchCoordinator.Persistence(query -> {}, FXCollections::observableArrayList, found -> {}));
        return FxTestSupport.callOnFx(() -> new SearchCoordinator(host, ops, worker, confirmation));
    }

    private static EditorBuffer dirtyBuffer(Path file, String baseline, String unsaved) throws Exception {
        EditorBuffer buffer = buffer(file, baseline);
        FxTestSupport.runOnFx(() -> buffer.getArea().appendText(unsaved));
        assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        return buffer;
    }

    private static EditorBuffer buffer(Path file, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(content);
            buffer.getArea().getUndoManager().forgetHistory();
            return buffer;
        });
    }

    private static void dispose(EditorBuffer buffer) throws Exception {
        FxTestSupport.runOnFx(buffer::dispose);
    }

    private static String key(Path file) {
        return PathKeys.key(file);
    }
}
