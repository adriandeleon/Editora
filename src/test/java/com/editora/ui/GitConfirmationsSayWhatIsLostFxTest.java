package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.git.GitFormat;
import com.editora.git.GitService;
import com.editora.git.GitService.LeftBehind;
import com.editora.git.GitStatus;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The destructive Git confirmations name what is actually destroyed: an untracked folder row is a folder with
 * files in it (V5, which are copied to Local History first), and a reset or a commit checkout says how many
 * commits it leaves behind (V9).
 */
@Tag("fx")
class GitConfirmationsSayWhatIsLostFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- V5: "Delete untracked" on a folder row ---------------------------------------------------------

    @Test
    void deletingAnUntrackedFolderSaysSoCountsItsFilesAndKeepsThemInLocalHistory(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write(".gitignore", "*.log\n");
        repo.write("tracked.txt", "tracked\n");
        repo.commitAll("base");
        Path x = repo.write("newdir/x.txt", "never added x\n");
        Path y = repo.write("newdir/sub/y.txt", "never added y\r\n");
        Path ignored = repo.write("newdir/build.log", "ignored: git clean leaves it\n");
        assertTrue(repo.git("status", "--porcelain").text().contains("?? newdir/\n"), "one row for the whole folder");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo.root, "main");
            String folderPrompt = tr("dialog.discard.untrackedFolder", "newdir/", 2);
            assertTrue(folderPrompt.contains("folder") && folderPrompt.contains("2 files"), folderPrompt);

            // Cancel: nothing is deleted and nothing is recorded.
            assertEquals(
                    folderPrompt, confirmationAfter(() -> coordinator.discardChanges(List.of(), List.of("newdir/"))));
            pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            settle(async, fx, coordinator);
            assertTrue(Files.exists(x) && Files.exists(y));
            assertNull(revisions(fx, x));

            // Confirm: the files are in Local History before git clean runs.
            CountDownLatch deleted = watchStatus(fx, tr("status.git.deleted", "newdir/"));
            assertEquals(
                    folderPrompt, confirmationAfter(() -> coordinator.discardChanges(List.of(), List.of("newdir/"))));
            pressDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(deleted, "untracked folder delete");

            assertFalse(Files.exists(x));
            assertFalse(Files.exists(y));
            assertTrue(Files.exists(ignored), "git clean -f keeps ignored files, and so the count left it out");
            assertDeletedCopy(fx, x, "never added x\n");
            assertDeletedCopy(fx, y, "never added y\r\n");
            assertNull(revisions(fx, ignored), "a file that is not deleted is not captured");
        }
    }

    @Test
    void aMixedSelectionCountsFilesNotRows(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path tracked = repo.write("tracked.txt", "base\n");
        repo.commitAll("base");
        Files.writeString(tracked, "working\n");
        repo.write("newdir/a.txt", "a\n");
        repo.write("newdir/b.txt", "b\n");
        Path single = repo.write("single.txt", "single\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo.root, "main");

            assertEquals(
                    tr("dialog.discard.untrackedWithFolders", 3, 1),
                    confirmationAfter(() -> coordinator.discardChanges(List.of(), List.of("newdir/", "single.txt"))));
            pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            settle(async, fx, coordinator);

            assertEquals(
                    tr("dialog.discard.mixedWithFolders", 1, 3, 1),
                    confirmationAfter(() ->
                            coordinator.discardChanges(List.of("tracked.txt"), List.of("newdir/", "single.txt"))));
            pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            settle(async, fx, coordinator);
            assertEquals("working\n", Files.readString(tracked));
            assertTrue(Files.exists(single));
        }
    }

    @Test
    void aSingleUntrackedFileIsAlsoKeptInLocalHistoryBeforeItIsDeleted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "tracked\n");
        repo.commitAll("base");
        Path draft = repo.write("draft.txt", "an afternoon of work\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo.root, "main");
            CountDownLatch deleted = watchStatus(fx, tr("status.git.deleted", "draft.txt"));

            assertEquals(
                    tr("dialog.discard.untracked", "draft.txt"),
                    confirmationAfter(() -> coordinator.discardChanges(List.of(), List.of("draft.txt"))));
            pressDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(deleted, "untracked file delete");

            assertFalse(Files.exists(draft));
            assertDeletedCopy(fx, draft, "an afternoon of work\n");
        }
    }

    // --- V9: reset and commit checkout ------------------------------------------------------------------

    /** A file no commit after the first touches: open in the editor, it keeps the window in the repository. */
    private static final String ANCHOR = "README.txt";

    /** main: first ← second ← third, with origin/main at second. */
    private record History(GitTestRepo repo, String first, String second, String third) {}

    private static History history(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "first\n");
        repo.write(ANCHOR, "never changes\n");
        repo.commitAll("first");
        String first = repo.git("rev-parse", "HEAD").text().strip();
        Files.writeString(file, "second\n");
        repo.commitAll("second");
        String second = repo.git("rev-parse", "HEAD").text().strip();
        Path remote = dir.resolve("remote.git");
        repo.git("init", "-q", "--bare", remote.toString());
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "-u", "origin", "main");
        Files.writeString(file, "third\n");
        repo.commitAll("third");
        String third = repo.git("rev-parse", "HEAD").text().strip();
        return new History(repo, first, second, third);
    }

    @Test
    void gitCountsWhatAResetOrCheckoutLeavesBehind(@TempDir Path dir) throws Exception {
        History h = history(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitService service = applyRepo(fx, h.repo().root, "main").service();

            // Reset to first: second and third leave main; origin/main still has second; only third is stranded.
            assertEquals(new LeftBehind(true, 2, true, 1, 1), leftBehind(async, service, h, h.first(), true));
            assertEquals(new LeftBehind(true, 1, true, 1, 1), leftBehind(async, service, h, h.second(), true));
            assertEquals(new LeftBehind(true, 0, false, 0, 0), leftBehind(async, service, h, h.third(), true));
            // A checkout leaves main where it is: nothing is stranded.
            assertEquals(0, leftBehind(async, service, h, h.first(), false).unreferenced());

            // Another branch at third keeps it alive through a reset.
            h.repo().git("branch", "keep");
            assertEquals(new LeftBehind(true, 2, true, 1, 0), leftBehind(async, service, h, h.first(), true));
            h.repo().git("branch", "-D", "keep");

            // Without an upstream every leaving commit is unpushed.
            h.repo().git("branch", "--unset-upstream");
            assertEquals(new LeftBehind(true, 2, false, 2, 1), leftBehind(async, service, h, h.first(), true));

            // Detached HEAD with a commit of its own: leaving it by checkout strands that commit.
            h.repo().git("checkout", "-q", "--detach");
            h.repo().write("work.txt", "made on a detached HEAD\n");
            h.repo().commitAll("detached work");
            assertEquals(1, leftBehind(async, service, h, h.first(), false).unreferenced());
            assertFalse(leftBehind(async, service, h, "-not-a-revision", true).known());
        }
    }

    @Test
    void hardResetNamesTheCommitsAndSoftAsksOnlyWhenTheyWouldBeStranded(@TempDir Path dir) throws Exception {
        History h = history(dir);
        Path root = h.repo().root;
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, root, "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            // The refresh after each command asks Git about the active file's folder.
            open(fx.controller, h.repo().root.resolve(ANCHOR));
            FxTestSupport.runOnFx(() -> windows.loadGitLog(null));
            String shortFirst = GitFormat.shortHash(h.first());
            String shortSecond = GitFormat.shortHash(h.second());
            LeftBehind toFirst = new LeftBehind(true, 2, true, 1, 1);

            // Hard: the old text spoke of uncommitted changes only.
            String hard = confirmationAfter(() -> windows.gitLogActions().reset(h.first(), "hard"));
            assertEquals(GitHeadMoveWarning.resetPrompt("hard", shortFirst, "main", root, toFirst), hard);
            assertTrue(hard.startsWith(tr("dialog.gitReset.hardConfirm", shortFirst, "main", root)));
            assertTrue(hard.contains("2 commits will no longer be on main"), hard);
            assertTrue(hard.contains("1 of them is not on the upstream"), hard);
            pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            settle(async, fx, coordinator);
            assertEquals(h.third(), head(h));

            // Soft and mixed used to run unasked. Third is on no other ref: ask, and Cancel changes nothing.
            for (String mode : List.of("soft", "mixed")) {
                assertEquals(
                        GitHeadMoveWarning.resetPrompt(mode, shortFirst, "main", root, toFirst),
                        confirmationAfter(() -> windows.gitLogActions().reset(h.first(), mode)));
                pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
                settle(async, fx, coordinator);
                assertEquals(h.third(), head(h), mode + " reset was cancelled");
            }

            // Once another ref holds the commits, a soft reset strands nothing and does not ask.
            h.repo().git("branch", "keep");
            CountDownLatch reset = watchStatus(fx, tr("status.git.reset", "soft", shortSecond));
            FxTestSupport.runOnFx(() -> windows.gitLogActions().reset(h.second(), "soft"));
            async.await(reset, "soft reset without a dialog");
            assertEquals(h.second(), head(h));
            assertEquals("third\n", Files.readString(root.resolve("work.txt")), "soft keeps the files");

            // Confirmed, a stranding soft reset runs.
            h.repo().git("reset", "-q", "--hard", h.third());
            h.repo().git("branch", "-D", "keep");
            CountDownLatch confirmedReset = watchStatus(fx, tr("status.git.reset", "soft", shortFirst));
            assertNotNull(confirmationAfter(() -> windows.gitLogActions().reset(h.first(), "soft")));
            pressDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(confirmedReset, "confirmed soft reset");
            assertEquals(h.first(), head(h));
        }
    }

    @Test
    void commitCheckoutSaysHeadIsDetachedAndAsksBeforeAbandoningDetachedCommits(@TempDir Path dir) throws Exception {
        History h = history(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, h.repo().root, "main");
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            // The refresh after each command asks Git about the active file's folder.
            open(fx.controller, h.repo().root.resolve(ANCHOR));
            FxTestSupport.runOnFx(() -> windows.loadGitLog(null));
            String shortSecond = GitFormat.shortHash(h.second());
            String shortFirst = GitFormat.shortHash(h.first());

            // From a branch nothing is abandoned: no question, and the result says where HEAD now is.
            String detached = tr("status.git.checkedOutDetached", shortSecond);
            assertTrue(detached.contains("detached HEAD"), detached);
            CountDownLatch checkedOut = watchStatus(fx, detached);
            FxTestSupport.runOnFx(() -> windows.gitLogActions().checkout(h.second()));
            async.await(checkedOut, "commit checkout");
            assertEquals(h.second(), head(h));
            assertEquals("", h.repo().git("branch", "--show-current").text().strip());

            // A commit made on the detached HEAD belongs to no branch; checking out elsewhere abandons it.
            h.repo().write("work.txt", "made on a detached HEAD\n");
            h.repo().commitAll("detached work");
            String orphan = head(h);
            String prompt = confirmationAfter(() -> windows.gitLogActions().checkout(h.first()));
            assertEquals(tr("dialog.gitCheckout.strandConfirm", shortFirst, 1), prompt);
            assertTrue(prompt.contains("1 commit made on it"), prompt);
            pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            settle(async, fx, coordinator);
            assertEquals(orphan, head(h), "cancelled: HEAD stays on the commit that has no branch");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static String head(History h) throws Exception {
        return h.repo().git("rev-parse", "HEAD").text().strip();
    }

    private static LeftBehind leftBehind(
            AsyncTestScope async, GitService service, History h, String target, boolean movesBranch) throws Exception {
        AtomicReference<LeftBehind> result = new AtomicReference<>();
        CountDownLatch counted = new CountDownLatch(1);
        service.commitsLeftBehind(h.repo().root, target, movesBranch, left -> {
            result.set(left);
            counted.countDown();
        });
        async.await(counted, "commit count");
        return result.get();
    }

    private static GitCoordinator applyRepo(FxWindowFixture fx, Path repo, String branch) throws Exception {
        GitCoordinator coordinator = FxTestSupport.field(fx.controller, "git");
        GitStatus status = new GitStatus(true, branch, null, 0, 0, List.of());
        FxTestSupport.runOnFx(() -> coordinator.applyState(
                new GitService.RepoState(repo.toAbsolutePath().normalize(), status, Map.of(), Map.of())));
        return coordinator;
    }

    private static void open(MainController controller, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                com.editora.editor.EditorBuffer buffer = new com.editora.editor.EditorBuffer();
                buffer.setPath(file);
                buffer.setContent(Files.readString(file));
                buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                FxTestSupport.call(
                        controller,
                        "addBuffer",
                        new Class<?>[] {com.editora.editor.EditorBuffer.class, boolean.class},
                        buffer,
                        true);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static CountDownLatch watchStatus(FxWindowFixture fx, String expected) throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label echo = FxTestSupport.field(statusBar, "echo");
            ChangeListener<String> listener = (observable, before, after) -> {
                if (expected.equals(after)) {
                    seen.countDown();
                }
            };
            echo.textProperty().addListener(listener);
        });
        return seen;
    }

    /** The confirmation this test is answering (dialogs left showing by other tests in the JVM are not it). */
    private Window confirmation;

    /**
     * Runs {@code action} on the FX thread without waiting for it — a confirmation it shows blocks it in a
     * nested event loop — and returns the content text of the confirmation that then appears.
     */
    private String confirmationAfter(Runnable action) throws Exception {
        List<Window> before = FxTestSupport.callOnFx(GitConfirmationsSayWhatIsLostFxTest::showingDialogs);
        javafx.application.Platform.runLater(action);
        return await(() -> FxTestSupport.callOnFx(() -> {
            for (Window window : showingDialogs()) {
                if (!before.contains(window)) {
                    confirmation = window;
                    return ((DialogPane) window.getScene().getRoot()).getContentText();
                }
            }
            return null;
        }));
    }

    private static List<Window> showingDialogs() {
        List<Window> dialogs = new ArrayList<>();
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.isShowing()
                    && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane) {
                dialogs.add(window);
            }
        }
        return dialogs;
    }

    private void pressDialog(ButtonBar.ButtonData buttonData) throws Exception {
        Window answered = confirmation;
        assertNotNull(answered, "a confirmation is showing");
        FxTestSupport.runOnFx(() -> {
            DialogPane pane = (DialogPane) answered.getScene().getRoot();
            Button button = (Button) pane.lookupButton(pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .orElseThrow());
            if (buttonData == ButtonBar.ButtonData.OK_DONE) {
                assertTrue(button.getStyleClass().contains("danger"), "the destructive button is styled as one");
            }
            button.fire();
        });
        await(() -> FxTestSupport.callOnFx(() -> answered.isShowing() ? null : Boolean.TRUE));
        confirmation = null;
    }

    private static <T> T await(Callable<T> probe) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            T value = probe.call();
            if (value != null) {
                return value;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for a confirmation dialog");
            }
            Thread.sleep(20);
        }
    }

    /** Lets a cancelled command finish doing nothing: Git worker, history worker and FX queue all idle. */
    private static void settle(AsyncTestScope async, FxWindowFixture fx, GitCoordinator coordinator) throws Exception {
        ExecutorService git = FxTestSupport.field(coordinator.service(), "exec");
        ExecutorService history = FxTestSupport.field(fx.shared.historyService(), "exec");
        for (int round = 0; round < 2; round++) {
            async.awaitFx();
            async.awaitWorker(git);
            async.awaitWorker(history);
        }
        async.awaitFx();
    }

    private static List<HistoryRevision> revisions(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> fx.shared.historyBucket("").get(PathKeys.normalizedKey(file)));
    }

    private static void assertDeletedCopy(FxWindowFixture fx, Path file, String content) throws Exception {
        List<HistoryRevision> revisions = revisions(fx, file);
        assertNotNull(revisions, "no Local History copy of " + file.getFileName());
        assertEquals(HistoryRevision.REASON_DELETE, revisions.get(0).reason());
        HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        assertEquals(content, blobs.get(revisions.get(0).sha256()));
    }
}
