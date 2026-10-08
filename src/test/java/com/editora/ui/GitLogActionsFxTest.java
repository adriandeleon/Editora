package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;
import javafx.scene.input.Clipboard;

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
 * What the Git Log's row actions do in the repository the log was listed from: review and compare commits,
 * tag, cherry-pick, revert (a merge asks for its mainline), branch from a commit, and the small ones — copy,
 * open, file history. Real git in a temp repository whose only remote is a bare repository beside it.
 */
@Tag("fx")
class GitLogActionsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * {@code main}: "base" (file.txt, other.txt) then "second" (file.txt changed). {@code side} (from base):
     * "side work" adds side.txt.
     */
    private record Fixture(GitTestRepo repo, Path file, String base, String second, String side) {}

    private static Fixture fixture(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("file.txt", "one\n");
        repo.write("other.txt", "other\n");
        repo.commitAll("base");
        String base = rev(repo, "HEAD");
        repo.git("checkout", "-q", "-b", "side");
        repo.write("side.txt", "from the side branch\n");
        repo.commitAll("side work");
        String side = rev(repo, "HEAD");
        repo.git("checkout", "-q", "main");
        repo.write("file.txt", "one\ntwo\n");
        repo.commitAll("second");
        return new Fixture(repo, file, base, rev(repo, "HEAD"), side);
    }

    private static String rev(GitTestRepo repo, String revision) throws Exception {
        return repo.git("rev-parse", revision).text().strip();
    }

    private record Window(GitFeatureFx w, GitWindowCoordinator windows, GitLogPanel.Actions actions) {}

    /** A window on the repository with the Git Log open and its first page loaded. */
    private static Window window(AsyncTestScope async, Fixture f) throws Exception {
        GitFeatureFx w = GitFeatureFx.create(async);
        w.open(f.file());
        OverlayTestKit.await(async, "the branch name", () -> !w.git.branchName().isBlank());
        GitWindowCoordinator windows = FxTestSupport.field(w.fx.controller, "gitWindows");
        FxTestSupport.runOnFx(windows::showGitLog);
        GitLogPanel panel = FxTestSupport.field(w.fx.controller, "gitLogPanel");
        OverlayTestKit.await(async, "the log to load", () -> panel.loadedCount() > 0);
        return new Window(w, windows, FxTestSupport.callOnFx(windows::gitLogActions));
    }

    /**
     * Back to the repository's file tab, with the log open again. A diff or review tab is not a file: while
     * one is selected a window without a project has no active repository, so the Git Log closes and drops
     * the rows it can no longer act on.
     */
    private static void backToTheFile(AsyncTestScope async, Window win, Fixture f) throws Exception {
        win.w().open(f.file());
        GitLogPanel panel = FxTestSupport.field(win.w().fx.controller, "gitLogPanel");
        FxTestSupport.runOnFx(win.windows()::showGitLog);
        OverlayTestKit.await(async, "the log to list the repository again", () -> panel.loadedCount() > 0);
    }

    private static void awaitMessage(AsyncTestScope async, Window win, String message) throws Exception {
        OverlayTestKit.await(
                async, "\"" + message + "\"", () -> win.w().messages().contains(message));
    }

    private static String shortHash(String hash) {
        return hash.substring(0, 7);
    }

    @Test
    void copyingAHashOrAPathPutsItOnTheClipboardAndSaysSo(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            FxTestSupport.runOnFx(() -> win.actions().copyHash(f.second()));
            assertEquals(
                    f.second(),
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
            assertEquals(
                    tr("status.git.copiedHash", shortHash(f.second())), win.w().status());

            FxTestSupport.runOnFx(() -> win.actions().copyPath("src/a file.txt"));
            assertEquals(
                    "src/a file.txt",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
            assertEquals(tr("status.copiedPath"), win.w().status());

            FxTestSupport.runOnFx(() -> win.actions().commitNotLoaded(f.base()));
            assertEquals(
                    tr("status.git.log.notLoaded", shortHash(f.base())), win.w().status());
        }
    }

    @Test
    void aCommitsFileOpensAsAFileAsADiffOrAsItsOwnHistory(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);
            Path other = f.repo().root.resolve("other.txt");

            FxTestSupport.runOnFx(() -> win.actions().openFile("other.txt"));
            OverlayTestKit.await(
                    async,
                    "other.txt to open",
                    () -> win.w().active() != null
                            && other.equals(win.w().active().getPath()));
            FxTestSupport.runOnFx(() -> win.actions().openFile("side.txt")); // only on the other branch
            assertEquals(tr("status.git.fileGone", "side.txt"), win.w().status());

            // Against its parent: read-only, the commit's two versions.
            FxTestSupport.runOnFx(() -> win.actions().openFileDiff(f.second(), "file.txt", null));
            OverlayTestKit.await(
                    async,
                    "the commit's diff",
                    () -> win.w().area.selectedTab().getUserData() instanceof DiffViewerPane);
            DiffViewerPane parent = (DiffViewerPane) win.w().activeContent();
            assertEquals(tr("diff.title.commitFile", "file.txt", shortHash(f.second())), parent.title());
            assertEquals("one\n", FxTestSupport.field(parent, "leftText"));
            assertEquals("one\ntwo\n", FxTestSupport.field(parent, "rightText"));
            assertEquals(DiffViewerPane.EditableSide.NONE, FxTestSupport.callOnFx(parent::editableSide));

            // Against the working copy — the open buffer's text, unsaved edits included — which can take the
            // old text back.
            backToTheFile(async, win, f);
            FxTestSupport.runOnFx(() -> win.w().active().getArea().appendText("working\n"));
            FxTestSupport.runOnFx(() -> win.actions().compareFileWithWorking(f.base(), "file.txt"));
            OverlayTestKit.await(
                    async,
                    "the working-copy diff",
                    () -> win.w().area.selectedTab().getUserData() instanceof DiffViewerPane pane && pane != parent);
            DiffViewerPane working = (DiffViewerPane) win.w().activeContent();
            assertEquals("one\n", FxTestSupport.field(working, "leftText"));
            assertEquals("one\ntwo\nworking\n", FxTestSupport.field(working, "rightText"));
            assertEquals(DiffViewerPane.EditableSide.RIGHT, FxTestSupport.callOnFx(working::editableSide));

            // The file's own history: the log is re-listed for that file.
            backToTheFile(async, win, f);
            FxTestSupport.runOnFx(() -> win.actions().showFileHistory("other.txt"));
            GitLogPanel panel = FxTestSupport.field(win.w().fx.controller, "gitLogPanel");
            OverlayTestKit.await(async, "other.txt's one commit", () -> panel.loadedCount() == 1);
            assertEquals(f.base(), FxTestSupport.callOnFx(panel::lastLoadedHash));
            FxTestSupport.runOnFx(win.actions()::showAll);
            OverlayTestKit.await(async, "the whole history again", () -> panel.loadedCount() == 2);
        }
    }

    @Test
    void aWholeCommitOrTwoCommitsOpenAsAReviewAndEmptyOnesSaySo(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().git("commit", "-q", "--allow-empty", "-m", "nothing changed");
        String empty = rev(f.repo(), "HEAD");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            FxTestSupport.runOnFx(() -> win.actions().reviewCommit(f.second()));
            OverlayTestKit.await(
                    async,
                    "the commit review",
                    () -> win.w().area.selectedTab().getUserData() instanceof DirectoryReviewPane);
            DirectoryReviewPane review = (DirectoryReviewPane) win.w().activeContent();
            assertEquals(tr("diff.title.commitReview", shortHash(f.second())), review.title());
            assertEquals(List.of("file.txt"), labels(review));

            backToTheFile(async, win, f);
            FxTestSupport.runOnFx(() -> win.actions().reviewCommit(empty));
            awaitMessage(async, win, tr("status.git.log.noFiles", shortHash(empty)));

            FxTestSupport.runOnFx(() -> win.actions().compareCommits(f.base(), f.side()));
            OverlayTestKit.await(
                    async,
                    "the comparison",
                    () -> win.w().area.selectedTab().getUserData() instanceof DirectoryReviewPane pane
                            && pane != review);
            DirectoryReviewPane compare = (DirectoryReviewPane) win.w().activeContent();
            assertEquals(tr("diff.title.commitCompare", shortHash(f.base()), shortHash(f.side())), compare.title());
            assertEquals(List.of("side.txt"), labels(compare));

            backToTheFile(async, win, f);
            FxTestSupport.runOnFx(() -> win.actions().compareCommits(f.second(), empty));
            awaitMessage(async, win, tr("status.git.log.identical", shortHash(f.second()), shortHash(empty)));

            String missing = "0123456789012345678901234567890123456789";
            FxTestSupport.runOnFx(() -> win.actions().compareCommits(f.base(), missing));
            OverlayTestKit.await(
                    async,
                    "the compare failure",
                    () -> win.w().messages().stream()
                            .anyMatch(message -> message.startsWith(
                                    tr("status.git.log.compareFailed", "").stripTrailing())));

            // Two commits must be selected for the palette's compare; one is not enough.
            GitLogPanel panel = FxTestSupport.field(win.w().fx.controller, "gitLogPanel");
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.ListView<?> commits = FxTestSupport.field(panel, "commits");
                commits.getSelectionModel().clearAndSelect(0);
                win.windows().compareSelectedCommits();
            });
            assertEquals(tr("status.git.log.selectTwo"), win.w().status());
        }
    }

    private static List<String> labels(DirectoryReviewPane review) {
        List<DirectoryReviewPane.Entry> entries = FxTestSupport.field(review, "entries");
        return entries.stream().map(DirectoryReviewPane.Entry::label).sorted().toList();
    }

    @Test
    void aTagIsNamedThenGivenAMessageAndAnInvalidNameIsRefused(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            // An annotated tag at a chosen commit.
            FxTestSupport.runOnFx(() -> win.actions().newTag(f.base()));
            assertEquals(
                    tr("dialog.newTag.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), " v1.0 "));
            OverlayTestKit.await(
                    async,
                    "the message prompt",
                    () -> OverlayTestKit.form(win.w().scene()) != null);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "  First release  "));
            awaitMessage(async, win, tr("status.git.tag.created", "v1.0"));
            assertEquals(f.base(), rev(f.repo(), "v1.0^{commit}"));
            assertEquals("tag", f.repo().git("cat-file", "-t", "v1.0").text().strip(), "annotated");
            assertEquals(
                    "First release",
                    f.repo()
                            .git("tag", "-l", "--format=%(contents:subject)", "v1.0")
                            .text()
                            .strip());

            // No message: a lightweight tag, at HEAD when no commit is named.
            FxTestSupport.runOnFx(() -> win.windows().createTagIn(f.repo().root, null));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "light"));
            OverlayTestKit.await(
                    async,
                    "the message prompt",
                    () -> OverlayTestKit.form(win.w().scene()) != null);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "   "));
            awaitMessage(async, win, tr("status.git.tag.created", "light"));
            assertEquals(
                    "commit", f.repo().git("cat-file", "-t", "light").text().strip(), "lightweight");
            assertEquals(f.second(), rev(f.repo(), "light"));

            // A name git would refuse is refused before the second prompt; a blank one is just dropped.
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> win.actions().newTag(f.base()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "bad..name"));
            assertEquals(tr("status.git.tag.invalidName", "bad..name"), win.w().status());
            async.awaitFx();
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(win.w().scene())), "no message prompt");
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> win.actions().newTag(f.base()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "   "));
            async.awaitFx();
            assertEquals("", win.w().status());
            FxTestSupport.runOnFx(() -> win.windows().createTag(f.repo().root, "ok", "", "--force"));
            assertEquals(tr("status.git.tag.invalidName", "ok"), win.w().status(), "an option is not a commit");
            assertEquals(
                    List.of("light", "v1.0"),
                    f.repo().git("tag").text().lines().sorted().toList());
        }
    }

    @Test
    void aTagIsCheckedOutPushedAndDeletedOnlyAfterConfirmation(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().git("tag", "v1", f.base());
        Path remote = dir.toRealPath().resolve("origin.git");
        assertEquals(
                0,
                new ProcessBuilder("git", "init", "-q", "--bare", "-b", "main", remote.toString())
                        .inheritIO()
                        .start()
                        .waitFor());
        f.repo().git("remote", "add", "origin", remote.toString());
        f.repo().git("push", "-q", "--set-upstream", "origin", "main");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            FxTestSupport.runOnFx(() -> win.actions().pushTag("v1"));
            awaitMessage(async, win, tr("status.gitDone", tr("gitlabel.pushTag", "v1")));
            Process show = new ProcessBuilder(
                            "git", "--git-dir", remote.toString(), "rev-parse", "-q", "--verify", "refs/tags/v1")
                    .start();
            assertEquals(
                    f.base(),
                    new String(show.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip(),
                    "the tag reached the remote");

            // A name that could be an option never reaches git.
            for (Runnable refused : List.<Runnable>of(
                    () -> win.actions().pushTag("--delete"),
                    () -> win.actions().checkoutTag("-f"),
                    () -> win.actions().deleteTag("--all"))) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(refused);
                assertTrue(
                        win.w()
                                .status()
                                .startsWith(tr("status.git.unsafeRef", "").stripTrailing()),
                        win.w().status());
            }

            // Deleting asks first, naming the tag and the repository; declined, the tag stays.
            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(() -> win.actions().deleteTag("v1"));
            async.await(declined, "the delete-tag question");
            assertEquals(
                    tr("dialog.deleteTag.content", "v1", f.repo().root.toString()),
                    question.get().content());
            assertEquals("v1", f.repo().git("tag").text().strip());

            FxTestSupport.runOnFx(() -> win.actions().checkoutTag("v1"));
            awaitMessage(async, win, tr("status.git.checkedOut", "v1"));
            assertEquals(f.base(), rev(f.repo(), "HEAD"));
            assertEquals("", f.repo().git("branch", "--show-current").text().strip(), "HEAD is detached at the tag");

            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            FxTestSupport.runOnFx(() -> win.actions().deleteTag("v1"));
            async.await(agreed, "the delete-tag question, accepted");
            awaitMessage(async, win, tr("status.git.tag.deleted", "v1"));
            assertEquals("", f.repo().git("tag").text().strip());
        }
    }

    @Test
    void cherryPickAndNewBranchActOnTheChosenCommit(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            FxTestSupport.runOnFx(() -> win.actions().cherryPick(f.side()));
            awaitMessage(async, win, tr("status.git.cherryPicked", shortHash(f.side())));
            assertEquals(
                    "from the side branch\n", Files.readString(f.repo().root.resolve("side.txt")));
            assertEquals(
                    "side work", f.repo().git("log", "-1", "--format=%s").text().strip());

            // A blank branch name creates nothing.
            FxTestSupport.runOnFx(() -> win.actions().newBranch(f.base()));
            assertEquals(
                    tr("dialog.newBranch.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "   "));
            async.awaitFx();
            assertEquals("main", f.repo().git("branch", "--show-current").text().strip());

            FxTestSupport.runOnFx(() -> win.actions().newBranch(f.base()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), " from-base "));
            awaitMessage(async, win, tr("status.createdBranch", "from-base"));
            assertEquals(
                    "from-base", f.repo().git("branch", "--show-current").text().strip());
            assertEquals(f.base(), rev(f.repo(), "HEAD"));
        }
    }

    /** A merge has two sides: reverting it asks which is the mainline, and without an answer nothing runs. */
    @Test
    void revertingAMergeAsksForTheMainlineParent(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        f.repo().git("merge", "-q", "--no-ff", "--no-edit", "side");
        String merge = rev(f.repo(), "HEAD");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);

            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch cancelled =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(() -> win.actions().revert(merge));
            async.await(cancelled, "the mainline question");
            async.awaitFx();
            assertEquals(tr("status.git.revertMergeCancelled"), win.w().status());
            assertEquals(merge, rev(f.repo(), "HEAD"), "nothing was reverted");

            // The first choice is parent 1 — main as it was — so the revert takes the side branch's file away.
            CountDownLatch accepted = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            FxTestSupport.runOnFx(() -> win.actions().revert(merge));
            async.await(accepted, "the mainline question, accepted");
            awaitMessage(async, win, tr("status.git.reverted", shortHash(merge)));
            assertFalse(Files.exists(f.repo().root.resolve("side.txt")), "the merged-in change is undone");
            assertEquals("one\ntwo\n", Files.readString(f.file()), "main's own work is kept");
        }
    }

    @Test
    void anOrdinaryCommitIsRevertedWithoutAQuestionEvenWhenItIsNotALoadedRow(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);
            FxTestSupport.runOnFx(() -> win.windows().mainlineChooser = (hash, parents) -> {
                throw new AssertionError("a commit with one parent has no mainline to choose");
            });

            FxTestSupport.runOnFx(() -> win.actions().revert(f.second()));
            awaitMessage(async, win, tr("status.git.reverted", shortHash(f.second())));
            assertEquals("one\n", Files.readString(f.file()));

            // A commit the log has not listed (it is on another branch): git is asked for its parents.
            FxTestSupport.runOnFx(() -> win.actions().cherryPick(f.side()));
            awaitMessage(async, win, tr("status.git.cherryPicked", shortHash(f.side())));
            FxTestSupport.runOnFx(() -> win.windows().revertIn(f.repo().root, f.side()));
            awaitMessage(async, win, tr("status.git.reverted", shortHash(f.side())));
            assertFalse(Files.exists(f.repo().root.resolve("side.txt")));
        }
    }

    @Test
    void theLogCommandsNeedARepository(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("plain.txt"), "no repository here\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(plain, 0));
            OverlayTestKit.await(
                    async,
                    "the tab",
                    () -> w.active() != null && plain.equals(w.active().getPath()));
            FxTestSupport.runOnFx(w.git::refresh);
            async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
            async.awaitFx();
            GitWindowCoordinator windows = FxTestSupport.field(w.fx.controller, "gitWindows");

            for (Runnable command : List.<Runnable>of(
                    () -> windows.reviewCommitIn(null, "abc1234"),
                    () -> windows.compareCommitsIn(null, "abc1234", "def5678"),
                    () -> windows.revertIn(null, "abc1234"),
                    () -> windows.createTagIn(null, "abc1234"),
                    windows::createTagCommand,
                    windows::focusGitLogSearch,
                    () -> windows.pickTagThen("branch.pick.delete", (root, tag) -> {
                        throw new AssertionError("no repository: no tag to act on");
                    }))) {
                w.clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.notARepo"), w.status());
            }
            FxTestSupport.runOnFx(() -> windows.pushTagIn(null, "v1")); // nowhere to push from: nothing runs
            FxTestSupport.runOnFx(() -> windows.deleteTagIn(null, "v1"));
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(w.scene())), "nothing was asked");
        }
    }

    @Test
    void theLogsOwnCommandsSearchPageAndSwitchBetweenBranches(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, f);
            GitLogPanel panel = FxTestSupport.field(win.w().fx.controller, "gitLogPanel");
            assertEquals(2, FxTestSupport.callOnFx(panel::loadedCount), "main's two commits");

            FxTestSupport.runOnFx(win.actions()::toggleAllBranches);
            assertEquals(tr("status.git.log.allBranches"), win.w().status());
            OverlayTestKit.await(async, "the side branch's commit too", () -> panel.loadedCount() == 3);
            FxTestSupport.runOnFx(win.actions()::toggleAllBranches);
            assertEquals(tr("status.git.log.currentBranch"), win.w().status());
            OverlayTestKit.await(async, "main only again", () -> panel.loadedCount() == 2);

            FxTestSupport.runOnFx(() -> win.actions().searchHistory("  second  "));
            OverlayTestKit.await(async, "the search result", () -> panel.loadedCount() == 1);
            assertEquals(f.second(), FxTestSupport.callOnFx(panel::lastLoadedHash));
            FxTestSupport.runOnFx(() -> win.actions().searchHistory(null));
            OverlayTestKit.await(async, "the search to end", () -> panel.loadedCount() == 2);

            FxTestSupport.runOnFx(win.windows()::loadMoreCommand);
            assertEquals(tr("status.git.log.noMore"), win.w().status(), "two commits fit in the first page");
            FxTestSupport.runOnFx(win.windows()::focusGitLogSearch);
            assertEquals(tr("status.git.log.searchHint"), win.w().status());

            // A commit made behind the log's back is there after Refresh.
            f.repo().write("file.txt", "one\ntwo\nthree\n");
            f.repo().commitAll("third");
            FxTestSupport.runOnFx(win.actions()::refresh);
            OverlayTestKit.await(async, "the new commit", () -> panel.loadedCount() == 3);

            // Create Patch hands the commit to the patch flow, which asks where the patch goes.
            FxTestSupport.runOnFx(() -> win.actions().createPatch(f.second()));
            assertEquals(
                    2,
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.pickerItems(win.w().scene()).size()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.cancelPicker(win.w().scene()));
        }
    }
}
