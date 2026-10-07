package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DelegatingFileOperations;
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

/** Integration coverage for destructive Git commands crossing the editor's live-buffer/save boundary. */
@Tag("fx")
class GitDestructiveOperationsFxTest {

    private enum SaveTiming {
        BEFORE_MUTATION,
        AFTER_MUTATION_STARTS
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void checkoutReloadsCleanBuffersButPreservesDirtyAndDeletedOpenCopies(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path cleanFile = Files.writeString(repo.resolve("clean.txt"), "main clean\n");
        Path dirtyFile = Files.writeString(repo.resolve("dirty.txt"), "main dirty\n");
        Path deletedFile = Files.writeString(repo.resolve("deleted.txt"), "recoverable from main\n");
        commitAll(repo, "main");
        git(repo, "checkout", "-q", "-b", "other");
        Files.writeString(cleanFile, "other branch has longer clean text\n");
        Files.writeString(dirtyFile, "other branch disk text\n");
        Files.delete(deletedFile);
        commitAll(repo, "other");
        git(repo, "checkout", "-q", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer clean = open(fx.controller, cleanFile);
            EditorBuffer dirty = open(fx.controller, dirtyFile);
            EditorBuffer deleted = open(fx.controller, deletedFile);
            FxTestSupport.runOnFx(() -> dirty.replaceWholeDocument("unsaved editor copy\n"));
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch switched = watchStatus(fx, tr("status.switchedBranch", "other")::equals);

            FxTestSupport.runOnFx(() -> coordinator.checkoutBranch("other"));
            async.await(switched, "successful branch checkout");
            awaitBufferContent(clean, "other branch has longer clean text\n");

            assertEquals("other", git(repo, "branch", "--show-current").out().strip());
            assertEquals("other branch has longer clean text\n", FxTestSupport.callOnFx(clean::getContent));
            assertFalse(FxTestSupport.callOnFx(clean::isDirty));
            assertEquals("unsaved editor copy\n", FxTestSupport.callOnFx(dirty::getContent));
            assertTrue(FxTestSupport.callOnFx(dirty::isDirty), "the in-memory copy remains recoverable");
            assertEquals("other branch disk text\n", Files.readString(dirtyFile));
            assertFalse(Files.exists(deletedFile));
            assertEquals("recoverable from main\n", FxTestSupport.callOnFx(deleted::getContent));
            assertFalse(FxTestSupport.callOnFx(deleted::isDirty));
        }
    }

