package com.editora.ui;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code git.applyPatch} and {@code git.createPatch} as the user drives them: the pickers, the patch file that
 * cannot be read, the rejected patch and its three-way question, and every "nothing to do" answer.
 */
@Tag("fx")
class GitPatchCommandsFxTest {

    private static final String BASE = "a\nb\nc\nd\ne\nf\ng\nh\n";
    private static final String PATCHED = "a\nb\nc\nD from the patch\ne\nf\ng\nh\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Fixture(GitTestRepo repo, Path file, byte[] patch) {}

    private static Fixture fixture(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", BASE);
        repo.commitAll("base");
        repo.write("f.txt", PATCHED);
        byte[] patch = repo.git("diff").out();
        repo.git("checkout", "-q", "--", "f.txt");
        return new Fixture(repo, file, patch);
    }

    @Test
    void applyPatchTakesTheActivePatchBufferAndAsksOnlyForTheTarget(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path patchFile = f.repo().write("change.patch", f.patch());
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(patchFile);
            GitPatchCoordinator patches = w.git.patches();
            FxTestSupport.runOnFx(() -> patches.choosePatchFile = () -> {
                throw new AssertionError("the active buffer is the patch: no file is asked for");
            });

            FxTestSupport.runOnFx(patches::applyPatchCommand);
            assertEquals(
                    List.of(tr("diff.patch.applyWorktree"), tr("diff.patch.applyIndex")),
                    FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(w.scene()).stream()
                            .map(target -> (String) FxTestSupport.call(target, "label", new Class<?>[] {}))
                            .toList()));
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("diff.patch.applyIndex"))));
            OverlayTestKit.await(async, "the apply", () -> w.messages().contains(tr("status.git.patchAppliedIndex")));

            assertEquals(PATCHED, f.repo().git("show", ":f.txt").text());
            assertEquals(BASE, Files.readString(f.file()), "the working tree is left alone");
        }
    }

    @Test
    void applyPatchAsksForAFileWhenTheActiveBufferIsNotAPatch(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path patchFile = Files.write(dir.resolve("outside.patch"), f.patch());
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();

            // Cancelled in the file dialog: nothing is asked next and nothing changes.
            FxTestSupport.runOnFx(() -> {
                patches.choosePatchFile = () -> null;
                patches.applyPatchCommand();
            });
            async.awaitFx();
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.picker(w.scene())));

            FxTestSupport.runOnFx(() -> {
                patches.choosePatchFile = () -> patchFile;
                patches.applyPatchCommand();
            });
            OverlayTestKit.await(async, "the target picker", () -> OverlayTestKit.picker(w.scene()) != null);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pickRow(w.scene(), 0)));
            OverlayTestKit.await(async, "the apply", () -> w.messages().contains(tr("status.git.patchApplied")));
            assertEquals(PATCHED, Files.readString(f.file()));
            OverlayTestKit.await(async, "the open buffer to follow", () -> PATCHED.equals(buffer.getContent()));
        }
    }

    @Test
    void aPatchFileThatIsMissingOrTooLargeIsReportedAndNeverApplied(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path huge = dir.resolve("huge.patch");
        try (RandomAccessFile sparse = new RandomAccessFile(huge.toFile(), "rw")) {
            sparse.setLength(GitPatchCoordinator.MAX_PATCH_BYTES + 1);
        }
        Path missing = dir.resolve("gone.patch");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();
            AtomicInteger delivered = new AtomicInteger();

            FxTestSupport.runOnFx(() -> patches.readPatchFile(huge, bytes -> delivered.incrementAndGet()));
            OverlayTestKit.await(
                    async,
                    "the too-large message",
                    () -> w.messages().contains(tr("status.git.patchTooLarge", "huge.patch")));
            FxTestSupport.runOnFx(() -> patches.readPatchFile(missing, bytes -> delivered.incrementAndGet()));
            OverlayTestKit.await(
                    async,
                    "the unreadable message",
                    () -> w.messages().contains(tr("status.git.patchReadFailed", "gone.patch")));

            assertEquals(0, delivered.get(), "neither file reached the apply step");
            assertEquals(BASE, Files.readString(f.file()));
        }
    }

    /** The real three-way question: it shows git's reason, and Cancel leaves everything as it was. */
    @Test
    void theThreeWayQuestionShowsGitsReasonAndCancelChangesNothing(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        String mine = "a\nb\nc\nD mine\ne\nf\ng\nh\n";
        f.repo().write("f.txt", mine);
        f.repo().commitAll("mine");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();

            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch cancelled = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("dialog.patch.rejected").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.CANCEL_CLOSE,
                    question);
            FxTestSupport.runOnFx(() -> patches.apply(f.patch(), false));
            async.await(cancelled, "the three-way question");
            OverlayTestKit.await(async, "the refusal", () -> w.messages().contains(tr("status.git.patchFailed")));
            assertTrue(
                    question.get().content().contains("patch does not apply"),
                    question.get().content());
            assertTrue(question.get().content().endsWith(tr("dialog.patch.threeWayHint")));
            assertTrue(question.get().buttons().contains(tr("dialog.patch.threeWay")));
            assertEquals(mine, Files.readString(f.file()));

            CountDownLatch accepted = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("dialog.patch.rejected").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.OK_DONE,
                    null);
            FxTestSupport.runOnFx(() -> patches.apply(f.patch(), false));
            async.await(accepted, "the three-way question, accepted");
            OverlayTestKit.await(async, "the conflicts", () -> w.messages().contains(tr("status.git.patchConflicts")));
            String merged = Files.readString(f.file());
            assertTrue(merged.contains("<<<<<<<") && merged.contains("D mine") && merged.contains("D from the patch"));
        }
    }

    /** A patch even a three-way merge cannot place: git's reason is shown and nothing is written. */
    @Test
    void aPatchThatFailsTheThreeWayMergeTooIsReportedWithGitsReason(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        byte[] foreign = ("diff --git a/f.txt b/f.txt\n"
                        + "index 1111111..2222222 100644\n"
                        + "--- a/f.txt\n"
                        + "+++ b/f.txt\n"
                        + "@@ -1,3 +1,3 @@\n"
                        + " x\n"
                        + "-y\n"
                        + "+Y\n"
                        + " z\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();
            AtomicReference<String> firstReason = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> patches.confirmThreeWay = reason -> {
                firstReason.set(reason);
                return true;
            });

            AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
            CountDownLatch shown = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("status.git.patchFailed").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.OK_DONE,
                    error);
            FxTestSupport.runOnFx(() -> patches.apply(foreign, false));
            async.await(shown, "the error dialog of the failed three-way merge");

            assertTrue(firstReason.get().contains("patch"), firstReason.get());
            assertFalse(error.get().content().isBlank(), "git's own explanation is in the dialog");
            assertEquals(BASE, Files.readString(f.file()));
            assertEquals("", f.repo().git("status", "--porcelain").text(), "nothing staged, nothing modified");
        }
    }

    @Test
    void createPatchWalksFromWhatToWhereAndOpensTheStagedChangesInABuffer(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().write("f.txt", BASE + "staged line\n");
        f.repo().git("add", "f.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer source = w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();

            FxTestSupport.runOnFx(patches::createPatchCommand);
            assertEquals(
                    3,
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.pickerItems(w.scene()).size()));
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("git.createPatch.staged"))));
            OverlayTestKit.await(
                    async,
                    "the destination picker",
                    () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("git.createPatch.toBuffer"))));

            OverlayTestKit.await(async, "the patch tab", () -> w.active() != null && w.active() != source);
            EditorBuffer patch = FxTestSupport.callOnFx(w::active);
            assertEquals("staged.patch", FxTestSupport.callOnFx(patch::getTitle));
            assertTrue(FxTestSupport.callOnFx(patch::getContent).contains("+staged line"));
            assertTrue(w.messages().contains(tr("status.git.patchOpened", "staged.patch")));
        }
    }

    @Test
    void createPatchFromACommitPicksTheCommitThenWritesTheFile(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().write("f.txt", PATCHED);
        f.repo().commitAll("change the fourth line");
        String commit = f.repo().git("rev-parse", "HEAD").text().strip();
        Path out = dir.resolve("commit.patch");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            GitPatchCoordinator patches = w.git.patches();
            AtomicReference<String> suggested = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> patches.chooseSaveFile = name -> {
                suggested.set(name);
                return out;
            });

            FxTestSupport.runOnFx(patches::createPatchCommand);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("git.createPatch.commit"))));
            OverlayTestKit.await(
                    async,
                    "the commit picker",
                    () -> OverlayTestKit.pickerItems(w.scene()).stream().allMatch(GitService.Commit.class::isInstance)
                            && OverlayTestKit.pickerItems(w.scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "change the fourth")));
            OverlayTestKit.await(
                    async,
                    "the destination picker",
                    () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("git.createPatch.toFile"))));
            OverlayTestKit.await(
                    async,
                    "the written file",
                    () -> w.messages().contains(tr("status.diff.patchSaved", "commit.patch")));

            assertEquals(commit.substring(0, 7) + ".patch", suggested.get());
            String mail = Files.readString(out);
            assertTrue(mail.startsWith("From " + commit), mail);
            assertTrue(mail.contains("+D from the patch"), mail);

            // The Git Log's entry point skips the first two pickers; cancelling the save dialog writes nothing.
            Files.delete(out);
            FxTestSupport.runOnFx(() -> {
                patches.chooseSaveFile = name -> null;
                patches.createCommitPatch(commit);
            });
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), tr("git.createPatch.toFile"))));
            async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
            async.awaitFx();
            assertFalse(Files.exists(out));
        }
    }

    @Test
    void createPatchSaysWhenThereIsNothingToWriteOrGitRefuses(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            GitPatchCoordinator patches = w.git.patches();
            FxTestSupport.runOnFx(() -> patches.chooseSaveFile = name -> {
                throw new AssertionError("an empty patch is never offered for saving");
            });

            FxTestSupport.runOnFx(() -> patches.create(
                    root, GitPatchCoordinator.Source.STAGED, null, GitPatchCoordinator.Destination.FILE));
            OverlayTestKit.await(
                    async, "no staged changes", () -> w.messages().contains(tr("status.diff.noStagedChanges")));
            FxTestSupport.runOnFx(() -> patches.create(
                    root, GitPatchCoordinator.Source.UNSTAGED, null, GitPatchCoordinator.Destination.FILE));
            OverlayTestKit.await(
                    async, "no unstaged changes", () -> w.messages().contains(tr("status.diff.noWorkingChanges")));

            // A commit git does not have: its error is shown, not an empty file.
            AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
            CountDownLatch shown = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("status.git.patchCreateFailed").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.OK_DONE,
                    error);
            FxTestSupport.runOnFx(() -> patches.create(
                    root,
                    GitPatchCoordinator.Source.COMMIT,
                    "0123456789012345678901234567890123456789",
                    GitPatchCoordinator.Destination.FILE));
            async.await(shown, "the create-failed dialog");
            assertFalse(error.get().content().isBlank());

            // No repository: nothing is started.
            FxTestSupport.runOnFx(() -> patches.create(
                    null, GitPatchCoordinator.Source.STAGED, null, GitPatchCoordinator.Destination.FILE));
            async.awaitFx();
            assertEquals("changes.patch", GitPatchCoordinator.suggestedName(GitPatchCoordinator.Source.UNSTAGED, null));
        }
    }

    @Test
    void aPatchThatCannotBeWrittenReportsTheFailure(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().write("f.txt", BASE + "more\n");
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file where a folder is needed");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            GitPatchCoordinator patches = w.git.patches();
            FxTestSupport.runOnFx(() -> {
                patches.chooseSaveFile = name -> blocker.resolve(name);
                patches.create(root, GitPatchCoordinator.Source.UNSTAGED, null, GitPatchCoordinator.Destination.FILE);
            });
            OverlayTestKit.await(
                    async,
                    "the write failure",
                    () -> w.messages().stream()
                            .anyMatch(message -> message.startsWith(
                                    tr("status.diff.patchFailed", "").strip())));
            assertEquals("a file where a folder is needed", Files.readString(blocker));
        }
    }

    @Test
    void outsideARepositoryBothCommandsOnlySayThereIsNone(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("plain.txt"), "no repository here\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(plain, 0));
            OverlayTestKit.await(
                    async,
                    "the tab",
                    () -> w.active() != null && plain.equals(w.active().getPath()));
            GitPatchCoordinator patches = w.git.patches();
            FxTestSupport.runOnFx(() -> {
                patches.choosePatchFile = () -> {
                    throw new AssertionError("no repository: no file is asked for");
                };
                w.git.refresh();
            });
            async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
            async.awaitFx();

            for (Runnable command : List.<Runnable>of(
                    patches::applyPatchCommand,
                    patches::createPatchCommand,
                    () -> patches.apply(new byte[] {1}, false),
                    () -> patches.createCommitPatch("abc1234"))) {
                w.clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.notARepo"), w.status());
                assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.picker(w.scene())));
            }
            FxTestSupport.runOnFx(() -> patches.apply(null, new byte[] {1}, false)); // no root: nothing to run
        }
    }
}
