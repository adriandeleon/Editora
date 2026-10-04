package com.editora.ui;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.editor.EditorBuffer;
import com.editora.editorconfig.EditorConfigCharset;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff and merge views against a real window and real Git: what Stage/Unstage hunk writes to the index
 * when the hunk reaches the end of the file or is the whole file, and that every side is the text an editor
 * buffer of the same bytes would hold (charset, line endings, whole document).
 */
@Tag("fx")
class DiffMergeNormalFormFxTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- Stage / Unstage hunk: end of file, new files, deleted files -----------------------------------------

    @Test
    void stagingAnUntrackedFileStagesTheWorkingFilesOwnBytes(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "x\n");
        repo.commitAll("init");
        // Byte-order mark, CRLF and a final newline: the empty index side knows none of them.
        byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\r', '\n', 'b', '\r', '\n'};
        repo.write("new.txt", bytes);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("new.txt", false));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            CountDownLatch staged = watchStatus(fx, tr("status.diff.hunkStaged")::equals);
            gitAction(
                    pane, DiffViewerPane.GitHunkAction.STAGE, blockStarts(pane).get(0));
            async.await(staged, "hunk staged");

            assertArrayEquals(bytes, repo.git("show", ":new.txt").out(), "the index holds the file as it is on disk");
            assertEquals("A  new.txt\n", repo.git("status", "--porcelain").text());
        }
    }

    @Test
    void unstagingAllOfANewlyAddedFileRemovesItsIndexEntry(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "x\n");
        repo.commitAll("init");
        repo.write("added.txt", "x\ny\n");
        repo.git("add", "added.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("added.txt", true));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            CountDownLatch unstaged = watchStatus(fx, tr("status.diff.hunkUnstaged")::equals);
            gitAction(
                    pane,
                    DiffViewerPane.GitHunkAction.UNSTAGE,
                    blockStarts(pane).get(0));
            async.await(unstaged, "hunk unstaged");

            // A blob could only say "empty file": the path stayed staged as a one-newline file.
            assertEquals("", repo.git("ls-files", "-s", "added.txt").text());
            assertEquals("?? added.txt\n", repo.git("status", "--porcelain").text());
        }
    }

    @Test
    void stagingAllOfADeletedFileStagesTheDeletion(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path gone = repo.write("gone.txt", "one\ntwo\nthree\n");
        repo.commitAll("init");
        Files.delete(gone);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("gone.txt", false));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            CountDownLatch staged = watchStatus(fx, tr("status.diff.hunkStaged")::equals);
            gitAction(
                    pane, DiffViewerPane.GitHunkAction.STAGE, blockStarts(pane).get(0));
            async.await(staged, "hunk staged");

            assertEquals("D  gone.txt\n", repo.git("status", "--porcelain").text());
        }
    }

    @Test
    void stagingTheHunkThatHoldsTheLastLineTakesItsEndOfFileFromTheWorkingFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("f.txt", "a\nb\nc\nd\ne\nf\ng\nh\n");
        repo.commitAll("init");
        repo.write("f.txt", "A\nb\nc\nd\ne\nf\ng\nh\ni"); // a second hunk that adds an unterminated last line

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("f.txt", false));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            List<Integer> blocks = blockStarts(pane);
            assertEquals(2, blocks.size());
            CountDownLatch staged = watchStatus(fx, tr("status.diff.hunkStaged")::equals);
            gitAction(pane, DiffViewerPane.GitHunkAction.STAGE, blocks.get(1));
            async.await(staged, "hunk staged");

            assertEquals(
                    "a\nb\nc\nd\ne\nf\ng\nh\ni",
                    repo.git("show", ":f.txt").text(),
                    "the staged last line is unterminated, as it is in the working file");
        }
    }

    // --- sides are decoded the way the editor reads the file --------------------------------------------------

    @Test
    void aHunkAppliesToAClosedCrlfFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("win.txt", "a\r\nb\r\nc\r\n");
        repo.commitAll("init");
        repo.write("win.txt", "a\r\nB\r\nc\r\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            DiffCoordinator.Ops ops = FxTestSupport.field(diff, "ops");
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            List<Integer> blocks = blockStarts(pane);
            assertEquals(1, blocks.size());
            CountDownLatch applied = watchStatus(fx, tr("status.diff.applied")::equals);

            // The side kept the file's \r\n while the buffer it is applied into holds \n: never "unchanged".
            FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(pane, "applyBlock", int.class, blocks.get(0)));
            async.await(applied, "hunk applied");

            EditorBuffer buffer = FxTestSupport.callOnFx(() -> ops.openBufferFor(file));
            assertNotNull(buffer, "the closed file was opened to receive the hunk");
            assertEquals("a\nb\nc\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("CRLF", FxTestSupport.callOnFx(buffer::getLineEnding), "it is still saved with CRLF");
        }
    }

    @Test
    void anUnmodifiedWindows1252FileHasNoDifferenceAgainstHead(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        byte[] bytes = "titulo=Configuración\nplain=ascii\nprecio=10 €\n".getBytes(CP1252);
        Path file = repo.write("legacy.properties", bytes); // no byte-order mark, no .editorconfig charset
        repo.commitAll("init");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);

            // Closed: both sides come from bytes.
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane closed = onlyPane(diff);
            assertEquals(new String(bytes, CP1252), FxTestSupport.<String>field(closed, "leftText"));
            assertEquals(new String(bytes, CP1252), FxTestSupport.<String>field(closed, "rightText"));

            // Open: the working side is the buffer, loaded the way the editor loads it.
            EditorBuffer buffer = openAsEditorWould(fx, file);
            FxTestSupport.runOnFx(closed::refresh);
            settle(async, fx);
            String head = FxTestSupport.field(closed, "leftText");
            assertFalse(head.contains("�"), "HEAD must not be decoded with replacement characters: " + head);
            assertEquals(FxTestSupport.callOnFx(buffer::getContent), head);
            assertTrue(FxTestSupport.<DiffModel>field(closed, "model").isEmpty(), "an unmodified file has no hunks");
        }
    }

    // --- a narrowed buffer is still the whole file -------------------------------------------------------------

    @Test
    void theWorkingSideOfANarrowedBufferIsTheWholeDocument(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        String text = "one\ntwo\nthree\nfour\nfive\n";
        Path file = repo.write("n.txt", text);
        repo.commitAll("init");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = openAsEditorWould(fx, file);
            assertTrue(FxTestSupport.callOnFx(() -> buffer.narrowTo(text.indexOf("three"), text.indexOf("five"))));
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);

            assertEquals(text, FxTestSupport.<String>field(onlyPane(diff), "rightText"));
        }
    }

    @Test
    void resolvingMarkersInANarrowedBufferKeepsTheRestOfTheFile(@TempDir Path dir) throws Exception {
        String conflict = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\n";
        String text = "header 1\nheader 2\n" + conflict + "footer 1\nfooter 2\n";
        Path file = Files.writeString(dir.resolve("story.txt"), text); // not in a repository: marker fallback

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = openAsEditorWould(fx, file);
            int start = text.indexOf(conflict);
            assertTrue(FxTestSupport.callOnFx(() -> buffer.narrowTo(start, start + conflict.length())));
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(diff::resolveConflicts);
            settle(async, fx);

            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            MergeViewerPane pane = (MergeViewerPane) data;
            assertNotNull(pane, "the resolver opened");
            boolean saved = FxTestSupport.callOnFx(() -> {
                FxTestSupport.call(
                        pane,
                        "choose",
                        new Class<?>[] {int.class, Choice.class, Label.class, String.class},
                        0,
                        Choice.OURS,
                        new Label(),
                        "ours");
                return (Boolean) FxTestSupport.call(pane, "saveResult", new Class<?>[] {});
            });

            assertTrue(saved);
            // Read as the region and written as the whole document, the file became just "ours\n".
            assertEquals("header 1\nheader 2\nours\nfooter 1\nfooter 2\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    /** A tab for {@code file} holding what the editor's own load produces for its bytes. */
    private static EditorBuffer openAsEditorWould(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(Files.readAllBytes(file), null);
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(decoded.text());
            buffer.setDetectedCharset(decoded.charset(), decoded.assumed());
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static void gitAction(DiffViewerPane pane, DiffViewerPane.GitHunkAction action, int row) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                pane,
                "performGitAction",
                new Class<?>[] {DiffViewerPane.GitHunkAction.class, int.class, boolean.class},
                action,
                row,
                false));
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

    /** Drains the Git, file-read, history and diff workers and the FX queue they post to, a few rounds over. */
    private static void settle(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        ExecutorService gitWorker = FxTestSupport.field(git.service(), "exec");
        ExecutorService diffWorker = FxTestSupport.field(FxTestSupport.<Object>field(diff, "diffService"), "exec");
        ExecutorService readWorker = FxTestSupport.field(diff, "fileReadExecutor");
        for (int round = 0; round < 6; round++) {
            async.awaitWorker(gitWorker);
            async.awaitFx();
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
            async.awaitFx();
            async.awaitWorker(diffWorker);
            async.awaitFx();
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
}
