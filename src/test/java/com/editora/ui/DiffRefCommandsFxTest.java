package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitOutputDiffs;
import com.editora.git.GitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff commands that compare a file or folder with something else the user names — a commit, a branch, a
 * tag, another revision, another path — and what each says when it has nothing to compare. Real git, real
 * pickers, in a temp repository.
 */
@Tag("fx")
class DiffRefCommandsFxTest {

    private static final String FIRST = "one\ntwo\nthree\n";
    private static final String FEATURE = "one\nTWO feature\nthree\n";
    private static final String SECOND = "one\ntwo\nthree\nfour\n";
    private static final String WORKING = "one\ntwo\nthree\nfour\nworking\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * {@code main}: f.txt FIRST then SECOND (working copy WORKING), dir/sub.txt. {@code feature} (from the
     * first commit): f.txt FEATURE, dir/sub.txt changed, dir/new.txt added.
     */
    private record Fixture(GitTestRepo repo, Path file, String first, String second) {}

    private static Fixture fixture(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", FIRST);
        repo.write("dir/sub.txt", "sub v1\n");
        repo.commitAll("first");
        String first = repo.git("rev-parse", "HEAD").text().strip();
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("f.txt", FEATURE);
        repo.write("dir/sub.txt", "sub feature\n");
        repo.write("dir/new.txt", "only on feature\n");
        repo.commitAll("feature work");
        repo.git("checkout", "-q", "main");
        repo.write("f.txt", SECOND);
        repo.commitAll("second");
        String second = repo.git("rev-parse", "HEAD").text().strip();
        repo.write("f.txt", WORKING);
        return new Fixture(repo, file, first, second);
    }

    private static DiffCoordinator diff(GitFeatureFx w) {
        return FxTestSupport.field(w.fx.controller, "diffCoordinator");
    }

    @Test
    void everyCompareCommandSaysWhenThereIsNoFileOrItIsOutsideTheRepository(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path outside = Files.writeString(dir.resolve("outside.txt"), "not in the repository\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());

            for (java.util.function.Consumer<Path> command : List.<java.util.function.Consumer<Path>>of(
                    diff::diffPathVsHead, diff::diffPathVsCommit, diff::diffPathVsBranch, diff::diffPathVsTag)) {
                w.clearStatus();
                FxTestSupport.runOnFx(() -> command.accept(null));
                assertEquals(tr("status.diff.noFile"), w.status());
                FxTestSupport.runOnFx(() -> command.accept(outside));
                assertEquals(tr("status.diff.notInRepo"), w.status());
            }
            assertFalse(w.activeContent() instanceof DiffViewerPane, "nothing was opened");
        }
    }

    @Test
    void aFileIsComparedWithTheCommitPickedFromItsHistory(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().write("untracked.txt", "never committed\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());

            FxTestSupport.runOnFx(diff::diffActiveVsCommit);
            OverlayTestKit.await(
                    async,
                    "the commit picker",
                    () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
            List<?> commits = FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(w.scene()));
            assertEquals(
                    List.of("second", "first"),
                    commits.stream()
                            .map(commit -> ((GitService.Commit) commit).subject())
                            .toList(),
                    "the file's own history, newest first");

            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pickRow(w.scene(), 1)));
            DiffViewerPane pane = awaitDiff(async, w);
            String shortHash = f.first().substring(0, 7);
            assertEquals(tr("diff.title.vsBranch", "f.txt", shortHash), pane.title());
            assertEquals(FIRST, FxTestSupport.field(pane, "leftText"));
            assertEquals(WORKING, FxTestSupport.field(pane, "rightText"));
            assertEquals(DiffViewerPane.EditableSide.RIGHT, FxTestSupport.callOnFx(pane::editableSide));

            // A file git has never seen has no history to pick from. (Back on the file's tab first: a diff
            // tab is not a file, so a window without a project has no repository while one is selected.)
            w.open(f.file());
            FxTestSupport.runOnFx(() -> diff.diffPathVsCommit(f.repo().root.resolve("untracked.txt")));
            OverlayTestKit.await(
                    async, "the no-history message", () -> w.messages().contains(tr("status.diff.noHistory")));
        }
    }

    @Test
    void aFileOrAFolderIsComparedWithAChosenBranch(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());

            FxTestSupport.runOnFx(() -> diff.diffPathVsBranch(f.file()));
            OverlayTestKit.await(
                    async,
                    "the branch picker",
                    () -> !OverlayTestKit.pickerItems(w.scene()).isEmpty());
            assertEquals(
                    List.of("feature", "main"),
                    FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(w.scene()).stream()
                            .map(String::valueOf)
                            .sorted()
                            .toList()));
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "feature")));
            DiffViewerPane pane = awaitDiff(async, w);
            assertEquals(tr("diff.title.vsBranch", "f.txt", "feature"), pane.title());
            assertEquals(FEATURE, FxTestSupport.field(pane, "leftText"));
            assertEquals(WORKING, FxTestSupport.field(pane, "rightText"));

            // A folder opens the review of every file under it that differs from the branch.
            Path folder = f.repo().root.resolve("dir");
            w.open(f.file());
            FxTestSupport.runOnFx(() -> diff.diffPathVsBranch(folder));
            OverlayTestKit.await(
                    async,
                    "the branch picker again",
                    () -> !OverlayTestKit.pickerItems(w.scene()).isEmpty());
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "feature")));
            DirectoryReviewPane review = awaitReview(async, w);
            assertEquals(tr("diff.title.vsBranch", "dir", "feature"), review.title());
            assertEquals(List.of("new.txt", "sub.txt"), labels(review), "paths are shown relative to the folder");
            assertTrue(w.messages().contains(tr("status.diff.directoryOpened", 2)));
            OverlayTestKit.await(async, "the first file's diff", () -> review.activePane() != null);
            DiffViewerPane first = FxTestSupport.callOnFx(review::activePane);
            assertEquals("only on feature\n", FxTestSupport.field(first, "leftText"));
            assertEquals("", FxTestSupport.field(first, "rightText"), "the working tree has no such file");
        }
    }

    @Test
    void theHeadCommandOpensAFolderAsAReviewOfItsUncommittedChanges(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().write("dir/sub.txt", "sub edited\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());

            FxTestSupport.runOnFx(() -> diff.diffPathVsHead(f.repo().root.resolve("dir")));
            DirectoryReviewPane review = awaitReview(async, w);
            assertEquals(List.of("sub.txt"), labels(review));
            OverlayTestKit.await(async, "the file's diff", () -> review.activePane() != null);
            DiffViewerPane pane = FxTestSupport.callOnFx(review::activePane);
            assertEquals("sub v1\n", FxTestSupport.field(pane, "leftText"));
            assertEquals("sub edited\n", FxTestSupport.field(pane, "rightText"));
            assertEquals(
                    DiffViewerPane.EditableSide.RIGHT,
                    FxTestSupport.callOnFx(pane::editableSide),
                    "the working file can take the committed text back");
        }
    }

    @Test
    void tagsAreOfferedOnlyWhenTheRepositoryHasSome(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);

            FxTestSupport.runOnFx(() -> diff.diffPathVsTag(f.file()));
            OverlayTestKit.await(
                    async, "the no-tags message", () -> w.messages().contains(tr("status.diff.noTags")));
            List<String> picked = new java.util.concurrent.CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> diff.pickTag(root, "Pick a tag", picked::add));
            OverlayTestKit.await(
                    async, "the tag command's message", () -> w.messages().contains(tr("status.git.noTags")));
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(w.scene())));

            w.open(f.file());
            f.repo().git("tag", "v1", f.first());
            f.repo().git("tag", "v2", f.second());
            FxTestSupport.runOnFx(() -> diff.diffPathVsTag(f.file()));
            OverlayTestKit.await(
                    async,
                    "the tag picker",
                    () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "v1")));
            DiffViewerPane pane = awaitDiff(async, w);
            assertEquals(tr("diff.title.vsBranch", "f.txt", "v1"), pane.title());
            assertEquals(FIRST, FxTestSupport.field(pane, "leftText"));

            w.open(f.file());
            FxTestSupport.runOnFx(() -> diff.pickTag(root, "Pick a tag", picked::add));
            OverlayTestKit.await(
                    async,
                    "the tag picker of a tag command",
                    () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "v2")));
            assertEquals(List.of("v2"), picked);
        }
    }

    @Test
    void twoRevisionsAreComparedAsAReadOnlyReview(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);

            FxTestSupport.runOnFx(() -> diff.compareRefs(root, "main", "feature"));
            DirectoryReviewPane review = awaitReview(async, w);
            assertEquals(tr("diff.title.refVsRef", "main", "feature"), review.title());
            assertEquals(List.of("dir/new.txt", "dir/sub.txt", "f.txt"), labels(review));
            OverlayTestKit.await(async, "the first file's diff", () -> review.activePane() != null);
            DiffViewerPane added = FxTestSupport.callOnFx(review::activePane);
            assertEquals("", FxTestSupport.field(added, "leftText"), "main has no such file");
            assertEquals("only on feature\n", FxTestSupport.field(added, "rightText"));
            assertEquals(
                    DiffViewerPane.EditableSide.NONE,
                    FxTestSupport.callOnFx(added::editableSide),
                    "both sides are blobs: nothing to edit");

            // The same revision twice: an empty review that says so.
            FxTestSupport.runOnFx(() -> diff.compareRefs(root, "main", "main"));
            OverlayTestKit.await(
                    async,
                    "the identical message",
                    () -> w.messages().contains(tr("status.diff.refsIdentical", "main", "main")));

            // A revision git does not know: its error is passed on, and no tab opens.
            Object before = w.activeContent();
            FxTestSupport.runOnFx(() -> diff.compareRefs(root, "main", "no-such-branch"));
            OverlayTestKit.await(
                    async,
                    "the failure message",
                    () -> w.messages().stream()
                            .anyMatch(message -> message.startsWith(
                                    tr("status.diff.gitFolderFailed", "").strip())));
            assertEquals(before, w.activeContent());

            FxTestSupport.runOnFx(() -> diff.compareRefs(null, "main", "feature"));
            assertEquals(before, w.activeContent(), "no repository: nothing to compare");
        }
    }

    @Test
    void arbitraryPathsMustBothBeReadableFilesOrBothBeDirectories(@TempDir Path dir) throws Exception {
        Path left = Files.createDirectories(dir.resolve("left"));
        Path right = Files.createDirectories(dir.resolve("right"));
        Files.writeString(left.resolve("same.txt"), "same\n");
        Files.writeString(right.resolve("same.txt"), "same\n");
        Path file = Files.writeString(dir.resolve("a.txt"), "a\n");
        Path missing = dir.resolve("missing.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);

            int tabsBefore = FxTestSupport.callOnFx(() -> w.area.size());
            FxTestSupport.runOnFx(() -> diff.comparePaths(file, right));
            assertEquals(tr("status.diff.pathTypeMismatch"), w.status());
            FxTestSupport.runOnFx(() -> diff.comparePaths(left, file));
            assertEquals(tr("status.diff.pathTypeMismatch"), w.status());

            FxTestSupport.runOnFx(() -> diff.comparePaths(file, missing));
            assertEquals(tr("status.diff.unreadable", missing.toAbsolutePath().normalize()), w.status());
            FxTestSupport.runOnFx(() -> diff.comparePaths(null, file));
            assertEquals(tr("status.diff.unreadable", "").strip(), w.status());

            FxTestSupport.runOnFx(() -> diff.compareDirectories(left, missing));
            assertEquals(
                    tr(
                            "status.diff.unreadableDirectory",
                            missing.toAbsolutePath().normalize()),
                    w.status());
            FxTestSupport.runOnFx(() -> diff.compareDirectories(null, right));
            assertEquals(tr("status.diff.unreadableDirectory", "").strip(), w.status());
            assertEquals(tabsBefore, FxTestSupport.callOnFx(() -> w.area.size()), "none of these opened a tab");

            // Two readable trees with nothing different: an empty review, and the count of files that matched.
            FxTestSupport.runOnFx(() -> diff.comparePaths(left, right));
            DirectoryReviewPane review = awaitReview(async, w);
            assertEquals(List.of(), labels(review));
            OverlayTestKit.await(
                    async,
                    "the identical message",
                    () -> w.messages().contains(tr("status.diff.directoriesIdentical", 1)));
        }
    }

    @Test
    void comparingWithTheClipboardOrAnUntitledBufferSaysWhatIsMissing(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);

            // The window starts on an untitled buffer: there is no file to compare.
            for (Runnable command : List.<Runnable>of(
                    diff::diffActiveVsHead,
                    diff::diffActiveVsCommit,
                    diff::compareActiveWithFile,
                    diff::compareActiveWithClipboard,
                    diff::compareActiveWithBlank)) {
                w.clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.diff.noFile"), w.status());
            }
            FxTestSupport.runOnFx(() -> diff.openPatchFile(null));
            assertEquals(tr("status.diff.noFile"), w.status());

            w.open(f.file());
            FxTestSupport.runOnFx(() -> {
                Clipboard.getSystemClipboard().clear();
                diff.compareActiveWithClipboard();
            });
            assertEquals(tr("status.diff.clipboardEmpty"), w.status());

            FxTestSupport.runOnFx(() -> diff.withActiveDiff(pane -> {
                throw new AssertionError("the active tab is an editor, not a diff");
            }));
            assertEquals(tr("status.diff.noActiveDiff"), w.status());
        }
    }

    /** A comparison option changed in the toolbar re-diffs the same two texts under the new rule. */
    @Test
    void changingAComparisonOptionRecomputesTheOpenDiff(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", "alpha\nbeta\n");
        repo.commitAll("base");
        repo.write("f.txt", "alpha\n    beta   \n"); // differs from HEAD only in whitespace
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(file);

            FxTestSupport.runOnFx(diff::diffActiveVsHead);
            DiffViewerPane pane = awaitDiff(async, w);
            Label nav = FxTestSupport.field(pane, "changeNav");
            assertEquals(tr("diff.changeCount.one"), FxTestSupport.callOnFx(nav::getText));

            Button whitespace = FxTestSupport.field(pane, "whitespaceButton");
            FxTestSupport.runOnFx(whitespace::fire); // exact -> trim
            OverlayTestKit.await(
                    async,
                    "the re-diff ignoring edge whitespace",
                    () -> tr("diff.changeCount", 0).equals(nav.getText()));
            assertEquals("alpha\n    beta   \n", FxTestSupport.field(pane, "rightText"), "the texts are unchanged");

            // The choice is the window's: the next diff opens with it.
            w.open(file);
            FxTestSupport.runOnFx(diff::compareActiveWithBlank);
            OverlayTestKit.await(
                    async,
                    "the second diff",
                    () -> w.area.selectedTab().getUserData() != pane
                            && w.area.selectedTab().getUserData() instanceof DiffViewerPane);
            DiffViewerPane blank = (DiffViewerPane) w.activeContent();
            Button blankWhitespace = FxTestSupport.field(blank, "whitespaceButton");
            assertEquals(tr("diff.whitespace.trim"), FxTestSupport.callOnFx(blankWhitespace::getText));

            // With the Result editor open the draft is what is compared, and it is not replaced.
            FxTestSupport.runOnFx(() -> {
                w.area.select(tabOf(w, pane));
                pane.toggleResultEditing();
            });
            assertTrue(FxTestSupport.callOnFx(pane::hasResultEditor));
            FxTestSupport.runOnFx(whitespace::fire); // trim -> ignore all
            FxTestSupport.runOnFx(whitespace::fire); // ignore all -> exact
            OverlayTestKit.await(
                    async, "the exact re-diff", () -> tr("diff.changeCount.one").equals(nav.getText()));
            assertEquals("alpha\n    beta   \n", FxTestSupport.callOnFx(pane::resultText));
        }
    }

    @Test
    void anExportedPatchIsWrittenWhereTheUserSaysAndAppliesToTheCommittedFile(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path out = dir.resolve("exported.patch");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());
            FxTestSupport.runOnFx(diff::diffActiveVsHead);
            DiffViewerPane pane = awaitDiff(async, w);
            Button export = FxTestSupport.field(pane, "exportButton");

            // Cancelled in the save dialog: nothing is written and nothing is claimed.
            FxTestSupport.runOnFx(() -> {
                diff.choosePatchExportFile = () -> null;
                export.fire();
            });
            async.awaitFx();
            assertFalse(Files.exists(out));

            FxTestSupport.runOnFx(() -> {
                diff.choosePatchExportFile = () -> out;
                export.fire();
            });
            OverlayTestKit.await(
                    async,
                    "the saved message",
                    () -> w.messages().contains(tr("status.diff.patchSaved", "exported.patch")));
            String patch = Files.readString(out);
            assertTrue(patch.startsWith("--- a/f.txt\n+++ b/f.txt\n"), patch);
            assertTrue(patch.contains("+working"), patch);
            f.repo().git("checkout", "-q", "--", "f.txt");
            f.repo().git("apply", out.toString());
            assertEquals(WORKING, Files.readString(f.file()), "git takes the exported patch back");

            // Somewhere that cannot be written: the failure is reported, with the reason.
            Path blocked = Files.writeString(dir.resolve("blocker"), "a file, not a folder");
            FxTestSupport.runOnFx(() -> {
                diff.choosePatchExportFile = () -> blocked.resolve("x.patch");
                export.fire();
            });
            OverlayTestKit.await(
                    async,
                    "the failure message",
                    () -> w.messages().stream()
                            .anyMatch(message -> message.startsWith(
                                    tr("status.diff.patchFailed", "").strip())));
        }
    }

    @Test
    void aPulledFileIsShownAsItWasBeforeAndAfterThePull(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            w.open(f.file());

            FxTestSupport.runOnFx(() ->
                    diff.diffPulledFile(new GitOutputDiffs.Target(0, 0, f.first(), f.second(), "f.txt", "f.txt")));
            DiffViewerPane pane = awaitDiff(async, w);
            String range = f.first().substring(0, 7) + ".." + f.second().substring(0, 7);
            assertEquals(tr("diff.title.commitFile", "f.txt", range), pane.title());
            assertEquals(FIRST, FxTestSupport.field(pane, "leftText"));
            assertEquals(SECOND, FxTestSupport.field(pane, "rightText"));
            assertEquals(DiffViewerPane.EditableSide.NONE, FxTestSupport.callOnFx(pane::editableSide));
        }
    }

    /** Swapping the sides of a patch section keeps each hunk's own line numbers on the side they belong to. */
    @Test
    void aPatchSectionCanBeSwappedAndReDiffedWithItsOwnLineNumbers(@TempDir Path dir) throws Exception {
        String patch = "--- a/notes.txt\n+++ b/notes.txt\n@@ -40,3 +40,3 @@\n keep\n-old line\n+new line\n keep too\n";
        Path patchFile = Files.write(dir.resolve("change.patch"), patch.getBytes(StandardCharsets.UTF_8));
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            DiffCoordinator diff = diff(w);
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(patchFile, 0));
            GitFeatureFx.await(
                    "the patch tab",
                    () -> w.active() != null && patchFile.equals(w.active().getPath()));
            EditorBuffer buffer = FxTestSupport.callOnFx(w::active);

            // Outside a repository a one-file patch opens as a plain diff tab.
            FxTestSupport.runOnFx(() -> diff.openPatchFile(buffer));
            DiffViewerPane pane = awaitDiff(async, w);
            assertEquals(tr("diff.title.patch", "notes.txt"), pane.title());
            assertEquals(41, leftLineOfFirstChange(pane));

            FxTestSupport.runOnFx(pane::swapComparisonSides);
            OverlayTestKit.await(
                    async,
                    "the swap",
                    () -> "keep\nnew line\nkeep too\n".equals(FxTestSupport.field(pane, "leftText")));
            assertEquals("keep\nold line\nkeep too\n", FxTestSupport.field(pane, "rightText"));
            assertEquals(41, leftLineOfFirstChange(pane), "still numbered as the patch numbers it");

            Button whitespace = FxTestSupport.field(pane, "whitespaceButton");
            String before = FxTestSupport.callOnFx(whitespace::getText);
            FxTestSupport.runOnFx(whitespace::fire);
            OverlayTestKit.await(async, "the option to change", () -> !before.equals(whitespace.getText()));
            async.awaitFx();
            assertEquals(41, leftLineOfFirstChange(pane));

            // Text that is not a patch at all.
            FxTestSupport.runOnFx(() -> {
                buffer.getArea().replaceText("just some notes\n");
                diff.openPatchFile(buffer);
            });
            assertEquals(tr("status.diff.patchUnparsable"), w.status());
        }
    }

    // --- plumbing ----------------------------------------------------------------------------------

    private static int leftLineOfFirstChange(DiffViewerPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            com.editora.diff.DiffModels.DiffModel model = FxTestSupport.field(pane, "model");
            return model.rows().get(model.changeBlockStarts().get(0)).leftLine();
        });
    }

    private static javafx.scene.control.Tab tabOf(GitFeatureFx w, Object content) {
        return w.area.tabs().stream()
                .filter(tab -> tab.getUserData() == content)
                .findFirst()
                .orElseThrow();
    }

    private static List<String> labels(DirectoryReviewPane review) {
        List<DirectoryReviewPane.Entry> entries = FxTestSupport.field(review, "entries");
        return entries.stream().map(DirectoryReviewPane.Entry::label).sorted().toList();
    }

    private static DiffViewerPane awaitDiff(AsyncTestScope async, GitFeatureFx w) throws Exception {
        return (DiffViewerPane) awaitContent(async, w, DiffViewerPane.class);
    }

    private static DirectoryReviewPane awaitReview(AsyncTestScope async, GitFeatureFx w) throws Exception {
        return (DirectoryReviewPane) awaitContent(async, w, DirectoryReviewPane.class);
    }

    /** Waits for a tab of {@code type} that was not there when the call was made to become the selected one. */
    private static Object awaitContent(AsyncTestScope async, GitFeatureFx w, Class<?> type) throws Exception {
        Callable<Boolean> shown = () -> {
            javafx.scene.control.Tab tab = w.area.selectedTab();
            return tab != null && type.isInstance(tab.getUserData()) && SEEN.add(tab.getUserData());
        };
        OverlayTestKit.await(async, "a " + type.getSimpleName() + " tab", shown);
        return w.activeContent();
    }

    /** Tab contents already handed to a test, so the next wait is for a new tab; weak, so no window is kept alive. */
    private static final java.util.Set<Object> SEEN =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
}