    @Test
    void refusedCheckoutKeepsTheWorkingTreeAndReportsFailure(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("conflict.txt"), "main version\n");
        commitAll(repo, "main");
        git(repo, "checkout", "-q", "-b", "other");
        Files.writeString(file, "other branch version\n");
        commitAll(repo, "other");
        git(repo, "checkout", "-q", "main");
        Files.writeString(file, "uncommitted local version\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            String failure = "Couldn't switch to other";
            CountDownLatch failed = watchStatus(fx, failure::equals);

            FxTestSupport.runOnFx(() -> coordinator.checkoutBranch("other"));
            async.await(failed, "checkout refusal feedback");
            dismissError(async);

            assertEquals("main", git(repo, "branch", "--show-current").out().strip());
            assertEquals("uncommitted local version\n", Files.readString(file));
            assertEquals("uncommitted local version\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void mixedDiscardPreservesTheIndexAndHandlesLiteralPathNames(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        List<String> tracked = new ArrayList<>(List.of("-dash.txt", "space name.txt", "wild[card].txt"));
        List<String> untracked = new ArrayList<>(List.of("-new.txt", "new space.txt", "new[card].txt"));
        if (supportsNewlineFileName(repo)) {
            tracked.add("line\nbreak.txt");
            untracked.add("new\nline.txt");
        }
        for (String name : tracked) {
            Files.writeString(repo.resolve(name), "base " + name + "\n");
        }
        commitAll(repo, "base");
        Files.writeString(repo.resolve(tracked.get(0)), "staged version\n");
        git(repo, "add", "--", tracked.get(0));
        for (String name : tracked) {
            Files.writeString(repo.resolve(name), "working " + name + "\n");
        }
        for (String name : untracked) {
            Files.writeString(repo.resolve(name), "untracked " + name + "\n");
        }

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer staged = open(fx.controller, repo.resolve(tracked.get(0)));
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch completed = watchStatus(fx, tr("status.git.deletedMany", untracked.size())::equals);
            CountDownLatch confirmed = new CountDownLatch(1);

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed));
                coordinator.discardChanges(tracked, untracked);
            });
            async.await(confirmed, "discard confirmation");
            async.await(completed, "mixed discard completion");
            async.awaitFx();
            awaitBufferContent(staged, "staged version\n");

            assertEquals("staged version\n", Files.readString(repo.resolve(tracked.get(0))));
            assertEquals(
                    "staged version\n", git(repo, "show", ":" + tracked.get(0)).out());
            assertEquals("staged version\n", FxTestSupport.callOnFx(staged::getContent));
            assertFalse(FxTestSupport.callOnFx(staged::isDirty));
            for (int i = 1; i < tracked.size(); i++) {
                assertEquals("base " + tracked.get(i) + "\n", Files.readString(repo.resolve(tracked.get(i))));
            }
            for (String name : untracked) {
                assertFalse(Files.exists(repo.resolve(name)), "clean must delete the literal path " + name);
            }
        }
    }

    @Test
    void cancellingMixedDiscardLeavesEveryPathUntouched(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path tracked = Files.writeString(repo.resolve("tracked.txt"), "base\n");
        commitAll(repo, "base");
        Files.writeString(tracked, "working\n");
        Path untracked = Files.writeString(repo.resolve("untracked.txt"), "new\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch cancelled = new CountDownLatch(1);

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled));
                coordinator.discardChanges(List.of("tracked.txt"), List.of("untracked.txt"));
            });
            async.await(cancelled, "discard cancellation");
            async.awaitFx();

            assertEquals("working\n", Files.readString(tracked));
            assertEquals("new\n", Files.readString(untracked));
            String status = git(repo, "status", "--porcelain=v1").out();
            assertTrue(status.contains(" M tracked.txt\n"));
            assertTrue(status.contains("?? untracked.txt\n"));
        }
    }

    @Test
    void stashPopConflictReportsFailureRetainsTheStashAndReloadsCleanBuffer(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("story.txt"), "base\n");
        commitAll(repo, "base");
        Files.writeString(file, "stashed version\n");
        git(repo, "stash", "push", "-q", "-m", "conflicting stash");
        Files.writeString(file, "current branch version\n");
        commitAll(repo, "current");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch failed = watchStatus(fx, tr("status.git.opFailed")::equals);

            FxTestSupport.runOnFx(coordinator::gitStashPop);
            async.await(failed, "stash conflict feedback");
            dismissError(async);

            assertTrue(git(repo, "status", "--porcelain=v1").out().contains("UU story.txt"));
            assertEquals(1, git(repo, "stash", "list").out().lines().count(), "a conflicted pop retains the stash");
            String conflict = Files.readString(file);
            assertTrue(conflict.contains("<<<<<<<"));
            assertEquals(conflict, FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @ParameterizedTest
    @EnumSource(SaveTiming.class)
    void pendingSaveCannotReverseSuccessfulCheckout(SaveTiming timing, @TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("pending.txt"), "main baseline\n");
        commitAll(repo, "main");
        git(repo, "checkout", "-q", "-b", "other");
        Files.writeString(file, "other branch result\n");
        commitAll(repo, "other");
        git(repo, "checkout", "-q", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService saveWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch releaseSave = new CountDownLatch(1);
            async.onClose(releaseSave::countDown);
            workflows.setDocumentWriter(blockingStagedWriter(staged, releaseSave));
            CountDownLatch switched = watchStatus(fx, tr("status.switchedBranch", "other")::equals);

            CountDownLatch releaseGit = holdGitWorkerIfNeeded(async, coordinator, timing);
            if (timing == SaveTiming.BEFORE_MUTATION) {
                startPendingSave(buffer, workflows);
                async.await(staged, "save staged before Git mutation");
                FxTestSupport.runOnFx(() -> coordinator.checkoutBranch("other"));
            } else {
                FxTestSupport.runOnFx(() -> coordinator.checkoutBranch("other"));
                startPendingSave(buffer, workflows);
                async.await(staged, "save staged after Git mutation started");
                releaseGit.countDown();
            }

            async.await(switched, "checkout with a pending editor save");
            releaseSave.countDown();
            async.awaitWorker(saveWorker);
            async.awaitFx();

            assertEquals("other branch result\n", Files.readString(file));
            assertEquals("obsolete pending editor save\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the uncommitted editor copy remains recoverable");
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    @Test
    void gitLogMutationReloadsCleanBuffersOnlyAfterTheCommandCompletes(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("history.txt"), "main contents\n");
        commitAll(repo, "main");
        git(repo, "checkout", "-q", "-b", "other");
        Files.writeString(file, "other contents\n");
        commitAll(repo, "other");
        String other = git(repo, "rev-parse", "HEAD").out().strip();
        git(repo, "checkout", "-q", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            applyRepo(fx, repo, "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            String completedMessage = "history reset complete";
            CountDownLatch completed = watchStatus(fx, completedMessage::equals);

            FxTestSupport.runOnFx(() -> windows.gitMutate(completedMessage, "reset", "--hard", other));
            async.await(completed, "Git Log reset completion");
            awaitBufferContent(buffer, "other contents\n");

            assertEquals("other contents\n", Files.readString(file));
            assertEquals("other contents\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- confirmation before history is thrown away ----------------------------------------------------

    @Test
    void hardResetIsConfirmedAndCancellingKeepsUncommittedWork(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("work.txt"), "first\n");
        commitAll(repo, "first");
        String first = git(repo, "rev-parse", "HEAD").out().strip();
        Files.writeString(file, "second\n");
        commitAll(repo, "second");
        String second = git(repo, "rev-parse", "HEAD").out().strip();
        Files.writeString(file, "uncommitted work\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            applyRepo(fx, repo, "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            String shortFirst = com.editora.git.GitFormat.shortHash(first);

            // Reset ▸ Hard sits directly under Soft and Mixed: it must ask, and Cancel must change nothing.
            // The confirmation follows a Git query (what the reset takes off the branch), so it is awaited.
            CountDownLatch cancelled = new CountDownLatch(1);
            String confirmation = GitHeadMoveWarning.resetPrompt(
                    "hard",
                    shortFirst,
                    "main",
                    repo.toAbsolutePath().normalize(),
                    new GitService.LeftBehind(true, 1, false, 1, 1));
            FxTestSupport.runOnFx(() -> windows.gitLogActions().reset(first, "hard"));
            awaitDialog(confirmation);
            FxTestSupport.runOnFx(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled));
            async.await(cancelled, "hard reset cancellation");
            async.awaitWorker(FxTestSupport.field(coordinatorOf(fx).service(), "exec"));
            async.awaitFx();
            assertEquals(second, git(repo, "rev-parse", "HEAD").out().strip());
            assertEquals("uncommitted work\n", Files.readString(file));

            // Confirmed, it runs.
            CountDownLatch confirmed = new CountDownLatch(1);
            CountDownLatch done = watchStatus(fx, tr("status.git.reset", "hard", shortFirst)::equals);
            FxTestSupport.runOnFx(() -> windows.gitLogActions().reset(first, "hard"));
            awaitDialog(confirmation);
            FxTestSupport.runOnFx(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed));
            async.await(confirmed, "hard reset confirmation");
            async.await(done, "hard reset completion");
            assertEquals(first, git(repo, "rev-parse", "HEAD").out().strip());
            assertEquals("first\n", Files.readString(file));
        }
    }

    @Test
    void softResetKeepsItsNoPromptBehaviour(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("work.txt"), "first\n");
        commitAll(repo, "first");
        String first = git(repo, "rev-parse", "HEAD").out().strip();
        Files.writeString(file, "second\n");
        commitAll(repo, "second");
        // Another ref still holds "second": the reset strands nothing, which is when soft does not ask
        // (a soft reset that would leave commits on no branch or tag is confirmed — see
        // GitConfirmationsSayWhatIsLostFxTest).
        git(repo, "branch", "keep");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            applyRepo(fx, repo, "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            CountDownLatch done =
                    watchStatus(fx, tr("status.git.reset", "soft", com.editora.git.GitFormat.shortHash(first))::equals);

            FxTestSupport.runOnFx(() -> windows.gitLogActions().reset(first, "soft"));
            async.await(done, "soft reset completion without a dialog");

            assertEquals(first, git(repo, "rev-parse", "HEAD").out().strip());
            assertEquals("second\n", Files.readString(file), "soft keeps the changes");
        }
    }

    @Test
    void dropStashIsConfirmedAndActsOnTheRepositoryItWasListedFrom(@TempDir Path dir) throws Exception {
        Path repo = initRepo(Files.createDirectory(dir.resolve("a")));
        Path other = initRepo(Files.createDirectory(dir.resolve("b")));
        for (Path r : List.of(repo, other)) {
            Path file = Files.writeString(r.resolve("story.txt"), "base\n");
            commitAll(r, "base");
            Files.writeString(file, "stashed\n");
            git(r, "stash", "push", "-q", "-m", "work in progress");
        }
        var entry = new com.editora.git.StashParser.StashEntry(0, "stash@{0}", "main", "work in progress");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            Path root = repo.toAbsolutePath().normalize();

            CountDownLatch cancelled = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled));
                coordinator.dropStash(root, entry);
            });
            async.await(cancelled, "drop stash cancellation");
            async.awaitWorker(FxTestSupport.field(coordinator.service(), "exec"));
            assertEquals(1, git(repo, "stash", "list").out().lines().count(), "Cancel keeps the stash");

            // While the confirmation is up the window moves to another repository (a tab switch): stash@{0}
            // means something else there, and must not be the one that is dropped.
            CountDownLatch confirmed = new CountDownLatch(1);
            CountDownLatch done = watchStatus(fx, tr("stash.dropped")::equals);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> {
                    coordinator.applyState(repoState(other, "main"));
                    pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed);
                });
                coordinator.dropStash(root, entry);
            });
            async.await(confirmed, "drop stash confirmation");
            async.await(done, "drop stash completion");

            assertEquals(0, git(repo, "stash", "list").out().lines().count());
            assertEquals(1, git(other, "stash", "list").out().lines().count(), "the other repository is untouched");
        }
    }

    // --- the Git Log acts on the repository it listed ---------------------------------------------------

    /** {@code repo} with commits first/second/third on main, and a linked worktree on {@code task} at "second". */
    private record TwoWorktrees(Path repo, Path worktree, String first, String second, String third, String task) {}

    private static TwoWorktrees twoWorktrees(Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("work.txt"), "first\n");
        commitAll(repo, "first");
        String first = git(repo, "rev-parse", "HEAD").out().strip();
        Files.writeString(file, "second\n");
        commitAll(repo, "second");
        String second = git(repo, "rev-parse", "HEAD").out().strip();
        Files.writeString(file, "third\n");
        commitAll(repo, "third");
        String third = git(repo, "rev-parse", "HEAD").out().strip();
        Path worktree = dir.resolve("task");
        git(repo, "worktree", "add", "-q", "-b", "task", worktree.toString(), second);
        Files.writeString(worktree.resolve("task-only.txt"), "committed on task\n");
        commitAll(worktree, "task work");
        String task = git(worktree, "rev-parse", "HEAD").out().strip();
        Files.writeString(worktree.resolve("work.txt"), "uncommitted work in the task worktree\n");
        return new TwoWorktrees(repo, worktree, first, second, third, task);
    }

    private static void assertWorktreeUntouched(TwoWorktrees t) throws Exception {
        assertEquals(t.task(), git(t.worktree(), "rev-parse", "HEAD").out().strip(), "the task branch did not move");
        assertEquals("task", git(t.worktree(), "branch", "--show-current").out().strip());
        assertEquals(
                "uncommitted work in the task worktree\n",
                Files.readString(t.worktree().resolve("work.txt")));
        assertTrue(Files.exists(t.worktree().resolve("task-only.txt")));
    }

    private static List<String> logRows(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            List<com.editora.git.GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
            return rows.stream().map(com.editora.git.GitLog.Entry::hash).toList();
        });
    }

    private static void awaitLogRows(FxWindowFixture fx, List<String> expected) throws Exception {
        for (int i = 0; i < 100 && !expected.equals(logRows(fx)); i++) {
            Thread.sleep(50);
        }
        assertEquals(expected, logRows(fx));
    }

    @Test
    void theGitLogDropsItsRowsAndReloadsWhenTheActiveRepositoryChanges(@TempDir Path dir) throws Exception {
        TwoWorktrees t = twoWorktrees(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, t.repo().resolve("work.txt"));
            GitCoordinator coordinator = applyRepo(fx, t.repo(), "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            FxTestSupport.runOnFx(() -> {
                windows.showGitLog();
                windows.loadGitLog(null);
            });
            awaitLogRows(fx, List.of(t.third(), t.second(), t.first()));
            async.awaitWorker(FxTestSupport.field(coordinator.service(), "exec"));
            async.awaitFx();
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.ListView<com.editora.git.GitLog.Entry> commits =
                        FxTestSupport.field(panel, "commits");
                commits.getSelectionModel().select(0);
                assertEquals(t.third(), panel.selectedHash());

                // A tab of the other worktree is activated. Its hashes are shared with this one, so a row left
                // on screen could be checked out or reset there: the old rows go at once…
                coordinator.applyState(repoState(t.worktree(), "task"));
                assertEquals(null, panel.selectedHash(), "no commit of the previous repository stays selected");
                List<com.editora.git.GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                assertTrue(rows.isEmpty(), "the previous repository's commits are no longer listed");
            });
            // …and the log, still open, lists the repository its actions now run in.
            awaitLogRows(fx, List.of(t.task(), t.second(), t.first()));
        }
    }

    @Test
    void hardResetFromTheLogRunsInTheRepositoryItWasConfirmedFor(@TempDir Path dir) throws Exception {
        TwoWorktrees t = twoWorktrees(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, t.repo(), "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            String shortFirst = com.editora.git.GitFormat.shortHash(t.first());
            CountDownLatch confirmed = new CountDownLatch(1);
            CountDownLatch done = watchStatus(fx, tr("status.git.reset", "hard", shortFirst)::equals);

            FxTestSupport.runOnFx(() -> windows.gitLogActions().reset(t.first(), "hard"));
            // The confirmation says which repository and branch are about to lose work — and which
            // commits: second and third leave main, and only the task branch still reaches second.
            awaitDialog(GitHeadMoveWarning.resetPrompt(
                    "hard",
                    shortFirst,
                    "main",
                    t.repo().toAbsolutePath().normalize(),
                    new GitService.LeftBehind(true, 2, false, 2, 1)));
            FxTestSupport.runOnFx(() -> {
                coordinator.applyState(repoState(t.worktree(), "task")); // the active repository changes
                pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed);
            });
            async.await(confirmed, "hard reset confirmation");
            async.await(done, "hard reset completion");

            assertEquals(t.first(), git(t.repo(), "rev-parse", "HEAD").out().strip());
            assertWorktreeUntouched(t);
        }
    }

    @Test
    void resetChosenFromThePaletteRunsInTheRepositoryTheCommitWasSelectedIn(@TempDir Path dir) throws Exception {
        TwoWorktrees t = twoWorktrees(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, t.repo(), "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            CountDownLatch chosen = new CountDownLatch(1);
            CountDownLatch done = watchStatus(
                    fx, tr("status.git.reset", "mixed", com.editora.git.GitFormat.shortHash(t.first()))::equals);

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> {
                    coordinator.applyState(repoState(t.worktree(), "task")); // while the mode prompt is up
                    pressDialog(ButtonBar.ButtonData.OK_DONE, chosen); // accepts the default, "mixed"
                });
                windows.promptGitReset(t.first());
            });
            async.await(chosen, "reset mode choice");
            // "third" would be left on no branch: a mixed reset that strands a commit is confirmed too.
            String confirmation = GitHeadMoveWarning.resetPrompt(
                    "mixed",
                    com.editora.git.GitFormat.shortHash(t.first()),
                    "main",
                    t.repo().toAbsolutePath().normalize(),
                    new GitService.LeftBehind(true, 2, false, 2, 1));
            awaitDialog(confirmation);
            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed));
            async.await(confirmed, "mixed reset confirmation");
            async.await(done, "mixed reset completion");

            assertEquals(t.first(), git(t.repo(), "rev-parse", "HEAD").out().strip());
            assertWorktreeUntouched(t);
        }
    }

    @Test
    void discardRunsInTheRepositoryItWasConfirmedFor(@TempDir Path dir) throws Exception {
        Path repo = initRepo(Files.createDirectory(dir.resolve("a")));
        Path other = initRepo(Files.createDirectory(dir.resolve("b")));
        for (Path r : List.of(repo, other)) {
            Path file = Files.writeString(r.resolve("same.txt"), "committed\n");
            commitAll(r, "base");
            Files.writeString(file, "local edit\n");
        }

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch confirmed = new CountDownLatch(1);
            CountDownLatch done = watchStatus(fx, tr("status.git.discarded", "same.txt")::equals);

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> {
                    coordinator.applyState(repoState(other, "main")); // the active repository changes mid-dialog
                    pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed);
                });
                coordinator.discardChanges(List.of("same.txt"), List.of());
            });
            async.await(confirmed, "discard confirmation");
            async.await(done, "discard completion");

            assertEquals("committed\n", Files.readString(repo.resolve("same.txt")), "the confirmed repository");
            assertEquals("local edit\n", Files.readString(other.resolve("same.txt")), "a same-named file elsewhere");
        }
    }

    @Test
    void aBranchNamedLikeAnOptionIsRefusedInsteadOfBecomingAForcedCheckout(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("work.txt"), "committed\n");
        commitAll(repo, "base");
        Files.writeString(file, "uncommitted work\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            CountDownLatch refused = watchStatus(fx, tr("status.git.unsafeRef", "-f")::equals);

            // `git checkout -f` would silently discard the working tree.
            FxTestSupport.runOnFx(() -> coordinator.checkoutBranch("-f"));
            async.await(refused, "unsafe branch name refusal");
            async.awaitWorker(FxTestSupport.field(coordinator.service(), "exec"));

            assertEquals("uncommitted work\n", Files.readString(file));
        }
    }

    // --- long-running commands are visible ---------------------------------------------------------------

    @Test
    void aRunningCommitIsShownAsBackgroundWorkUntilItFinishes(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GitTestRepo.windows(), "uses a /bin/sh hook and named pipes");
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("work.txt"), "first\n");
        commitAll(repo, "first");
        Path started = GitTestRepo.fifo(dir.resolve("hook-started"));
        Path release = GitTestRepo.fifo(dir.resolve("hook-release"));
        GitTestRepo.script(
                repo.resolve(".git/hooks/pre-commit"),
                "echo started > '" + started + "'\nread go < '" + release + "'\nexit 0");
        Files.writeString(file, "second\n");
        git(repo, "add", "work.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, "main");
            BackgroundTasks tasks = FxTestSupport.field(fx.controller, "backgroundTasks");
            CountDownLatch committed = watchStatus(fx, tr("status.committed")::equals);

            FxTestSupport.runOnFx(() -> coordinator.gitCommit("second"));
            assertEquals(List.of("started"), Files.readAllLines(started), "the commit is inside its hook");
            List<String> running = FxTestSupport.callOnFx(() ->
                    tasks.running().stream().map(BackgroundTasks.Task::label).toList());
            assertTrue(running.contains(tr("status.gitRunning", "git commit")), running.toString());

            async.start("release-hook", () -> Files.writeString(release, "go\n"));
            async.await(committed, "commit completion");
            async.awaitFx();
            List<String> after = FxTestSupport.callOnFx(() ->
                    tasks.running().stream().map(BackgroundTasks.Task::label).toList());
            assertFalse(after.contains(tr("status.gitRunning", "git commit")), after.toString());
        }
    }

    // --- gh pr checkout uses the same boundary as git checkout -------------------------------------------

    @Test
    void pullRequestCheckoutSupersedesAPendingEditorSave(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GitTestRepo.windows(), "uses a /bin/sh stand-in for gh");
        Path repo = initRepo(dir);
        Path file = Files.writeString(repo.resolve("pending.txt"), "main baseline\n");
        commitAll(repo, "main");
        git(repo, "checkout", "-q", "-b", "other");
        Files.writeString(file, "other branch result\n");
        commitAll(repo, "other");
        git(repo, "checkout", "-q", "main");
        // A stand-in gh: `pr checkout N` switches to the PR branch, exactly what the real one does.
        Path gh = GitTestRepo.script(
                dir.resolve("fake-gh"),
                "if [ \"$1\" = pr ] && [ \"$2\" = checkout ]; then exec git checkout -q other; fi\necho '[]'");
        org.junit.jupiter.api.Assumptions.assumeFalse(gh.toString().matches(".*\\s.*"), "the gh command is tokenized");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ExecutorService saveWorker = FxTestSupport.field(workflows, "autoSaveExecutor");
            applyRepo(fx, repo, "main");
            GitHubCoordinator github = FxTestSupport.field(fx.controller, "github");
            com.editora.github.GitHubService ghService = FxTestSupport.field(github, "service");
            ghService.setCommand(gh.toString());
            CountDownLatch staged = new CountDownLatch(1);
            CountDownLatch releaseSave = new CountDownLatch(1);
            async.onClose(releaseSave::countDown);
            workflows.setDocumentWriter(blockingStagedWriter(staged, releaseSave));
            CountDownLatch checkedOut = watchStatus(fx, tr("status.github.checkedOut", 7)::equals);

            startPendingSave(buffer, workflows);
            async.await(staged, "save staged before the PR checkout");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    github, "doCheckout", new Class<?>[] {Path.class, int.class}, repo.toAbsolutePath(), 7));
            async.await(checkedOut, "gh pr checkout");
            releaseSave.countDown();
            async.awaitWorker(saveWorker);
            async.awaitFx();

            assertEquals("other", git(repo, "branch", "--show-current").out().strip());
            assertEquals(
                    "other branch result\n",
                    Files.readString(file),
                    "the stale save must not overwrite the checked-out file");
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    private static GitCoordinator coordinatorOf(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "git");
    }

    private static GitService.RepoState repoState(Path repo, String branch) {
        return new GitService.RepoState(
                repo.toAbsolutePath().normalize(),
                new GitStatus(true, branch, null, 0, 0, List.of()),
                Map.of(),
                Map.of());
    }

    /**
     * Waits until the confirmation with this content text is on screen. One that follows a Git query is not
     * there in the turn that asked for it, and it is matched by its text because dialogs of other tests in
     * the same JVM can still be listed.
     */
    private static void awaitDialog(String content) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.isShowing()
                        && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane
                        && content.equals(pane.getContentText())) {
                    return true;
                }
            }
            return false;
        })) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no confirmation dialog saying: " + content);
            }
            Thread.sleep(20);
        }
    }

    /** Asserts that the confirmation on screen names the consequence the caller expects. */
    private static void assertDialogSays(String expected) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane) {
                assertEquals(expected, pane.getContentText());
                return;
            }
        }
        throw new AssertionError("no confirmation dialog is showing");
    }

    private static void awaitBufferContent(EditorBuffer buffer, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(FxTestSupport.callOnFx(buffer::getContent))) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, FxTestSupport.callOnFx(buffer::getContent));
    }

    private static CountDownLatch holdGitWorkerIfNeeded(
            AsyncTestScope async, GitCoordinator coordinator, SaveTiming timing) throws Exception {
        CountDownLatch release = new CountDownLatch(timing == SaveTiming.AFTER_MUTATION_STARTS ? 1 : 0);
        if (timing == SaveTiming.BEFORE_MUTATION) {
            return release;
        }
        CountDownLatch held = new CountDownLatch(1);
        async.onClose(release::countDown);
        ExecutorService worker = FxTestSupport.field(coordinator.service(), "exec");
        worker.submit(() -> {
            held.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out holding the Git worker");
            }
            return null;
        });
        async.await(held, "Git worker hold");
        return release;
    }

    private static void startPendingSave(EditorBuffer buffer, FileWorkflowCoordinator workflows) throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.replaceWholeDocument("obsolete pending editor save\n");
            assertTrue(workflows.save(buffer));
        });
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

    private static GitCoordinator applyRepo(FxWindowFixture fx, Path repo, String branch) throws Exception {
        GitCoordinator coordinator = FxTestSupport.field(fx.controller, "git");
        GitStatus status = new GitStatus(true, branch, null, 0, 0, List.of());
        FxTestSupport.runOnFx(() -> coordinator.applyState(
                new GitService.RepoState(repo.toAbsolutePath().normalize(), status, Map.of(), Map.of())));
        return coordinator;
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

    private static void dismissError(AsyncTestScope async) throws Exception {
        CountDownLatch dismissed = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, dismissed));
        async.await(dismissed, "Git error dialog");
        async.awaitFx();
    }

    private static void pressDialog(ButtonBar.ButtonData buttonData, CountDownLatch pressed) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .ifPresent(type -> {
                        pressed.countDown();
                        Button button = (Button) pane.lookupButton(type);
                        if (List.of(tr("dialog.discard"), tr("dialog.gitReset.hard"), tr("dialog.stashDrop"))
                                .contains(type.getText())) {
                            assertTrue(button.getStyleClass().contains("danger"));
                        }
                        button.fire();
                    });
        }
    }

    private static Path initRepo(Path dir) throws Exception {
        Path repo = Files.createDirectory(dir.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "editora-test@example.invalid");
        git(repo, "config", "user.name", "Editora Test");
        return repo;
    }

    private static boolean supportsNewlineFileName(Path repo) throws IOException {
        Path probe = repo.resolve("newline\nprobe.txt");
        try {
            Files.writeString(probe, "probe");
            return true;
        } catch (IOException unsupported) {
            return false;
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private static void commitAll(Path repo, String message) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", message);
    }

    private static GitResult git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(false)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + err + out);
        }
        return new GitResult(out, err);
    }

    private record GitResult(String out, String err) {}
}
