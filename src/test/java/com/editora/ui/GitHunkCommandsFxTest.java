package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.GitHunk;
import com.editora.git.GitService;
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
 * The active file's changes worked on in the editor: next/previous change, the change card, Revert Hunk and
 * Stage Hunk, the tab tint and the minimap marks — through a real repository, the real refresh and the
 * registered commands.
 */
@Tag("fx")
class GitHunkCommandsFxTest {

    private static final String COMMITTED = "l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\nl11\n";
    /** Line 2 modified, {@code l6} deleted (marked on line 6, {@code l7}), a line added at line 9. */
    private static final String WORKING = "l0\nl1\nL2 changed\nl3\nl4\nl5\nl7\nl8\nl9\nadded\nl10\nl11\n";

    private static final int MODIFIED = GitHunk.Kind.MODIFIED.ordinal();
    private static final int DELETED = GitHunk.Kind.DELETED.ordinal();
    private static final int ADDED = GitHunk.Kind.ADDED.ordinal();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A window on {@code doc.txt} of a repository, with the gutter loaded. */
    private static final class Session {
        final AsyncTestScope async;
        final GitTestRepo repo;
        final FxWindowFixture fx;
        final Path file;
        final EditorBuffer buffer;
        final Object git;
        final ExecutorService gitLane;
        final CommandRegistry commands;

        Session(AsyncTestScope async, GitTestRepo repo, Path file) throws Exception {
            this.async = async;
            this.repo = repo;
            this.file = file;
            fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            git = FxTestSupport.field(fx.controller, "git");
            GitService service = (GitService) FxTestSupport.call(git, "service", new Class<?>[] {});
            gitLane = FxTestSupport.field(service, "exec");
            commands = FxTestSupport.field(fx.controller, "registry");
            CountDownLatch loaded = new CountDownLatch(1);
            buffer = FxTestSupport.callOnFx(() -> {
                workflows.openPath(file);
                EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
                workflows.afterBufferLoad(opening, loaded::countDown);
                return opening;
            });
            async.await(loaded, "document load");
            refresh();
        }

        void refresh() throws Exception {
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(git, "refresh"));
            settle();
        }

        void settle() throws Exception {
            for (int i = 0; i < 3; i++) {
                async.awaitFx();
                async.awaitWorker(gitLane);
                async.awaitFx();
            }
        }

        void run(String command) throws Exception {
            FxTestSupport.runOnFx(() -> assertTrue(commands.run(command), command));
            async.awaitFx();
        }

