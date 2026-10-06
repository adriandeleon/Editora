package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local File History is indexed by path. A file renamed or moved inside Editora (its own rename, a folder
 * renamed or dragged in the Project tree) used to leave its revisions under the path that no longer exists —
 * the history window then showed an empty list for a file with weeks of history — and a Save As started the
 * new file with none. Driven through the entry point the Project tree calls after it moved the file.
 */
@Tag("fx")
class LocalHistoryFollowsRenameFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aRenamedFileAndAMovedFolderKeepTheirHistoryAndItsContent(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            Path src = Files.createDirectories(dir.resolve("src"));
            Path open = Files.writeString(src.resolve("open.txt"), "first\n");
            Path closed = Files.writeString(src.resolve("closed.txt"), "closed file\n");
            EditorBuffer buffer = open(fx, open);
            FxTestSupport.runOnFx(() -> {
                history.record(buffer, HistoryRevision.REASON_SAVE);
                history.record(closed, "closed file\n", HistoryRevision.REASON_SAVE);
            });
            settle(async, fx, worker);
            FxTestSupport.runOnFx(() -> {
                buffer.setContent("second\n");
                history.record(buffer, HistoryRevision.REASON_SAVE);
            });
            settle(async, fx, worker);
            assertEquals(2, revisions(fx, open).size(), "precondition");

            // The file's own rename.
            Path renamed = src.resolve("renamed.txt");
            treeRenamed(fx, open, renamed);
            settle(async, fx, worker);
            assertNull(revisions(fx, open), "nothing is left under the path that is gone");
            assertEquals(List.of("second\n", "first\n"), contents(blobs, revisions(fx, renamed)));
            assertTrue(revisions(fx, renamed).stream().allMatch(r -> r.path().equals(renamed.toString())));

            // The folder above it, with a file that has no tab.
            Path lib = dir.resolve("lib");
            treeRenamed(fx, src, lib);
            settle(async, fx, worker);
            Path renamedNow = lib.resolve("renamed.txt");
            Path closedNow = lib.resolve("closed.txt");
            assertEquals(renamedNow, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals(List.of("second\n", "first\n"), contents(blobs, revisions(fx, renamedNow)));
            assertEquals(List.of("closed file\n"), contents(blobs, revisions(fx, closedNow)));
            assertNull(revisions(fx, renamed));
            assertNull(revisions(fx, closed));

            // The index on disk agrees, and a blob collection forced now deletes none of the bodies.
            String index = Files.readString(fx.shared.getHistoryFile());
            assertTrue(index.contains(jsonPath(renamedNow)), index);
            assertFalse(index.contains(jsonPath(open)), "the old path is gone from the index file");
            FxTestSupport.runOnFx(() -> {
                fx.shared.historyService().requestGc();
                buffer.setContent("third\n");
                history.record(buffer, HistoryRevision.REASON_SAVE); // publishes the index, then collects
            });
            settle(async, fx, worker);
            assertEquals(List.of("third\n", "second\n", "first\n"), contents(blobs, revisions(fx, renamedNow)));
            assertEquals(List.of("closed file\n"), contents(blobs, revisions(fx, closedNow)));
        }
    }

    @Test
    void aSaveRecordedWhileTheFileIsBeingRenamedLandsUnderItsNewName(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            Path file = Files.writeString(dir.resolve("a.txt"), "one\n");
            Path target = dir.resolve("b.txt");
            EditorBuffer buffer = open(fx, file);
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx, worker);

            // Submitted under the old name; the rename happens in the same FX turn, before the worker answers.
            Files.move(file, target);
            FxTestSupport.runOnFx(() -> {
                buffer.setContent("two\n");
                history.record(buffer, HistoryRevision.REASON_SAVE);
                FxTestSupport.call(
                        fx.controller, "onProjectFileRenamed", new Class<?>[] {Path.class, Path.class}, file, target);
            });
            settle(async, fx, worker);

            assertNull(revisions(fx, file), "the late revision does not resurrect the old path");
            assertEquals(List.of("two\n", "one\n"), contents(blobs, revisions(fx, target)));
        }
    }

    @Test
    void saveAsStartsTheNewFileWithThePastOfTheOneItCameFrom(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
            Path b = dir.resolve("b.txt");
            EditorBuffer buffer = open(fx, a);
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx, worker);

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("two\n"));
            assertTrue(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, b)));
            ExecutorService saver = FxTestSupport.field(workflows, "autoSaveExecutor");
            async.awaitWorker(saver);
            settle(async, fx, worker);

            assertTrue(Files.exists(b));
            assertEquals(List.of("one\ntwo\n", "one\n"), contents(blobs, revisions(fx, b)), "the copy has the past");
            assertEquals(List.of("one\n"), contents(blobs, revisions(fx, a)), "the original keeps its own");
        }
    }

    private static List<HistoryRevision> revisions(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<HistoryRevision> list = fx.shared.historyBucket("").get(PathKeys.normalizedKey(file));
            return list == null ? null : List.copyOf(list);
        });
    }

    private static List<String> contents(HistoryBlobStore blobs, List<HistoryRevision> revisions) {
        assertNotNull(revisions, "the file has history");
        return revisions.stream().map(r -> blobs.get(r.sha256())).toList();
    }

    private static String jsonPath(Path file) {
        return file.toString().replace("\\", "\\\\");
    }

    private static void treeRenamed(FxWindowFixture fx, Path old, Path target) throws Exception {
        Files.move(old, target);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "onProjectFileRenamed", new Class<?>[] {Path.class, Path.class}, old, target));
        FxTestSupport.drainFx();
    }

    private static void settle(AsyncTestScope async, FxWindowFixture fx, ExecutorService worker) throws Exception {
        for (int round = 0; round < 3; round++) {
            async.awaitWorker(worker);
            async.awaitFx();
            assertTrue(fx.shared.flushWrites());
        }
        async.awaitWorker(worker);
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }
}
