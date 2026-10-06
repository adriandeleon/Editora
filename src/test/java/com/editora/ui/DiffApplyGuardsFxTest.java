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
            assertEquals(DiffCoordinator.tooLargeSide("HEAD:big.txt"), FxTestSupport.<String>field(pane, "leftText"));
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
            assertTrue(
                    FxTestSupport.<String>field(pane, "leftText").startsWith(tr("diff.side.tooLarge") + " ⟦10.3 MiB"));
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

    // --- round 2 -------------------------------------------------------------------------------------------

    /** B2-3: one constant stand-in text for every oversized side made any two such files "identical". */
    @Test
    void twoDifferentOversizedFilesAreNotReportedIdentical(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.log"), ("y".repeat(1023) + "\n").repeat(10 * 1024 + 300));
        Path b = Files.writeString(dir.resolve("b.log"), ("z".repeat(1023) + "\n").repeat(10 * 1024 + 300));

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> diff.compareFiles(a, b));
            settle(async, fx);

            DiffViewerPane pane = onlyPane(diff);
            assertEquals(1, blockStarts(pane).size(), "two different 10 MB files must show as different");
            assertFalse(FxTestSupport.<Boolean>field(pane, "mutationAllowed"));
        }
    }

    /** A9-8: Revert hunk is an apply to the local file, so the pane's Undo and Save must follow it. */
    @Test
    void revertHunkEnablesUndoAndSaveAndSaveWritesTheFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("letters.txt", "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n");
        repo.commitAll("init");
        repo.write("letters.txt", "A\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("letters.txt", false));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            List<Integer> blocks = blockStarts(pane);
            assertEquals(2, blocks.size());
            javafx.scene.control.Button save = FxTestSupport.field(pane, "saveButton");
            javafx.scene.control.Button undo = FxTestSupport.field(pane, "undoButton");
            assertTrue(FxTestSupport.callOnFx(save::isDisabled));

            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    pane,
                    "performGitAction",
                    new Class<?>[] {DiffViewerPane.GitHunkAction.class, int.class, boolean.class},
                    DiffViewerPane.GitHunkAction.REVERT,
                    blocks.get(0),
                    false));
            // The closed file is opened into a background buffer on the file-load worker first.
            for (int attempt = 0; attempt < 200 && blockStarts(pane).size() != 1; attempt++) {
                Thread.sleep(25);
                settle(async, fx);
            }

            assertEquals(1, blockStarts(pane).size(), "the reverted hunk is gone from the diff");
            assertFalse(FxTestSupport.callOnFx(save::isDisabled), "Save follows an accepted revert");
            assertFalse(FxTestSupport.callOnFx(undo::isDisabled), "Undo follows an accepted revert");
            assertEquals("A\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n", Files.readString(file), "not on disk until saved");

            FxTestSupport.runOnFx(save::fire);
            settle(async, fx);
            assertEquals("a\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n", Files.readString(file));
        }
    }

    /** C2-5: HEAD has a staged rename under its old path; HEAD:<new path> showed the whole file as added. */
    @Test
    void showDiffOnAStagedRenameComparesWithTheOldPath(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        String body = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\neleven\ntwelve\n";
        repo.write("OldName.txt", body);
        repo.commitAll("init");
        repo.git("mv", "OldName.txt", "NewName.txt");
        repo.write("NewName.txt", body.replace("six", "SIX"));
        repo.git("add", "NewName.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            GitStatus status = new GitStatus(
                    true, "main", null, 0, 0, List.of(new GitStatus.FileEntry("NewName.txt", 'R', '.', "OldName.txt")));
            FxTestSupport.runOnFx(
                    () -> git.applyState(new GitService.RepoState(repo.root, status, Map.of(), Map.of())));
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> diff.diffGitPanelFile("NewName.txt", true));
            settle(async, fx);

            DiffModel model = FxTestSupport.field(onlyPane(diff), "model");
            assertEquals(1, model.added());
            assertEquals(1, model.removed());
        }
    }

    /** A8-6: closing the Result editor must bring the apply chevrons back. */
    @Test
    void closingTheResultEditorRestoresTheApplyChevrons(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("sample.txt", "a\nb\nc\n");
        repo.commitAll("init");
        repo.write("sample.txt", "a\nB\nc\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            int before = applySlots(pane, 1);
            assertTrue(before > 0, "the changed row offers an apply chevron");

            FxTestSupport.runOnFx(pane::toggleResultEditing);
            settle(async, fx);
            assertTrue(FxTestSupport.callOnFx(pane::hasResultEditor));
            assertEquals(0, applySlots(pane, 1), "no chevrons while the Result is being edited");

            FxTestSupport.runOnFx(pane::toggleResultEditing);
            settle(async, fx);
            assertFalse(FxTestSupport.callOnFx(pane::hasResultEditor));
            assertEquals(before, applySlots(pane, 1));
        }
    }

    /** A8-6: a draft comparison must not survive as the "working" side once the editor is closed. */
    @Test
    void closingTheResultEditorDropsADraftComparison(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("sample.txt", "a\nb\nc\n");
        repo.commitAll("init");
        repo.write("sample.txt", "a\nB\nc\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            FxTestSupport.runOnFx(pane::toggleResultEditing);
            settle(async, fx);
            String draft = "a\nB\nc\nEXTRA DRAFT LINE\n";
            FxTestSupport.runOnFx(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(pane, "resultArea")
                    .replaceText(draft));
            // The draft re-diff is debounced by a 250 ms timer; wait until the pane shows the draft.
            for (int attempt = 0;
                    attempt < 200
                            && !draft.equals(FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "rightText")));
                    attempt++) {
                Thread.sleep(25);
                settle(async, fx);
            }
            assertEquals(draft, FxTestSupport.<String>callOnFx(() -> FxTestSupport.field(pane, "rightText")));

            // Typed back to the baseline and closed before the correcting re-diff's timer fires.
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<org.fxmisc.richtext.CodeArea>field(pane, "resultArea")
                        .replaceText("a\nB\nc\n");
                pane.toggleResultEditing();
            });
            settle(async, fx);

            assertFalse(FxTestSupport.callOnFx(pane::hasResultEditor));
            assertEquals("a\nB\nc\n", FxTestSupport.<String>callOnFx(() -> FxTestSupport.field(pane, "rightText")));
            assertEquals("a\nB\nc\n", FxTestSupport.callOnFx(pane::editableBaselineText));
        }
    }

    /** A8-6: a focused editable RichTextFX area pins its window unless its owner disposes it. */
    @Test
    void aWindowClosedWithTheResultEditorFocusedIsReleased(@TempDir Path dir) throws Exception {
        java.lang.ref.WeakReference<MainController> closed = openResultEditorAndClose(dir);
        for (int attempt = 0; attempt < 60 && closed.get() != null; attempt++) {
            FxTestSupport.runOnFx(() -> {}); // drain the FX queue: a pending event can hold the last edge
            System.gc();
            Thread.sleep(100);
        }
        assertNull(closed.get(), "the closed window is still reachable (Result editor caret blink timer?)");
    }

    private static java.lang.ref.WeakReference<MainController> openResultEditorAndClose(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("sample.txt", "a\nb\nc\n");
        repo.commitAll("init");
        repo.write("sample.txt", "a\nB\nc\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            FxTestSupport.runOnFx(pane::toggleResultEditing);
            settle(async, fx);
            boolean focused = false;
            for (int attempt = 0; attempt < 50 && !focused; attempt++) {
                // Focus is granted asynchronously, and a window left by an earlier test may still hold it.
                focused = FxTestSupport.callOnFx(() -> {
                    javafx.stage.Stage stage = FxTestSupport.field(fx.controller, "stage");
                    stage.toFront();
                    stage.requestFocus();
                    org.fxmisc.richtext.CodeArea result = FxTestSupport.field(pane, "resultArea");
                    result.requestFocus();
                    return result.isFocused();
                });
                FxTestSupport.drainFx();
                if (!focused) {
                    Thread.sleep(20);
                }
            }
            // Without focus there is no blink timer and so nothing to release: when another test class's
            // window keeps the focus for the whole wait, the scenario cannot be set up — skip, do not fail.
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    focused, "the Result editor took focus, so its caret blink timer is running");
            FxTestSupport.drainFx();
            return new java.lang.ref.WeakReference<>(fx.controller);
        }
    }

    /** A8-5: a scroll-bar drag moves no focus; the pane being scrolled must still lead the other. */
    @Test
    void scrollingAnUnfocusedPaneByItsScrollBarMovesTheOtherPane(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            body.append("line ").append(i).append('\n');
        }
        Path file = repo.write("long.txt", body.toString());
        repo.commitAll("init");
        repo.write("long.txt", body.toString().replace("line 10\n", "LINE 10\n").replace("line 590\n", "LINE 590\n"));

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            DiffCoordinator diff = applyRepo(fx, repo.root);
            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(file));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.ToggleButton context = FxTestSupport.field(pane, "contextButton");
                if (context.isSelected()) {
                    context.fire(); // expand the context so both panes have a real scroll range
                }
            });
            settle(async, fx);
            org.fxmisc.richtext.CodeArea left = FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "leftArea"));
            org.fxmisc.richtext.CodeArea right = FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "rightArea"));
            // Whatever has focus, it is not the pane about to be scrolled.
            FxTestSupport.runOnFx(left::requestFocus);
            settle(async, fx);

            FxTestSupport.runOnFx(() -> {
                javafx.scene.Node scrollPane = right.getParent();
                javafx.event.Event.fireEvent(
                        scrollPane,
                        new javafx.scene.input.MouseEvent(
                                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                                1,
                                1,
                                1,
                                1,
                                javafx.scene.input.MouseButton.PRIMARY,
                                1,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null));
                right.estimatedScrollYProperty().setValue(2000.0);
            });
            double leftY = 0;
            for (int attempt = 0; attempt < 100 && leftY <= 0; attempt++) {
                Thread.sleep(20);
                FxTestSupport.drainFx();
                leftY = FxTestSupport.callOnFx(left::getEstimatedScrollY);
            }
            double rightY = FxTestSupport.callOnFx(right::getEstimatedScrollY);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.ToggleButton context = FxTestSupport.field(pane, "contextButton");
                if (!context.isSelected()) {
                    context.fire(); // the choice is remembered process-wide: put the default back
                }
            });
            assertTrue(rightY > 0, "the right pane scrolled");
            assertEquals(rightY, leftY, 1.0, "the left pane follows the scrolled right pane");
        }
    }

    /** A9-n3: a newly recorded revision reloads the list; the diff being worked on must stay. */
    @Test
    void aNewlyRecordedRevisionKeepsTheSelectedHistoryDiff(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("story.txt"), "one\ntwo\nthree\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx);
            HistoryRevision first = FxTestSupport.callOnFx(() -> {
                buffer.replaceWholeDocument("one\nTWO\nthree\n");
                history.refresh();
                ListView<HistoryRevision> revisions = FxTestSupport.field(history.panel(), "revisions");
                revisions.getSelectionModel().select(0);
                return revisions.getSelectionModel().getSelectedItem();
            });
            settle(async, fx);
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> FxTestSupport.field(history.panel(), "pane"));
            assertNotNull(pane);

            // A save (or autosave) of the active file records a revision and reloads the panel's list.
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx);

            ListView<HistoryRevision> revisions = FxTestSupport.field(history.panel(), "revisions");
            assertEquals(2, FxTestSupport.callOnFx(() -> revisions.getItems().size()));
            assertEquals(
                    first,
                    FxTestSupport.callOnFx(() -> revisions.getSelectionModel().getSelectedItem()),
                    "the revision being restored from stays selected");
            assertTrue(
                    pane == FxTestSupport.callOnFx(() -> FxTestSupport.<DiffViewerPane>field(history.panel(), "pane")),
                    "and its diff stays on screen");
        }
    }

    /** A9-n4 / C2-8: Revert re-baselines when the restore has landed, not before it. */
    @Test
    void historyRevertLeavesThePanelShowingTheRestoredText(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("story.txt"), "one\ntwo\nthree\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            FxTestSupport.runOnFx(() -> history.record(buffer, HistoryRevision.REASON_SAVE));
            settle(async, fx);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("one\nTWO edited\nthree\nfour\n");
                history.refresh();
                ListView<HistoryRevision> revisions = FxTestSupport.field(history.panel(), "revisions");
                revisions.getSelectionModel().select(0);
            });
            settle(async, fx);
            DiffViewerPane pane = FxTestSupport.callOnFx(() -> FxTestSupport.field(history.panel(), "pane"));
            assertEquals(2, blockStarts(pane).size());

            FxTestSupport.runOnFx(() -> FxTestSupport.call(history.panel(), "revertSelected", new Class<?>[] {}));
            settle(async, fx);

            assertEquals("one\ntwo\nthree\n", FxTestSupport.callOnFx(buffer::getContent));
            DiffViewerPane shown = FxTestSupport.callOnFx(() -> FxTestSupport.field(history.panel(), "pane"));
            assertNotNull(shown);
            assertEquals(0, blockStarts(shown).size(), "the panel compares with the restored text");
            Label count = FxTestSupport.field(history.panel(), "diffCount");
            assertEquals(tr("history.window.differences", 0), FxTestSupport.callOnFx(count::getText));
        }
    }

    /** A9-n11: an option toggle issued while a swap is pending must not pair one side's model with the other's text. */
    @Test
    void patchReviewOptionToggleDuringAPendingSwapStaysConsistent(@TempDir Path dir) throws Exception {
        Path patch = Files.writeString(
                dir.resolve("change.patch"),
                "--- a/f.txt\n+++ b/f.txt\n@@ -1,3 +1,3 @@\n keep\n-old line\n+new line\n tail\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, patch);
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(() -> diff.openPatchFile(buffer));
            settle(async, fx);
            DiffViewerPane pane = onlyPane(diff);

            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(pane, "requestSwap", new Class<?>[] {});
                FxTestSupport.call(pane, "updateIgnoreCase", new Class<?>[] {boolean.class}, true);
            });
            settle(async, fx);

            String leftText = FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "leftText"));
            DiffModel model = FxTestSupport.callOnFx(() -> FxTestSupport.field(pane, "model"));
            List<String> modelLeft = model.rows().stream()
                    .filter(row -> row.leftLine() >= 1)
                    .map(com.editora.diff.DiffModels.Row::left)
                    .toList();
            assertEquals(com.editora.diff.DiffEngine.lines(leftText), modelLeft, "the rows describe the text shown");
            assertFalse(FxTestSupport.<Boolean>callOnFx(() -> FxTestSupport.field(pane, "swapPending")));
        }
    }

    /** A9-6: the resolver may apply again after a change of mind; its baseline follows what it applied. */
    @Test
    void aSecondMergeApplyReplacesTheFirstResolution(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(
                dir.resolve("notes.txt"), "top\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\nend\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(diff::resolveConflicts);
            settle(async, fx);
            MergeViewerPane pane = FxTestSupport.callOnFx(() -> {
                EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
                return (MergeViewerPane) area.selectedTab().getUserData();
            });
            List<com.editora.diff.ConflictParser.Choice> choices = FxTestSupport.field(pane, "choices");

            assertTrue(FxTestSupport.<Boolean>callOnFx(() -> {
                choices.set(0, com.editora.diff.ConflictParser.Choice.OURS);
                FxTestSupport.call(pane, "refreshResult", new Class<?>[] {});
                return (Boolean) FxTestSupport.call(pane, "saveResult", new Class<?>[] {});
            }));
            assertEquals("top\nours\nend\n", FxTestSupport.callOnFx(buffer::getContent));

            assertTrue(
                    FxTestSupport.<Boolean>callOnFx(() -> {
                        choices.set(0, com.editora.diff.ConflictParser.Choice.THEIRS);
                        FxTestSupport.call(pane, "refreshResult", new Class<?>[] {});
                        return (Boolean) FxTestSupport.call(pane, "saveResult", new Class<?>[] {});
                    }),
                    "the document still holds what the resolver applied, so it is not stale");
            assertEquals("top\ntheirs\nend\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    /** A8-4: a diff that dies on the worker must still call back (with no model), not strand the caller. */
    @Test
    void aFailedDiffStillReportsBack() throws Exception {
        com.editora.diff.DiffService service = new com.editora.diff.DiffService();
        try {
            CountDownLatch reported = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<DiffModel> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // Over the rendered-lines cap the metadata summary dereferences the (null) left text and throws.
            service.compute(null, "\n".repeat(130_000), model -> {
                result.set(model);
                reported.countDown();
            });
            assertTrue(reported.await(30, java.util.concurrent.TimeUnit.SECONDS), "the callback never fired");
            assertNull(result.get());
        } finally {
            service.shutdown();
        }
    }

    private static int applySlots(DiffViewerPane pane, int row) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            org.fxmisc.richtext.CodeArea right = FxTestSupport.field(pane, "rightArea");
            javafx.scene.Node graphic = right.getParagraphGraphicFactory().apply(row);
            int slots = 0;
            for (javafx.scene.Node child : ((javafx.scene.Parent) graphic).getChildrenUnmodifiable()) {
                if (child.getStyleClass().contains("diff-apply")) {
                    slots++;
                }
            }
            return slots;
        });
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
