package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Button;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code git.applyPatch} / {@code git.createPatch} and the patch review tab's Apply pair, against real git. */
@Tag("fx")
class GitPatchFeaturesFxTest {

    private static final String BASE = "a\nb\nc\nd\ne\nf\ng\nh\n";
    private static final String PATCHED = "a\nb\nc\nD from the patch\ne\nf\ng\nh\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A repository with {@code f.txt} = BASE committed, and the patch that turns it into PATCHED. */
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
    void theCommandsAreRegisteredAndInTheMenu(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            CommandRegistry registry = FxTestSupport.field(w.fx.controller, "registry");
            for (String id : List.of(
                    "git.stashes",
                    "git.stash",
                    "git.stashPop",
                    "git.unstash",
                    "git.stashDrop",
                    "git.blame.ignoreWhitespace",
                    "git.blame.detectMoves",
                    "git.blamePreviousRevision",
                    "git.applyPatch",
                    "git.createPatch")) {
                assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), id + " is not a command");
            }
            for (String id : List.of("git.stashes", "git.blamePreviousRevision", "git.applyPatch", "git.createPatch")) {
                assertTrue(MenuBarModel.allCommandIds().contains(id), id + " is not in the menu bar");
            }
        }
    }

    /** A clean patch is applied to the working tree through the mutation path: the open buffer reloads. */
    @Test
    void aCleanPatchAppliesToTheWorkingTreeAndTheOpenBufferFollows(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(f.file());
            CountDownLatch applied = w.watchStatus(tr("status.git.patchApplied")::equals);

            FxTestSupport.runOnFx(() -> w.git.patches().apply(f.patch(), false));
            async.await(applied, "the apply");

            assertEquals(PATCHED, Files.readString(f.file()));
            GitFeatureFx.await("the buffer to reload", () -> PATCHED.equals(buffer.getContent()));
            assertEquals("", f.repo().git("diff", "--cached", "--name-only").text(), "the index is untouched");
        }
    }

    @Test
    void applyToIndexLeavesTheWorkingTreeAlone(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            CountDownLatch applied = w.watchStatus(tr("status.git.patchAppliedIndex")::equals);

            FxTestSupport.runOnFx(() -> w.git.patches().apply(f.patch(), true));
            async.await(applied, "the apply");

            assertEquals(PATCHED, f.repo().git("show", ":f.txt").text());
            assertEquals(BASE, Files.readString(f.file()));
        }
    }

    /**
     * A patch that does not fit changes nothing; the user is shown git's reason and offered a three-way
     * merge. Declined: still nothing changed. Accepted: applied, with conflict markers where it did not fit.
     */
    @Test
    void aPatchThatDoesNotApplyShowsGitsReasonAndOffersThreeWay(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        String mine = "a\nb\nc\nD mine\ne\nf\ng\nh\n";
        f.repo().write("f.txt", mine);
        f.repo().commitAll("mine");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(f.file());
            AtomicReference<String> reason = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> w.git.patches().confirmThreeWay = shown -> {
                reason.set(shown);
                return false;
            });
            CountDownLatch refused = w.watchStatus(tr("status.git.patchFailed")::equals);

            FxTestSupport.runOnFx(() -> w.git.patches().apply(f.patch(), false));
            async.await(refused, "the refusal");
            assertTrue(reason.get().contains("patch does not apply"), "git's own reason is shown: " + reason.get());
            assertEquals(mine, Files.readString(f.file()), "a failed pre-flight applies nothing");

            FxTestSupport.runOnFx(() -> w.git.patches().confirmThreeWay = shown -> true);
            CountDownLatch conflicts = w.watchStatus(tr("status.git.patchConflicts")::equals);
            FxTestSupport.runOnFx(() -> w.git.patches().apply(f.patch(), false));
            async.await(conflicts, "the three-way apply");

            String merged = Files.readString(f.file());
            assertTrue(merged.contains("<<<<<<<") && merged.contains("D mine") && merged.contains("D from the patch"));
            GitFeatureFx.await(
                    "the buffer to show the markers", () -> buffer.getContent().contains("<<<<<<<"));
            GitFeatureFx.await(
                    "status to list the conflict",
                    () -> w.git.status().files().stream().anyMatch(file -> file.unmerged()));
        }
    }

    /** In a repository a patch file opens as a review tab with the Apply pair; the buttons apply that patch. */
    @Test
    void thePatchReviewTabAppliesThePatchItShows(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path patchFile = f.repo().write("change.patch", f.patch()); // untracked, inside the repository

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(f.file());
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(patchFile, 0));
            GitFeatureFx.await(
                    "the patch tab",
                    () -> w.active() != null && patchFile.equals(w.active().getPath()));
            EditorBuffer patchBuffer = FxTestSupport.callOnFx(w::active);
            assertTrue(FxTestSupport.callOnFx(
                    () -> GitPatchCoordinator.isPatch(patchBuffer.getTitle(), patchBuffer.getContent())));
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await(
                    "the patch file's repository", () -> f.repo().root.equals(w.git.repoRoot()));
            DiffCoordinator diff = FxTestSupport.field(w.fx.controller, "diffCoordinator");

            FxTestSupport.runOnFx(() -> diff.openPatchFile(patchBuffer));
            GitFeatureFx.await("the review tab", () -> w.area.selectedTab().getUserData() instanceof PatchReviewPane);
            PatchReviewPane review = (PatchReviewPane) w.activeContent();
            List<Button> buttons = FxTestSupport.callOnFx(review::applyButtons);
            assertEquals(
                    List.of(tr("diff.patch.applyWorktree"), tr("diff.patch.applyIndex")),
                    buttons.stream().map(Button::getText).toList());

            CountDownLatch staged = w.watchStatus(tr("status.git.patchAppliedIndex")::equals);
            FxTestSupport.runOnFx(() -> buttons.get(1).fire());
            async.await(staged, "Apply to Index");
            assertEquals(PATCHED, f.repo().git("show", ":f.txt").text());
            assertEquals(BASE, Files.readString(f.file()));
        }
    }

    /** Staged and unstaged changes and a commit, to a new buffer or a file — and the result applies again. */
    @Test
    void createPatchWritesStagedUnstagedAndCommitPatches(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", BASE);
        repo.write("g.txt", "g one\n");
        repo.commitAll("base");
        repo.write("f.txt", PATCHED);
        repo.commitAll("change the fourth line");
        String commit = repo.git("rev-parse", "HEAD").text().strip();
        repo.write("f.txt", PATCHED + "staged line\n");
        repo.git("add", "f.txt");
        repo.write("g.txt", "g one\nunstaged line\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer source = w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            GitPatchCoordinator patches = w.git.patches();

            // Staged → a new untitled buffer.
            FxTestSupport.runOnFx(() -> patches.create(
                    root, GitPatchCoordinator.Source.STAGED, null, GitPatchCoordinator.Destination.BUFFER));
            GitFeatureFx.await("the staged patch tab", () -> w.active() != null && w.active() != source);
            EditorBuffer staged = FxTestSupport.callOnFx(w::active);
            assertEquals("staged.patch", FxTestSupport.callOnFx(staged::getTitle));
            assertNullPath(staged);
            String stagedText = FxTestSupport.callOnFx(staged::getContent);
            assertTrue(stagedText.contains("+staged line") && !stagedText.contains("unstaged line"), stagedText);

            // Unstaged → a file the user names.
            Path saved = dir.resolve("out").resolve("changes.patch");
            Files.createDirectories(saved.getParent());
            AtomicReference<String> suggested = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> patches.chooseSaveFile = name -> {
                suggested.set(name);
                return saved;
            });
            CountDownLatch written = w.watchStatus(tr("status.diff.patchSaved", "changes.patch")::equals);
            FxTestSupport.runOnFx(() -> patches.create(
                    root, GitPatchCoordinator.Source.UNSTAGED, null, GitPatchCoordinator.Destination.FILE));
            async.await(written, "the unstaged patch file");
            assertEquals("changes.patch", suggested.get());
            String unstagedText = Files.readString(saved);
            assertTrue(
                    unstagedText.contains("+unstaged line") && !unstagedText.contains("staged line\n+"), unstagedText);

            // A commit → format-patch, by hash (the entry point the Git Log calls).
            Path commitFile = dir.resolve("out").resolve("commit.patch");
            FxTestSupport.runOnFx(() -> patches.chooseSaveFile = name -> {
                suggested.set(name);
                return commitFile;
            });
            CountDownLatch commitWritten = w.watchStatus(tr("status.diff.patchSaved", "commit.patch")::equals);
            FxTestSupport.runOnFx(() -> patches.create(
                    root, GitPatchCoordinator.Source.COMMIT, commit, GitPatchCoordinator.Destination.FILE));
            async.await(commitWritten, "the commit patch file");
            assertEquals(commit.substring(0, 7) + ".patch", suggested.get());
            String mail = Files.readString(commitFile);
            assertTrue(mail.startsWith("From " + commit), mail);
            assertTrue(mail.contains("Subject: [PATCH] change the fourth line"), mail);

            // What was written is a patch git takes back: undo the commit's change, apply the file.
            repo.git("reset", "-q", "--hard", "HEAD~1");
            assertEquals(BASE, Files.readString(file));
            CountDownLatch applied = w.watchStatus(tr("status.git.patchApplied")::equals);
            byte[] bytes = Files.readAllBytes(commitFile);
            FxTestSupport.runOnFx(() -> patches.apply(root, bytes, false)); // the active tab is untitled
            async.await(applied, "applying the created patch");
            assertEquals(PATCHED, Files.readString(file));
        }
    }

    private static void assertNullPath(EditorBuffer buffer) throws Exception {
        assertEquals(null, FxTestSupport.callOnFx(buffer::getPath), "an untitled buffer: the first save asks where");
    }

    /** Which buffers count as "the active buffer is a patch", and which files a patch will rewrite. */
    @Test
    void recognisesPatchesAndTheFilesTheyTouch() {
        String patch = "--- a/src/f.txt\n+++ b/src/f.txt\n@@ -1 +1 @@\n-old\n+new\n";
        assertTrue(GitPatchCoordinator.isPatch("change.patch", patch));
        assertTrue(GitPatchCoordinator.isPatch("untitled", "diff --git a/src/f.txt b/src/f.txt\n" + patch));
        assertTrue(GitPatchCoordinator.isPatch("untitled", patch));
        assertFalse(GitPatchCoordinator.isPatch("notes.md", "Here is a diff:\n\n" + patch), "a document quoting one");
        assertFalse(GitPatchCoordinator.isPatch("change.patch", "not a diff at all\n"));
        assertFalse(GitPatchCoordinator.isPatch("change.patch", ""));

        assertEquals(List.of("src/f.txt"), GitPatchCoordinator.touchedPaths(patch.getBytes(StandardCharsets.UTF_8)));
        String added = "--- /dev/null\n+++ b/año/nuevo.txt\n@@ -0,0 +1 @@\n+hola\n";
        assertEquals(
                List.of("año/nuevo.txt"), GitPatchCoordinator.touchedPaths(added.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(), GitPatchCoordinator.touchedPaths(new byte[] {0, 1, 2}), "unparsable: every buffer");
        assertEquals("staged.patch", GitPatchCoordinator.suggestedName(GitPatchCoordinator.Source.STAGED, null));
    }
}
