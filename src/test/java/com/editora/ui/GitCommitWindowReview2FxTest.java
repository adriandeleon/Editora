package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Commit window against real repositories, for what the second Git review found: a file added with
 * {@code git add -N} emptied by Discard, a staged new file in a repository with no commit, nested repositories,
 * partly staged files, unsaved edits, the Local History limit, Undo Last Commit after HEAD moved, a commit made in a
 * terminal, Amend across a moved HEAD and another repository, and what the commit keys say when nothing happens.
 */
@Tag("fx")
class GitCommitWindowReview2FxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void forgetTheSession() {
        GitCommitCoordinator.clearSessionForTest();
    }

    // --- U1, U11: files in the index but in no commit --------------------------------------------------------

    @Test
    void discardingAnIntentToAddFileDeletesItInsteadOfEmptyingIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path base = repo.write("base.txt", "base\n");
        repo.commitAll("base");
        Path ita = repo.write("ita.txt", "only copy of this work\n");
        repo.git("add", "-N", "ita.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(base);
            CountDownLatch done = w.watchStatus(tr("status.git.discarded", "ita.txt")::equals);

            javafx.application.Platform.runLater(() -> w.git.discardChanges(List.of("ita.txt"), List.of()));
            assertEquals(tr("dialog.discard.added", "ita.txt"), answerDialog(ButtonBar.ButtonData.OK_DONE));
            async.await(done, "the discard");

            assertFalse(Files.exists(ita), "deleted, as an untracked file would be — not left behind empty");
            assertEquals("", repo.git("status", "--porcelain=v1").text(), "and out of the index");
        }
    }

    @Test
    void revertingAStagedNewFileInARepositoryWithNoCommitDeletesIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("first.txt", "first\n");
        repo.git("add", "first.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            CountDownLatch done = w.watchStatus(tr("status.git.discarded", "first.txt")::equals);

            javafx.application.Platform.runLater(() -> w.git.gitRevertPath(file));
            assertEquals(tr("dialog.discard.added", "first.txt"), answerDialog(ButtonBar.ButtonData.OK_DONE));
            async.await(done, "the revert");

            assertFalse(Files.exists(file));
            assertEquals("", repo.git("status", "--porcelain=v1").text());
            assertFalse(dialogShowing(), "no fatal from restore --source=HEAD");
        }
    }

    // --- U12: nested repositories --------------------------------------------------------------------------

    @Test
    void anUntrackedFolderThatIsARepositoryIsNotReportedDeleted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path base = repo.write("base.txt", "base\n");
        repo.commitAll("base");
        Path nested = Files.createDirectory(repo.root.resolve("nested"));
        Files.writeString(nested.resolve("inner.txt"), "inner\n");
        repo.git("-C", nested.toString(), "init", "-q");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(base);
            CountDownLatch said = w.watchStatus(tr("status.git.nestedRepoOnly", "nested/")::equals);

            FxTestSupport.runOnFx(() -> w.git.discardChanges(List.of(), List.of("nested/")));
            async.await(said, "the nested repository named");

            assertFalse(dialogShowing(), "nothing to confirm");
            assertTrue(Files.exists(nested.resolve("inner.txt")));
        }
    }

    @Test
    void stagingAFileOfANestedRepositoryFromTheTreeStagesItThere(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path base = repo.write("base.txt", "base\n");
        repo.commitAll("base");
        Path nested = Files.createDirectory(repo.root.resolve("nested"));
        repo.git("-C", nested.toString(), "init", "-q");
        // A path no menu has asked about: the nearest repository has not been looked at.
        Path inner = Files.writeString(nested.resolve("never-opened-" + System.nanoTime() + ".txt"), "inner\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(base);
            String name = inner.getFileName().toString();
            CountDownLatch staged = w.watchStatus(tr("status.git.staged", name)::equals);

            FxTestSupport.runOnFx(() -> w.git.gitStagePath(inner));
            async.await(staged, "the stage");

            assertEquals(
                    name,
                    repo.git("-C", nested.toString(), "diff", "--cached", "--name-only")
                            .text()
                            .strip());
            assertEquals("", repo.git("diff", "--cached", "--name-only").text().strip(), "the outer index untouched");
        }
    }

    // --- U17, U5, U8: what the Discard question says --------------------------------------------------------

    @Test
    void discardingAPartlyStagedFileSaysItsStagedChangesStay(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "one\n");
        repo.commitAll("base");
        repo.write("a.txt", "two\n");
        repo.git("add", "a.txt");
        repo.write("a.txt", "three\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            javafx.application.Platform.runLater(() -> w.git.discardChanges(List.of("a.txt"), List.of()));
            assertEquals(tr("dialog.discard.unstagedOnly", "a.txt"), answerDialog(ButtonBar.ButtonData.CANCEL_CLOSE));
        }
    }

    @Test
    void discardingAFileWithUnsavedEditsSaysSoAndRevertsTheBuffer(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "base\n");
        repo.commitAll("base");
        repo.write("a.txt", "changed on disk\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("typed, not saved\n"));
            w.clearStatus();
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await(
                    "the change in the status", () -> !w.git.status().files().isEmpty());

            javafx.application.Platform.runLater(() -> w.git.discardChanges(List.of("a.txt"), List.of()));
            assertEquals(
                    tr("dialog.discard.tracked", "a.txt") + "\n\n" + tr("dialog.discard.unsavedEdits", 1),
                    answerDialog(ButtonBar.ButtonData.OK_DONE));

            GitFeatureFx.await(
                    "the buffer back to the last commit",
                    () -> "base\n".equals(buffer.getArea().getText()) && !buffer.isDirty());
            assertEquals("base\n", Files.readString(file));
        }
    }

    @Test
    void theLocalHistoryLimitIsSaidForFilesListedOneByOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path base = repo.write("base.txt", "base\n");
        repo.commitAll("base");
        List<String> untracked = new ArrayList<>();
        for (int i = 0; i <= GitUntrackedDelete.MAX_HISTORY_CAPTURES; i++) {
            repo.write("u" + i + ".txt", "x\n");
            untracked.add("u" + i + ".txt");
        }

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.fx.shared.getSettings().setLocalHistory(true);
            w.open(base);
            javafx.application.Platform.runLater(() -> w.git.discardChanges(List.of(), untracked));
            String prompt = answerDialog(ButtonBar.ButtonData.CANCEL_CLOSE);

            assertTrue(
                    prompt.endsWith(tr("dialog.discard.historyLimit", GitUntrackedDelete.MAX_HISTORY_CAPTURES)),
                    prompt);
            assertTrue(Files.exists(repo.root.resolve("u0.txt")), "declined: nothing deleted");
        }
    }

    // --- U9: Undo Last Commit undoes the commit it named ----------------------------------------------------

    @Test
    void undoLastCommitUndoesNothingWhenHeadMovedWhileItAsked(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.write("story.txt", "second\n");
        repo.commitAll("second");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            String shortHash = repo.git("rev-parse", "--short", "HEAD").text().strip();
            CountDownLatch refused = w.watchStatus(tr("status.git.undoCommit.moved", shortHash)::equals);

            javafx.application.Platform.runLater(() -> w.git.commits().undoLastCommit());
            GitFeatureFx.await("the question", GitCommitWindowReview2FxTest::dialogShowing);
            repo.write("story.txt", "third, from a terminal\n");
            repo.commitAll("third");
            answerDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(refused, "the refusal");

            assertEquals("third", repo.git("log", "-1", "--format=%s").text().strip(), "the new commit stays");
            assertEquals("3", repo.git("rev-list", "--count", "HEAD").text().strip());
        }
    }

    // --- U2, U3: changes Editora did not make through its Git commands --------------------------------------

    @Test
    void aCommitMadeInATerminalRefreshesTheCommitWindow(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "base\n");
        repo.commitAll("base");
        repo.write("a.txt", "changed\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitFeatureFx.await(
                    "the change listed", () -> !w.git.status().files().isEmpty());
            int before = FxTestSupport.callOnFx(() -> w.git.externalRefreshesForTest);
            // As if long after the window's own commands, so the watcher does not take it for their echo.
            FxTestSupport.runOnFx(w.git::forgetOwnMutationsForTest);

            repo.commitAll("from a terminal");

            GitFeatureFx.await(
                    "the refresh the commit causes",
                    () -> w.git.externalRefreshesForTest > before
                            && w.git.status().files().isEmpty());
        }
    }

    @Test
    void movingATrackedFileInTheProjectTreeRefreshesGit(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "base\n");
        Path other = repo.write("b.txt", "other\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(other);
            GitFeatureFx.await(
                    "a clean status",
                    () -> w.git.status().isRepo() && w.git.status().files().isEmpty());
            Path moved = Files.move(file, repo.root.resolve("c.txt"));

            FxTestSupport.runOnFx(() -> w.fx.controller.onProjectFileRenamed(file, moved));

            GitFeatureFx.await(
                    "the move in the status", () -> w.git.status().files().size() == 2);
        }
    }

    // --- U4: Amend and the message across a moved HEAD and another repository -------------------------------

    @Test
    void amendFollowsAMovedHeadWithAnUntouchedMessageAndSwitchesOffWithATypedOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            FxTestSupport.runOnFx(() -> check(panel).fire());
            GitFeatureFx.await(
                    "the prefilled message", () -> "base".equals(message(panel).getText()));

            repo.write("story.txt", "second\n");
            repo.commitAll("second");
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await(
                    "the new last commit's message",
                    () -> message(panel).getText().startsWith("second"));
            assertTrue(FxTestSupport.callOnFx(() -> check(panel).isSelected()), "still amending — the new commit");

            FxTestSupport.runOnFx(() -> message(panel).setText("my own words"));
            repo.write("story.txt", "third\n");
            repo.commitAll("third");
            CountDownLatch off = w.watchStatus(tr(
                    "status.git.amendOffHeadMoved",
                    repo.git("rev-parse", "--short", "HEAD").text().strip())::equals);
            FxTestSupport.runOnFx(w.git::refresh);
            async.await(off, "Amend switched off");

            assertFalse(FxTestSupport.callOnFx(() -> check(panel).isSelected()));
            assertEquals(
                    "my own words", FxTestSupport.callOnFx(() -> message(panel).getText()));
        }
    }

    @Test
    void aTypedMessageStaysWithItsRepository(@TempDir Path dir) throws Exception {
        GitTestRepo one = GitTestRepo.init(Files.createDirectory(dir.resolve("one")));
        Path a = one.write("a.txt", "a\n");
        one.commitAll("base");
        GitTestRepo two = GitTestRepo.init(Files.createDirectory(dir.resolve("two")));
        Path b = two.write("b.txt", "b\n");
        two.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(a);
            GitPanel panel = showCommitWindow(w);
            FxTestSupport.runOnFx(() -> message(panel).setText("for the first repository"));

            w.open(b);
            GitFeatureFx.await("the second repository", () -> two.root.equals(w.git.repoRoot()));
            GitFeatureFx.await("an empty box", () -> message(panel).getText().isEmpty());

            w.open(a);
            GitFeatureFx.await(
                    "the first repository's message back",
                    () -> "for the first repository".equals(message(panel).getText()));
        }
    }

    /**
     * The Commit window is built on first use, after the repository's status was applied, and opening it reads
     * no status: the first switch away must still move the message aside (it was carried into the other
     * repository; on macOS, where git is slower, the test above hit it every time).
     */
    @Test
    void aMessageTypedBeforeAnyRefreshStaysWithItsRepository(@TempDir Path dir) throws Exception {
        GitTestRepo one = GitTestRepo.init(Files.createDirectory(dir.resolve("one")));
        Path a = one.write("a.txt", "a\n");
        one.commitAll("base");
        GitTestRepo two = GitTestRepo.init(Files.createDirectory(dir.resolve("two")));
        Path b = two.write("b.txt", "b\n");
        two.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(a);
            GitFeatureFx.await("the first repository", () -> one.root.equals(w.git.repoRoot()));
            FxTestSupport.runOnFx(w.git::gitCommitFocus); // builds the panel; no refresh
            GitPanel panel = FxTestSupport.field(w.fx.controller, "gitPanel");
            GitFeatureFx.await(
                    "the Commit window",
                    () -> panel.isOnScreen() && panel.getChildren().size() > 1);
            FxTestSupport.runOnFx(() -> message(panel).setText("for the first repository"));

            w.open(b);
            GitFeatureFx.await("the second repository", () -> two.root.equals(w.git.repoRoot()));
            GitFeatureFx.await("an empty box", () -> message(panel).getText().isEmpty());

            w.open(a);
            GitFeatureFx.await(
                    "the first repository's message back",
                    () -> "for the first repository".equals(message(panel).getText()));
        }
    }

    // --- U15: why a commit key did nothing --------------------------------------------------------------------

    @Test
    void theCommitKeysSayWhyNothingWasCommitted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "base\n");
        repo.commitAll("base");
        repo.write("a.txt", "staged\n");
        repo.git("add", "a.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the staged file", () -> !commitButton(panel).isDisable());

            CountDownLatch typeFirst = w.watchStatus(tr("status.git.commitNeedsMessage")::equals);
            FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(
                    message(panel),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, false)));
            async.await(typeFirst, "the key's reason");

            w.clearStatus();
            CountDownLatch again = w.watchStatus(tr("status.git.commitNeedsMessage")::equals);
            FxTestSupport.runOnFx(() -> w.git.commits().commitAndPush());
            async.await(again, "Commit and Push's reason: a message, not 'nothing to commit'");
        }
    }

    // --- U18: a merge's message replaces an untouched commit template ---------------------------------------

    @Test
    void aMergesMessageReplacesTheUntouchedTemplate(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("story.txt", "feature version\n");
        repo.commitAll("feature");
        repo.git("checkout", "-q", "main");
        repo.write("story.txt", "main version\n");
        repo.commitAll("main change");
        Path template = Files.writeString(dir.resolve("template.txt"), "\n# Why:\n");
        repo.git("config", "commit.template", template.toString());

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await(
                    "the template in the box", () -> message(panel).getText().contains("# Why:"));

            repo.tryGit("merge", "feature"); // conflicts: the merge waits, with its prepared message
            FxTestSupport.runOnFx(w.git::refresh);

            GitFeatureFx.await(
                    "the merge's message", () -> message(panel).getText().startsWith("Merge branch 'feature'"));
        }
    }

    // --- U14: the Commit window's row commands need the window --------------------------------------------

    @Test
    void discardSelectedDoesNothingWhileTheCommitWindowIsClosed(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("a.txt", "base\n");
        repo.commitAll("base");
        repo.write("a.txt", "changed\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the row", () -> tree(panel).getRoot() != null);
            FxTestSupport.runOnFx(() -> {
                select(panel, "a.txt");
                w.fx.controller.toolWindows.close(w.fx.controller.commitToolWindow);
            });
            GitFeatureFx.await(
                    "the window closed", () -> !w.fx.controller.toolWindows.isOpen(w.fx.controller.commitToolWindow));

            CountDownLatch said = w.watchStatus(tr("status.git.nothingToDiscardSelected")::equals);
            FxTestSupport.runOnFx(() -> w.fx.controller.registry.run("git.discardSelected"));
            async.await(said, "the refusal");
            assertFalse(dialogShowing(), "a hidden selection is never discarded");
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static GitPanel showCommitWindow(GitFeatureFx w) throws Exception {
        GitPanel panel = FxTestSupport.field(w.fx.controller, "gitPanel");
        FxTestSupport.runOnFx(() -> {
            w.git.gitCommitFocus();
            w.git.refresh();
        });
        GitFeatureFx.await(
                "the Commit window",
                () -> panel.isOnScreen() && panel.getChildren().size() > 1);
        return panel;
    }

    private static TextArea message(GitPanel panel) {
        return FxTestSupport.field(panel, "message");
    }

    private static CheckBox check(GitPanel panel) {
        return FxTestSupport.field(panel, "amendCheck");
    }

    private static Button commitButton(GitPanel panel) {
        return FxTestSupport.field(panel, "commitButton");
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(GitPanel panel) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    private static void select(GitPanel panel, String path) {
        TreeView<Object> tree = tree(panel);
        for (TreeItem<Object> group : tree.getRoot().getChildren()) {
            for (TreeItem<Object> row : group.getChildren()) {
                if (String.valueOf(row.getValue()).contains("path=" + path + ",")) {
                    tree.getSelectionModel().clearSelection();
                    tree.getSelectionModel().select(row);
                    return;
                }
            }
        }
        throw new AssertionError("no row for " + path);
    }

    private static boolean dialogShowing() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
                return true;
            }
        }
        return false;
    }

    /** Waits for a dialog, presses its button of {@code buttonData} and returns the dialog's text. */
    private static String answerDialog(ButtonBar.ButtonData buttonData) throws Exception {
        GitFeatureFx.await("the dialog", GitCommitWindowReview2FxTest::dialogShowing);
        AtomicReference<String> prompt = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                    continue;
                }
                pane.getButtonTypes().stream()
                        .filter(type -> type.getButtonData() == buttonData)
                        .findFirst()
                        .ifPresent(type -> {
                            prompt.set(pane.getContentText());
                            ((Button) pane.lookupButton(type)).fire();
                        });
            }
        });
        assertTrue(prompt.get() != null, "the dialog has a " + buttonData + " button");
        return prompt.get();
    }
}
