package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4: Local History recorded what each save wrote and never what the first one replaced, so the text a file
 * held when it was opened — or that Git, a formatter or another editor had put there — was in no revision.
 */
@Tag("fx")
class LocalHistoryBaselineFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theFirstSaveOfASessionRecordsTheTextItReplaces(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "written before Editora\r\nsecond line\r\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            EditorBuffer buffer = open(fx.controller, file);

            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("first edit\n");
                assertTrue(workflows.save(buffer));
            });
            List<HistoryRevision> afterFirst = awaitRevisions(fx, file, 2);

            assertEquals(
                    List.of(HistoryRevision.REASON_SAVE, HistoryCoordinator.REASON_BASELINE),
                    afterFirst.stream().map(HistoryRevision::reason).toList(),
                    "newest first: the save, then what it replaced");
            assertEquals("first edit\n", body(fx, afterFirst.get(0)));
            assertEquals("written before Editora\nsecond line\n", body(fx, afterFirst.get(1)), "in the editor's form");
            assertTrue(afterFirst.get(1).timestamp() <= afterFirst.get(0).timestamp());

            // A later save replaces this window's own bytes: one revision, not two.
            awaitClean(buffer);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("second edit\n");
                assertTrue(workflows.save(buffer));
            });
            List<HistoryRevision> afterSecond = awaitRevisions(fx, file, 3);
            settle(async, fx);
            assertEquals(
                    List.of(
                            HistoryRevision.REASON_SAVE,
                            HistoryRevision.REASON_SAVE,
                            HistoryCoordinator.REASON_BASELINE),
                    revisions(fx, file).stream().map(HistoryRevision::reason).toList());
            assertEquals("second edit\n", body(fx, afterSecond.get(0)));
        }
    }

    @Test
    void aSaveOverBytesWrittenOutsideEditoraRecordsThemAndOverItsOwnDoesNot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tracked.txt");
        byte[] ours = "saved by this window\n".getBytes(StandardCharsets.UTF_8);
        byte[] fromGit = "checked out by git\n".getBytes(StandardCharsets.UTF_8);
        byte[] next = "next save\n".getBytes(StandardCharsets.UTF_8);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");

            // The save hook runs on a save worker; a new file replaces nothing.
            onSaveWorker(async, () -> history.saveReplaced(file, null, ours));
            settle(async, fx);
            assertEquals(List.of(), revisions(fx, file));

            // Replacing our own bytes: nothing to keep.
            onSaveWorker(async, () -> history.saveReplaced(file, ours, next));
            settle(async, fx);
            assertEquals(List.of(), revisions(fx, file));

            // The file was rewritten underneath (a checkout, a formatter) and is now saved over.
            onSaveWorker(async, () -> history.saveReplaced(file, fromGit, ours));
            List<HistoryRevision> recorded = awaitRevisions(fx, file, 1);
            assertEquals(HistoryRevision.REASON_EXTERNAL, recorded.get(0).reason());
            assertEquals("checked out by git\n", body(fx, recorded.get(0)));

            // The same outside text again is the newest revision already: not recorded twice.
            onSaveWorker(async, () -> history.saveReplaced(file, fromGit, ours));
            settle(async, fx);
            assertEquals(1, revisions(fx, file).size());

            // Binary content is outside Local History's text contract.
            onSaveWorker(async, () -> history.saveReplaced(file, new byte[] {1, 0, 2, 0, 0}, ours));
            settle(async, fx);
            assertEquals(1, revisions(fx, file).size());
        }
    }

    @Test
    void nothingIsRecordedWhileLocalHistoryIsOff(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("off.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setLocalHistory(false));

            onSaveWorker(
                    async,
                    () -> history.saveReplaced(
                            file,
                            "before\n".getBytes(StandardCharsets.UTF_8),
                            "after\n".getBytes(StandardCharsets.UTF_8)));
            settle(async, fx);

            assertEquals(List.of(), revisions(fx, file));
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    /** The save hook is called from a save worker, never from the FX thread. */
    private static void onSaveWorker(AsyncTestScope async, AsyncTestScope.CheckedRunnable call) throws Exception {
        async.start("save-worker", call).join();
        async.assertNoAsynchronousFailures();
    }

    private static EditorBuffer open(MainController controller, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(com.editora.editor.LineEndings.toLf(Files.readString(file)));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static List<HistoryRevision> revisions(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(
                () -> List.copyOf(fx.shared.historyBucket("").getOrDefault(PathKeys.normalizedKey(file), List.of())));
    }

    private static List<HistoryRevision> awaitRevisions(FxWindowFixture fx, Path file, int count) throws Exception {
        await(() -> revisions(fx, file).size() >= count, count + " recorded revisions of " + file.getFileName());
        return revisions(fx, file);
    }

    private static void awaitClean(EditorBuffer buffer) throws Exception {
        await(() -> FxTestSupport.callOnFx(() -> !buffer.isDirty()), "the save to be acknowledged");
    }

    private static void await(Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    /** Lets a record travel FX → history worker → FX, so "nothing was recorded" is a real observation. */
    private static void settle(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        java.util.concurrent.ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
        for (int round = 0; round < 3; round++) {
            async.awaitFx();
            async.awaitWorker(worker);
        }
        async.awaitFx();
    }

    private static String body(FxWindowFixture fx, HistoryRevision revision) throws Exception {
        HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        return blobs.get(revision.sha256());
    }
}
