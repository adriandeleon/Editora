package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;

import com.editora.config.HistoryRevision;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diff, merge and Local History applies against the real window: every one of them rebuilds a whole
 * document from a displayed comparison, so each must refuse when the document is no longer the one shown.
 */
@Tag("fx")
class DiffApplyGuardsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- G6: a second hunk before the re-diff lands -------------------------------------------------------

    @Test
    void aSecondHunkAppliedBeforeTheRediffDoesNotRevertTheFirst(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        String head = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        Path file = repo.write("letters.txt", head);
        repo.commitAll("init");
        repo.write("letters.txt", "A\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            List<Integer> blocks = blockStarts(pane);
            assertEquals(2, blocks.size(), "two separate hunks");

            // Both clicks land before the refreshed model is installed: the second is computed from rows that
            // still show the file as it was before the first.
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invokeWith(pane, "applyBlock", int.class, blocks.get(0));
                FxTestSupport.invokeWith(pane, "applyBlock", int.class, blocks.get(1));
            });
            assertEquals(
                    "a\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n",
                    FxTestSupport.callOnFx(buffer::getContent),
                    "the first hunk stays applied; the stale second apply is refused");

            // Once the pane shows the new state the remaining hunk applies normally.
            settle(async, fx);
            List<Integer> remaining = blockStarts(pane);
            assertEquals(1, remaining.size());
            FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(pane, "applyBlock", int.class, remaining.get(0)));
            assertEquals(head, FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    // --- G3: Local History hunk restore ---------------------------------------------------------------------

    @Test
    void historyHunkRestoreKeepsTextTypedAfterTheRevisionWasSelected(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("story.txt"), "one\ntwo\nthree\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("one\nTWO\nthree\n");
                history.refresh();
                ListView<HistoryRevision> revisions = FxTestSupport.field(history.panel(), "revisions");
                assertEquals(1, revisions.getItems().size(), "the recorded revision is listed");
                revisions.getSelectionModel().select(0);
            });
            settle(async, fx);
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> FxTestSupport.field(history.panel(), "pane"));
            assertNotNull(pane, "the revision diff is showing");
            List<Integer> blocks = blockStarts(pane);
            assertEquals(1, blocks.size());

            // The user keeps typing in the editor; the panel still shows the file as it was when selected.
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("one\nTWO\nthree\nfour, typed later\n"));
            FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(pane, "applyBlock", int.class, blocks.get(0)));
            assertEquals(
                    "one\nTWO\nthree\nfour, typed later\n",
                    FxTestSupport.callOnFx(buffer::getContent),
                    "a hunk computed from the old text must not replace the document");

            // The refused apply re-baselines and re-diffs; the hunk then restores just its own lines.
            settle(async, fx);
            assertEquals("one\nTWO\nthree\nfour, typed later\n", FxTestSupport.callOnFx(pane::editableBaselineText));
            List<Integer> rebased = blockStarts(pane);
            FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(pane, "applyBlock", int.class, rebased.get(0)));
            assertEquals("one\ntwo\nthree\nfour, typed later\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    // --- G9: merge Apply after the source tab closed ----------------------------------------------------------

    @Test
    void mergeApplyAfterTheSourceTabClosedGoesToTheLiveDocument(@TempDir Path dir) throws Exception {
        String conflicted = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\n";
        Path file = Files.writeString(dir.resolve("story.txt"), conflicted);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer opened = open(fx, file);
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            DiffCoordinator.Ops ops = FxTestSupport.field(diff, "ops");
            String source = FxTestSupport.callOnFx(opened::text);
            closeTab(fx, opened);
            assertNull(FxTestSupport.callOnFx(() -> ops.openBufferFor(file)), "the source tab is closed");

            // The disposed buffer's text still equals the baseline, so writing into it "succeeded" and the
            // resolution was lost. It must reach a live buffer for the file instead.
            boolean applied = FxTestSupport.callOnFx(() -> diff.applyMergeResolution(opened, source, "ours\n"));

            assertTrue(applied);
            EditorBuffer live = FxTestSupport.callOnFx(() -> ops.openBufferFor(file));
            assertNotNull(live, "the file was reopened to receive the resolution");
            assertEquals("ours\n", FxTestSupport.callOnFx(live::getContent));
            assertTrue(FxTestSupport.callOnFx(live::isDirty), "applied through the normal undoable path, unsaved");
        }
    }

    @Test
    void mergeApplyIsRefusedWhenTheClosedFileChangedOrCannotBeFound(@TempDir Path dir) throws Exception {
        String conflicted = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\n";
        Path file = Files.writeString(dir.resolve("story.txt"), conflicted);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer opened = open(fx, file);
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            DiffCoordinator.Ops ops = FxTestSupport.field(diff, "ops");
            String source = FxTestSupport.callOnFx(opened::text);
            closeTab(fx, opened);
            Files.writeString(file, "resolved by hand in another tool\n");

            CountDownLatch stale = watchStatus(fx, tr("status.merge.stale")::equals);
            assertFalse(FxTestSupport.callOnFx(() -> diff.applyMergeResolution(opened, source, "ours\n")));
            async.await(stale, "stale merge feedback");
            assertNull(FxTestSupport.callOnFx(() -> ops.openBufferFor(file)), "no stray tab is left behind");
            assertEquals("resolved by hand in another tool\n", Files.readString(file));

            // An untitled document has no path to find it by: once closed it cannot receive the result.
            EditorBuffer untitled = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent(conflicted);
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                return b;
            });
            String untitledSource = FxTestSupport.callOnFx(untitled::text);
            closeTab(fx, untitled);
            assertFalse(FxTestSupport.callOnFx(() -> diff.applyMergeResolution(untitled, untitledSource, "ours\n")));
        }
    }

    // --- G11: a side over the size cap ---------------------------------------------------------------------

    @Test
    void anOversizedGitBlobCompletesTheDiffWithAReadOnlySurrogate(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        String row = "x".repeat(1023) + "\n";
        Path file = repo.write("big.txt", row.repeat(10 * 1024 + 300)); // just over the 10 MB capture cap
        repo.commitAll("big");
        repo.write("big.txt", "small now\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);

            // The blob side returned without calling back, so no pane ever appeared ("Loading…" in a review).
            DiffViewerPane pane = onlyPane(diff);
            assertEquals(tr("diff.side.tooLarge"), FxTestSupport.<String>field(pane, "leftText"));
            assertFalse(FxTestSupport.<Boolean>field(pane, "mutationAllowed"), "a surrogate side is never applied");
        }
    }

    @Test
    void anOversizedWorkingFileIsNotReadIntoADiff(@TempDir Path dir) throws Exception {
        String row = "y".repeat(1023) + "\n";
        Path big = Files.writeString(dir.resolve("big.log"), row.repeat(10 * 1024 + 300));
        Path small = Files.writeString(dir.resolve("small.log"), "small\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> diff.compareFiles(big, small));
            settle(async, fx);

            DiffViewerPane pane = onlyPane(diff);
            assertEquals(tr("diff.side.tooLarge"), FxTestSupport.<String>field(pane, "leftText"));
            assertEquals("small\n", FxTestSupport.<String>field(pane, "rightText"));
        }
    }

    // --- G5: staging a hunk of a CRLF file from the diff view ------------------------------------------------

    @Test
    void stagingOneHunkOfACrlfFileFromTheDiffViewKeepsItsBytes(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("win.txt", "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\nseven\r\neight\r\nnine\r\n");
        repo.commitAll("init");
        repo.write("win.txt", "ONE\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\nseven\r\neight\r\nNINE\r\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("win.txt", false));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            List<Integer> blocks = blockStarts(pane);
            assertEquals(2, blocks.size());
            CountDownLatch staged = watchStatus(fx, tr("status.diff.hunkStaged")::equals);

            // An LF-only text patch never applied to this blob: the action always ended in "hunk stale".
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    pane,
                    "performGitAction",
                    new Class<?>[] {DiffViewerPane.GitHunkAction.class, int.class, boolean.class},
                    DiffViewerPane.GitHunkAction.STAGE,
                    blocks.get(0),
                    false));
            async.await(staged, "hunk staged");

            assertArrayEquals(
                    "ONE\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\nseven\r\neight\r\nnine\r\n"
                            .getBytes(StandardCharsets.UTF_8),
                    repo.git("show", ":win.txt").out(),
                    "exactly the first hunk, with every CRLF intact");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

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

    private static void closeTab(FxWindowFixture fx, EditorBuffer buffer) throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.markClean();
            Tab tab = (Tab) FxTestSupport.call(fx.controller, "tabFor", new Class<?>[] {EditorBuffer.class}, buffer);
            FxTestSupport.call(fx.controller, "closeTab", new Class<?>[] {Tab.class}, tab);
        });
        assertTrue(FxTestSupport.callOnFx(buffer::isDisposed), "closing the tab disposes its buffer");
    }

    private static DiffCoordinator applyRepo(FxWindowFixture fx, Path root) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        GitStatus status = new GitStatus(true, "main", null, 0, 0, List.of());
        FxTestSupport.runOnFx(() -> git.applyState(new GitService.RepoState(root, status, Map.of(), Map.of())));
        return FxTestSupport.field(fx.controller, "diffCoordinator");
    }

    private static DiffViewerPane onlyPane(DiffCoordinator diff) throws Exception {
        DiffCoordinator.Ops ops = FxTestSupport.field(diff, "ops");
        List<DiffViewerPane> panes = FxTestSupport.callOnFx(ops::openDiffPanes);
        assertEquals(1, panes.size(), "one diff tab should be open");
        return panes.get(0);
    }

    private static List<Integer> blockStarts(DiffViewerPane pane) throws Exception {
        return FxTestSupport.callOnFx(
                () -> List.copyOf(FxTestSupport.<DiffModel>field(pane, "model").changeBlockStarts()));
    }

    /**
     * Runs every background stage a diff passes through to completion, without sleeping: each round waits
     * for the Git, file-read, history and diff workers to drain and then for the FX queue they post to. A
     * request crosses those hops a fixed, small number of times, so a few rounds settle it.
     */
    private static void settle(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        ExecutorService gitWorker = FxTestSupport.field(git.service(), "exec");
        ExecutorService diffWorker = FxTestSupport.field(FxTestSupport.<Object>field(diff, "diffService"), "exec");
        ExecutorService readWorker = FxTestSupport.field(diff, "fileReadExecutor");
        ExecutorService historyWorker = FxTestSupport.field(fx.shared.historyService(), "exec");
        for (int round = 0; round < 6; round++) {
            async.awaitWorker(gitWorker);
            async.awaitFx();
            awaitBothReadThreads(async, readWorker);
            async.awaitFx();
            async.awaitWorker(historyWorker);
            async.awaitFx();
            async.awaitWorker(diffWorker);
            async.awaitFx();
        }
    }

    /** The file-read pool has two threads; occupying both at once proves everything queued earlier is done. */
    private static void awaitBothReadThreads(AsyncTestScope async, ExecutorService readWorker) throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        var first = readWorker.submit(() -> {
            bothRunning.countDown();
            return bothRunning.await(30, java.util.concurrent.TimeUnit.SECONDS);
        });
        var second = readWorker.submit(() -> {
            bothRunning.countDown();
            return bothRunning.await(30, java.util.concurrent.TimeUnit.SECONDS);
        });
        assertTrue(async.await(first));
        assertTrue(async.await(second));
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
}
