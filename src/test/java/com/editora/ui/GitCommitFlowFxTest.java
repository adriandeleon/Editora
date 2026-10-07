package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage / unstage / commit / discard against a real repository, through the window's Git coordinator. */
@Tag("fx")
class GitCommitFlowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- W3: git only sees the saved copy ---------------------------------------------------------------

    @Test
    void stagingAFileSavesItsUnsavedBufferFirst(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "committed\n");
        Path other = repo.write("other.txt", "committed\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            EditorBuffer bystander = open(fx.controller, other);
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("edited in the editor\n");
                bystander.replaceWholeDocument("still being written\n");
            });
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch staged = watchStatus(fx, tr("status.git.staged", "work.txt")::equals);

            FxTestSupport.runOnFx(() -> git.gitStagePaths(List.of("work.txt")));
            async.await(staged, "stage completion");

            assertEquals("edited in the editor\n", Files.readString(file), "the buffer was written before git add");
            assertEquals("edited in the editor\n", repo.git("show", ":work.txt").text(), "and that is what is staged");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("committed\n", Files.readString(other), "a file that is not being staged is left alone");
            assertTrue(FxTestSupport.callOnFx(bystander::isDirty));
        }
    }

    @Test
    void committingSavesEveryUnsavedBufferOfTheRepository(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "committed\n");
        repo.commitAll("base");
        repo.write("staged.txt", "new\n");
        repo.git("add", "staged.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("typed but never saved\n"));
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Boolean> committed = new AtomicReference<>();

            FxTestSupport.runOnFx(() -> git.gitCommit("second", ok -> {
                committed.set(ok);
                done.countDown();
            }));
            async.await(done, "commit completion");

            assertEquals(Boolean.TRUE, committed.get());
            assertEquals("typed but never saved\n", Files.readString(file), "nothing is left only in memory");
            assertEquals(
                    " M work.txt\n",
                    repo.git("status", "--porcelain=v1").text(),
                    "the edit is on disk and visibly uncommitted, instead of silently missing from the commit");
        }
    }

    @Test
    void aBufferThatCannotBeSavedStopsTheStage(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "committed\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            GitCoordinator git = activate(async, fx, repo.root);
            // A buffer whose load was cut short must never be written (SaveRefusal): the save is refused.
            FxTestSupport.runOnFx(() -> {
                buffer.replaceWholeDocument("half a file\n");
                buffer.setTruncatedLoad(true);
            });
            CountDownLatch refused = watchStatus(fx, tr("status.git.unsavedFailed", "work.txt")::equals);

            FxTestSupport.runOnFx(() -> git.gitStagePaths(List.of("work.txt")));
            async.await(refused, "the reason the stage did not run");
            async.awaitWorker(FxTestSupport.field(git.service(), "exec"));

            assertEquals("committed\n", Files.readString(file));
            assertEquals("", repo.git("status", "--porcelain=v1").text(), "nothing was staged");
        }
    }

    // --- W4: a staged rename is two index changes -------------------------------------------------------

    @Test
    void unstagingARenamedFileLeavesNothingStaged(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a file long enough to be detected as renamed\n");
        repo.commitAll("base");
        repo.git("mv", "a.txt", "b.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch unstaged = watchStatus(fx, tr("status.git.unstaged", "b.txt")::equals);

            FxTestSupport.runOnFx(() -> git.gitUnstagePaths(List.of("b.txt"))); // what the Commit window sends
            async.await(unstaged, "unstage completion");

            // Before: "D  a.txt" stayed staged, so the next commit recorded only the deletion.
            assertEquals(
                    " D a.txt\n?? b.txt\n", repo.git("status", "--porcelain=v1").text());
        }
    }

    // --- W5: discard looks at the file's status --------------------------------------------------------

    @Test
    void discardingAStagedOnlyChangeReallyRevertsTheFile(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "committed\n");
        repo.commitAll("base");
        Files.writeString(file, "staged change\n");
        repo.git("add", "work.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, file);
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch done = watchStatus(fx, tr("status.git.discarded", "work.txt")::equals);
            CountDownLatch confirmed = new CountDownLatch(1);
            AtomicReference<String> prompt = new AtomicReference<>();

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed, prompt));
                git.gitDiscardActiveFile();
            });
            async.await(confirmed, "discard confirmation");
            async.await(done, "discard completion");

            assertEquals(tr("dialog.discard.toHead", "work.txt"), prompt.get(), "the prompt says staged changes go");
            assertEquals("committed\n", Files.readString(file), "checkout -- left the staged text in place");
            assertEquals("", repo.git("status", "--porcelain=v1").text());
        }
    }

    @Test
    void discardingAnUntrackedActiveFileDeletesItInsteadOfFailingOnAPathspec(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "x\n");
        repo.commitAll("base");
        Path fresh = repo.write("fresh.txt", "new\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, fresh);
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch done = watchStatus(fx, tr("status.git.deleted", "fresh.txt")::equals);
            CountDownLatch confirmed = new CountDownLatch(1);
            AtomicReference<String> prompt = new AtomicReference<>();

            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed, prompt));
                git.gitDiscardActiveFile();
            });
            async.await(confirmed, "delete confirmation");
            async.await(done, "delete completion");

            assertEquals(tr("dialog.discard.untracked", "fresh.txt"), prompt.get());
            assertFalse(Files.exists(fresh));
        }
    }

    @Test
    void discardRefusesAnUnmergedFileAndSaysNothingToDoForACleanOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path story = repo.write("story.txt", "base\n");
        Path clean = repo.write("clean.txt", "clean\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "other");
        Files.writeString(story, "theirs\n");
        repo.commitAll("theirs");
        repo.git("checkout", "-q", "main");
        Files.writeString(story, "ours\n");
        repo.commitAll("ours");
        assertTrue(repo.tryGit("merge", "other").exit() != 0, "precondition: the merge conflicts");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, clean);
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);
            String conflicted = Files.readString(story);

            CountDownLatch refused = watchStatus(fx, tr("status.git.discardConflict", "story.txt")::equals);
            FxTestSupport.runOnFx(git::gitDiscardActiveFile);
            async.await(refused, "the refusal");
            // The Commit window's own Discard reaches the same refusal (its rows used to offer it).
            CountDownLatch refusedAgain = watchStatus(fx, tr("status.git.discardConflict", "story.txt")::equals);
            FxTestSupport.runOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                FxTestSupport.<Label>field(statusBar, "echo").setText("");
                git.discardChanges(List.of("story.txt"), List.of());
            });
            async.await(refusedAgain, "the refusal from the Commit window");

            CountDownLatch nothing = watchStatus(fx, tr("status.git.nothingToDiscard", "clean.txt")::equals);
            FxTestSupport.runOnFx(() -> git.gitRevertPath(clean));
            async.await(nothing, "nothing to discard");

            assertEquals(conflicted, Files.readString(story), "the conflict is untouched");
            assertTrue(repo.git("status", "--porcelain=v1").text().contains("UU story.txt"));
            assertNoDialog();
        }
    }

    @Test
    void discardingAFileOutsideTheActiveRepositoryReportsItInsteadOfThrowing(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tracked.txt", "x\n");
        repo.commitAll("base");
        Path outside = Files.writeString(
                Files.createDirectory(dir.resolve("elsewhere")).resolve("note.txt"), "n\n");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                GitPathScope.nearestRepository(outside) == null, "the temp folder is itself inside a repository");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, outside);
            settleGit(async, fx);
            GitCoordinator git = activate(async, fx, repo.root); // a root that is stale for the active file
            CountDownLatch reported = watchStatus(fx, tr("status.notARepo")::equals);

            FxTestSupport.runOnFx(git::gitDiscardActiveFile); // used to throw a NullPointerException
            async.await(reported, "not-a-repository report");

            assertEquals("n\n", Files.readString(outside));
            assertNoDialog();
        }
    }

    // --- W17: the repository of the path that was clicked ----------------------------------------------

    @Test
    void stagingAFileOfANestedRepositoryStagesItThere(@TempDir Path dir) throws Exception {
        GitTestRepo outer = GitTestRepo.init(dir);
        outer.write("outer.txt", "x\n");
        outer.commitAll("base");
        Path nestedParent = Files.createDirectories(outer.root.resolve("vendor"));
        GitTestRepo nested = GitTestRepo.init(nestedParent); // vendor/repo
        Path inner = nested.write("inner.txt", "first\n");
        nested.commitAll("inner base");
        Files.writeString(inner, "changed\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, outer.root.resolve("outer.txt"));
            settleGit(async, fx);
            GitCoordinator git = activate(async, fx, outer.root); // the active repository is the outer one
            assertEquals(GitPathScope.OTHER, FxTestSupport.callOnFx(() -> git.scopeOf(inner)));
            CountDownLatch staged = watchStatus(fx, tr("status.git.staged", "inner.txt")::equals);

            // The Project tree's Git > Stage on that file. It used to compute "vendor/repo/inner.txt" against
            // the outer root — a pathspec the outer repository rejects.
            FxTestSupport.runOnFx(() -> git.gitStagePath(inner));
            async.await(staged, "stage in the nested repository");

            assertEquals(
                    "M  inner.txt\n", nested.git("status", "--porcelain=v1").text());
            settleGit(async, fx); // the refresh that follows a mutation
            assertEquals(outer.root, FxTestSupport.callOnFx(git::repoRoot), "the active repository is unchanged");

            // An action written against the active root (Compare with HEAD…) sees the clicked path's root.
            AtomicReference<Path> seen = new AtomicReference<>();
            CountDownLatch ran = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> git.withRepositoryOf(inner, () -> {
                seen.set(git.repoRoot());
                ran.countDown();
            }));
            async.await(ran, "action in the nested repository");
            assertEquals(nested.root, seen.get());
            assertEquals(outer.root, FxTestSupport.callOnFx(git::repoRoot), "and only for its duration");

            // File history lists the ACTIVE repository, so that action opens the file first: the Git UI
            // follows the active tab, and the action runs once the nested repository is the active one.
            AtomicReference<Path> active = new AtomicReference<>();
            CountDownLatch activated = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> git.activatingRepositoryOf(inner, () -> {
                active.set(git.repoRoot());
                activated.countDown();
            }));
            async.await(activated, "action after activating the nested repository");
            assertEquals(nested.root, active.get());
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** Lets a Git refresh in flight finish and reach the FX thread. */
    private static void settleGit(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        async.awaitFx();
        async.awaitWorker(FxTestSupport.field(git.service(), "exec"));
        async.awaitFx();
    }

    /** Applies the repository's real status, as a refresh for a tab inside it would. */
    private static GitCoordinator activate(AsyncTestScope async, FxWindowFixture fx, Path root) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        CountDownLatch applied = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> git.service().status(root, state -> {
            git.applyState(state);
            applied.countDown();
        }));
        async.await(applied, "repository state");
        return git;
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

    private static void assertNoDialog() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                assertFalse(
                        window.getScene() != null && window.getScene().getRoot() instanceof DialogPane,
                        "no confirmation or error dialog was shown");
            }
        });
    }

    private static void pressDialog(
            ButtonBar.ButtonData buttonData, CountDownLatch pressed, AtomicReference<String> prompt) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .ifPresent(type -> {
                        prompt.set(pane.getContentText());
                        pressed.countDown();
                        ((Button) pane.lookupButton(type)).fire();
                    });
        }
    }
}
