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
            async.awaitFx();

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
        List<String> tracked = new ArrayList<>(List.of("-dash.txt", "space name.txt"));
        List<String> untracked = new ArrayList<>(List.of("-new.txt", "new space.txt"));
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
                        ((Button) pane.lookupButton(type)).fire();
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
