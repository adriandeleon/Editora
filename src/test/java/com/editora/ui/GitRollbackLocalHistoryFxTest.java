package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Window;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R3: Git Rollback / Discard of tracked changes copies each file's working-tree text to Local History first. A
 * change made outside Editora (a formatter, {@code sed}, another editor) is in no commit and nowhere else.
 */
@Tag("fx")
@Tag("windows-fx")
class GitRollbackLocalHistoryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rollingBackAFileChangedOutsideEditoraKeepsTheChangeInLocalHistory(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path tracked = Files.writeString(repo.resolve("tracked.txt"), "base\n");
        commitAll(repo);
        Files.writeString(tracked, "edited by a formatter\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, List.of());
            CountDownLatch done = watchStatus(fx, tr("status.git.discarded", "tracked.txt")::equals);

            FxTestSupport.runOnFx(() -> coordinator.discardChanges(List.of("tracked.txt"), List.of()));
            confirmWhenAsked();
            async.await(done, "discard");

            assertEquals("base\n", Files.readString(tracked));
            assertEquals(List.of("edited by a formatter\n"), historyTexts(fx, tracked));
        }
    }

    @Test
    void discardAllCopiesStagedAndUnstagedFilesFirst(@TempDir Path dir) throws Exception {
        Path repo = initRepo(dir);
        Path unstaged = Files.writeString(repo.resolve("unstaged.txt"), "base one\n");
        Path staged = Files.writeString(repo.resolve("staged.txt"), "base two\n");
        commitAll(repo);
        Files.writeString(unstaged, "unstaged work\n");
        Files.writeString(staged, "staged work\n");
        git(repo, "add", "--", "staged.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(
                    fx,
                    repo,
                    List.of(
                            new GitStatus.FileEntry("unstaged.txt", '.', 'M', null),
                            new GitStatus.FileEntry("staged.txt", 'M', '.', null)));
            CountDownLatch done = watchStatus(fx, tr("status.git.discardedAll", 2, 0)::equals);

            // asked once the untracked rows have been looked at, off the FX thread
            FxTestSupport.runOnFx(coordinator::gitDiscardAll);
            confirmWhenAsked();
            async.await(done, "discard all");

            assertEquals("base one\n", Files.readString(unstaged));
            assertEquals("base two\n", Files.readString(staged));
            assertEquals(List.of("unstaged work\n"), historyTexts(fx, unstaged));
            assertEquals(List.of("staged work\n"), historyTexts(fx, staged));
        }
    }

    @Test
    void aFileLocalHistoryCannotReadIsNotRolledBack(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
        Path repo = initRepo(dir);
        Path tracked = Files.writeString(repo.resolve("locked.txt"), "base\n");
        commitAll(repo);
        Files.writeString(tracked, "only copy\n");
        Files.setPosixFilePermissions(tracked, PosixFilePermissions.fromString("-w-------"));
        Assumptions.assumeTrue(!Files.isReadable(tracked), "running as a user who reads everything");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator coordinator = applyRepo(fx, repo, List.of());
            CountDownLatch refused = watchStatus(fx, tr("status.git.discardHistoryFailed", "locked.txt")::equals);

            FxTestSupport.runOnFx(() -> coordinator.discardChanges(List.of("locked.txt"), List.of()));
            confirmWhenAsked();
            async.await(refused, "refusal");
            async.awaitFx();
        } finally {
            Files.setPosixFilePermissions(tracked, PosixFilePermissions.fromString("rw-------"));
        }
        assertEquals("only copy\n", Files.readString(tracked), "nothing was rolled back without its copy");
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static List<String> historyTexts(FxWindowFixture fx, Path file) throws Exception {
        String key = PathKeys.normalizedKey(file);
        HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        List<HistoryRevision> revisions = FxTestSupport.callOnFx(() -> {
            List<HistoryRevision> all = new ArrayList<>();
            for (Map<String, List<HistoryRevision>> bucket :
                    fx.shared.historyByProject().values()) {
                all.addAll(bucket.getOrDefault(key, List.of()));
            }
            return all;
        });
        List<String> texts = new ArrayList<>();
        for (HistoryRevision revision : revisions) {
            texts.add(blobs.get(revision.sha256()));
        }
        return texts;
    }

    private static GitCoordinator applyRepo(FxWindowFixture fx, Path repo, List<GitStatus.FileEntry> files)
            throws Exception {
        GitCoordinator coordinator = FxTestSupport.field(fx.controller, "git");
        GitStatus status = new GitStatus(true, "main", null, 0, 0, files);
        FxTestSupport.runOnFx(() -> coordinator.applyState(
                new GitService.RepoState(repo.toAbsolutePath().normalize(), status, Map.of(), Map.of())));
        return coordinator;
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

    /** Presses the confirmation's action once it is on screen: it follows a look off the FX thread. */
    private static void confirmWhenAsked() throws Exception {
        FxTestSupport.awaitFxCondition(
                () -> {
                    CountDownLatch pressed = new CountDownLatch(1);
                    pressDialog(ButtonBar.ButtonData.OK_DONE, pressed);
                    return pressed.getCount() == 0;
                },
                "the discard confirmation",
                java.time.Duration.ofSeconds(20));
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
        // Git for Windows converts LF to CRLF on checkout by default; these tests compare bytes.
        git(repo, "config", "core.autocrlf", "false");
        return repo;
    }

    private static void commitAll(Path repo) throws Exception {
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "base");
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