        void caret(int line) throws Exception {
            FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(line, 0));
        }

        int line() throws Exception {
            return FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph());
        }

        String text() throws Exception {
            return FxTestSupport.callOnFx(() -> buffer.getArea().getText());
        }

        String status() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                Label echo = FxTestSupport.field(statusBar, "echo");
                return echo.getText();
            });
        }

        CountDownLatch whenStatus(String expected) throws Exception {
            CountDownLatch seen = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                Label echo = FxTestSupport.field(statusBar, "echo");
                echo.textProperty().addListener((o, was, now) -> {
                    if (expected.equals(now)) {
                        seen.countDown();
                    }
                });
            });
            return seen;
        }

        int[] marks() throws Exception {
            return FxTestSupport.callOnFx(() -> buffer.gitGutter().marks().clone());
        }

        GitHunkPopup popup() throws Exception {
            DiffCoordinator diff = FxTestSupport.field(fx.controller, "diffCoordinator");
            return FxTestSupport.field(diff.hunks(), "popup");
        }
    }

    private static Session open(AsyncTestScope async, Path dir, String committed, String working) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("doc.txt", committed.getBytes(StandardCharsets.UTF_8));
        repo.commitAll("first");
        Path file = repo.write("doc.txt", working.getBytes(StandardCharsets.UTF_8));
        return new Session(async, repo, file);
    }

    @Test
    void nextAndPreviousStepThroughTheChangesWrapAndOpenAFold(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);
            assertArrayEquals(new int[] {2, 1, MODIFIED, 6, 1, DELETED, 9, 1, ADDED}, s.marks());

            s.caret(0);
            s.run("git.nextChange");
            assertEquals(2, s.line());
            assertEquals(tr("status.git.hunk.position", 1, 3), s.status());
            s.run("git.nextChange");
            assertEquals(6, s.line(), "the line below the deleted one");
            s.run("git.nextChange");
            assertEquals(9, s.line());
            s.run("git.nextChange");
            assertEquals(2, s.line(), "wrapped");
            assertEquals(tr("status.git.hunk.wrappedFirst", 1, 3), s.status());
            s.run("git.previousChange");
            assertEquals(9, s.line());
            assertEquals(tr("status.git.hunk.wrappedLast", 3, 3), s.status());

            // A change hidden in a collapsed region is shown, not jumped into blind.
            FxTestSupport.runOnFx(() -> s.buffer.getArea().foldParagraphs(4, 7));
            assertTrue(FxTestSupport.callOnFx(() -> s.buffer.getArea().isFolded(6)));
            s.caret(9); // folding moved the caret to the fold's header
            s.run("git.previousChange");
            assertEquals(6, s.line());
            assertFalse(FxTestSupport.callOnFx(() -> s.buffer.getArea().isFolded(6)));
        }
    }

    @Test
    void revertHunkPutsTheHeadLinesBackAsOneUndoableEditAndWritesNothing(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);

            s.caret(2);
            s.run("git.revertHunk");
            assertEquals(WORKING.replace("L2 changed", "l2"), s.text());
            assertEquals(tr("status.git.hunk.reverted"), s.status());
            assertEquals(WORKING, Files.readString(s.file), "an edit to the buffer, not a write");
            assertTrue(FxTestSupport.callOnFx(s.buffer::isDirty));
            assertArrayEquals(new int[] {6, 1, DELETED, 9, 1, ADDED}, s.marks(), "its mark is gone with it");

            FxTestSupport.runOnFx(() -> s.buffer.getArea().undo());
            assertEquals(WORKING, s.text(), "one undo brings the change back");

            s.caret(6);
            s.run("git.revertHunk");
            assertEquals(WORKING.replace("l5\nl7", "l5\nl6\nl7"), s.text(), "a deletion goes back above its mark");
            s.caret(10);
            s.run("git.revertHunk");
            assertEquals(WORKING.replace("l5\nl7", "l5\nl6\nl7").replace("added\n", ""), s.text());

            s.caret(0);
            String before = s.text();
            s.run("git.revertHunk");
            assertEquals(before, s.text());
            assertEquals(tr("status.git.hunk.none"), s.status());
        }
    }

    /** The hunk is on-disk lines 9; with two unsaved lines typed above, it is buffer line 11 that goes. */
    @Test
    void withUnsavedEditsTheRightLinesAreRevertedOrNothingIs(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);
            FxTestSupport.runOnFx(() -> s.buffer.getArea().insertText(0, "typed 1\ntyped 2\n"));
            s.async.awaitFx();
            assertArrayEquals(new int[] {4, 1, MODIFIED, 8, 1, DELETED, 11, 1, ADDED}, s.marks());
            Object minimap = FxTestSupport.field(s.buffer, "minimap");
            assertArrayEquals(
                    new int[] {4, 1, MODIFIED, 8, 1, DELETED, 11, 1, ADDED},
                    FxTestSupport.callOnFx(
                            () -> (int[]) FxTestSupport.call(minimap, "gitMarksForTest", new Class<?>[] {})),
                    "the minimap draws them where the gutter does");

            s.caret(11);
            s.run("git.revertHunk");
            assertEquals("typed 1\ntyped 2\n" + WORKING.replace("added\n", ""), s.text());

            // A rewrite the line map cannot follow: refuse rather than replace whatever is on those lines now.
            StringBuilder rewrite = new StringBuilder();
            for (int i = 0; i < 10_050; i++) {
                rewrite.append("line ").append(i).append('\n');
            }
            FxTestSupport.runOnFx(() -> s.buffer.getArea().replaceText(rewrite.toString()));
            s.async.awaitFx();
            assertTrue(FxTestSupport.callOnFx(() -> s.buffer.gitGutter().lost()));
            s.caret(4);
            s.run("git.revertHunk");
            assertEquals(rewrite.toString(), s.text());
            assertEquals(tr("status.git.hunk.cannotLocate"), s.status());
            s.run("git.nextChange");
            assertEquals(tr("status.git.hunk.cannotLocate"), s.status());
        }
    }

    @Test
    void stageHunkStagesOnlyThatChange(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);
            CountDownLatch staged = s.whenStatus(tr("status.diff.hunkStaged"));
            s.caret(6);
            s.run("git.stageHunk");
            async.await(staged, "hunk staged");

            assertEquals(
                    COMMITTED.replace("l6\n", ""),
                    s.repo.git("show", ":doc.txt").text());
            assertEquals(WORKING, Files.readString(s.file));

            // Staged in full: there is nothing left of it to stage, and the index is not touched again.
            s.settle();
            CountDownLatch already = s.whenStatus(tr("status.git.hunk.alreadyStaged"));
            s.caret(6);
            s.run("git.stageHunk");
            async.await(already, "already staged");
        }
    }

    @Test
    void stageHunkSavesUnsavedEditsFirstAndKeepsACrlfBlobCrlf(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED.replace("\n", "\r\n"), WORKING.replace("\n", "\r\n"));
            FxTestSupport.runOnFx(() -> s.buffer.getArea().insertText(0, "typed\n"));
            s.async.awaitFx();

            CountDownLatch staged = s.whenStatus(tr("status.diff.hunkStaged"));
            s.caret(3); // "L2 changed", one line further down than on disk
            s.run("git.stageHunk");
            async.await(staged, "hunk staged");

            assertFalse(FxTestSupport.callOnFx(s.buffer::isDirty), "saved through the normal save path");
            assertEquals(("typed\n" + WORKING).replace("\n", "\r\n"), Files.readString(s.file));
            assertEquals(
                    COMMITTED.replace("l2", "L2 changed").replace("\n", "\r\n"),
                    new String(s.repo.git("show", ":doc.txt").out(), StandardCharsets.UTF_8),
                    "only the change at the caret, with the blob's own line endings");
        }
    }

    @Test
    void stageHunkRefusesAnUnmergedFile(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitTestRepo repo = GitTestRepo.init(dir);
            repo.write("doc.txt", COMMITTED);
            repo.commitAll("first");
            String base = repo.git("rev-parse", "--abbrev-ref", "HEAD").text().strip();
            repo.git("checkout", "-q", "-b", "other");
            repo.write("doc.txt", COMMITTED.replace("l2", "theirs"));
            repo.commitAll("theirs");
            repo.git("checkout", "-q", base);
            Path file = repo.write("doc.txt", COMMITTED.replace("l2", "ours"));
            repo.commitAll("ours");
            repo.tryGit("merge", "other");
            String unmerged = repo.git("ls-files", "-u").text();
            assertFalse(unmerged.isBlank(), "precondition: the merge conflicted");

            Session s = new Session(async, repo, file);
            s.caret(2);
            s.run("git.stageHunk");
            s.settle();
            assertEquals(tr("status.git.hunk.cannotStageUnmerged"), s.status());
            assertEquals(unmerged, repo.git("ls-files", "-u").text(), "the merge stages are untouched");
        }
    }

    @Test
    void theChangeCardShowsBothSidesAndClosesOnEscapeNotOnScroll(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);
            OverlayHost overlay = FxTestSupport.field(s.fx.controller, "overlayHost");

            s.caret(2);
            s.run("git.peekChange");
            assertTrue(FxTestSupport.callOnFx(overlay::isShowing));
            GitHunkPopup popup = s.popup();
            assertNotNull(popup);
            TextArea oldText = FxTestSupport.field(popup, "oldText");
            TextArea newText = FxTestSupport.field(popup, "newText");
            assertEquals("l2", FxTestSupport.callOnFx(oldText::getText));
            assertEquals("L2 changed", FxTestSupport.callOnFx(newText::getText));
            assertFalse(FxTestSupport.callOnFx(oldText::isEditable));

            FxTestSupport.runOnFx(() -> s.buffer.getArea().scrollYBy(40));
            async.awaitFx();
            assertTrue(FxTestSupport.callOnFx(overlay::isShowing), "a scroll (or a layout) does not close it");

            FxTestSupport.runOnFx(() -> popup.card()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)));
            assertFalse(FxTestSupport.callOnFx(overlay::isShowing));

            // A pure deletion's mark is a bar like the others: clicking it opens its card.
            Node bar = FxTestSupport.callOnFx(() -> {
                s.buffer.getArea().getScene().getRoot().applyCss();
                s.buffer.getArea().getScene().getRoot().layout();
                return s.buffer.getArea().lookupAll(".git-change-bar").stream()
                        .filter(n -> n.getStyleClass().contains("git-deleted"))
                        .findFirst()
                        .orElse(null);
            });
            assertNotNull(bar, "the deleted-lines marker is in the gutter");
            FxTestSupport.runOnFx(() -> bar.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1,
                    1,
                    1,
                    1,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null)));
            async.awaitFx();
            assertTrue(FxTestSupport.callOnFx(overlay::isShowing));
            assertEquals("l6", FxTestSupport.callOnFx(oldText::getText));
            assertFalse(FxTestSupport.callOnFx(newText::isVisible), "a deletion has no new side");
            FxTestSupport.runOnFx(overlay::hide);
        }
    }

    @Test
    void theTabTakesItsFilesStatusColourUntilGitIsOff(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Session s = open(async, dir, COMMITTED, WORKING);
            EditorArea area = FxTestSupport.field(s.fx.controller, "editorArea");
            Tab tab = FxTestSupport.callOnFx(area::selectedTab);
            assertTrue(
                    tab.getStyleClass().contains("git-status-modified"),
                    tab.getStyleClass().toString());

            // Typing rebuilds the tab's header; the status stays, next to the unsaved marker.
            FxTestSupport.runOnFx(() -> s.buffer.getArea().insertText(0, "x"));
            async.awaitFx();
            assertTrue(tab.getStyleClass().containsAll(Arrays.asList("git-status-modified", "dirty")));
            long before = tab.getStyleClass().stream()
                    .filter(c -> c.startsWith("git-status-"))
                    .count();
            s.refresh();
            assertEquals(1, before);
            assertEquals(
                    1,
                    tab.getStyleClass().stream()
                            .filter(c -> c.startsWith("git-status-"))
                            .count(),
                    "a refresh with the same status adds nothing");

            s.fx.shared.getSettings().setGitSupport(false);
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(s.git, "applySupport"));
            assertFalse(tab.getStyleClass().stream().anyMatch(c -> c.startsWith("git-status-")));
        }
    }
}
